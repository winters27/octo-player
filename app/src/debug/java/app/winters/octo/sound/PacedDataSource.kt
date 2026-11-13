package app.winters.octo.sound

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

// The debug build's paced source: a local file handed out no faster than
// `bytesPerSecond` (the song's own rate for real time, more for a refill
// faster than real time), and not at all while stopped, so a deck can be
// made to run dry and refill on command.
@UnstableApi
class PacedDataSource(private val inner: DataSource, private val control: Control) : DataSource {
    // Shared by every source a factory makes, so a test steers them all.
    class Control(@Volatile var bytesPerSecond: Long) {
        @Volatile var stopped = false
        private val lock = Object()

        fun stop() {
            stopped = true
        }

        fun resume(bytesPerSecond: Long = this.bytesPerSecond) {
            this.bytesPerSecond = bytesPerSecond
            synchronized(lock) {
                stopped = false
                lock.notifyAll()
            }
        }

        // Returns whether it waited.
        internal fun waitWhileStopped(): Boolean {
            var waited = false
            synchronized(lock) {
                while (stopped) {
                    waited = true
                    lock.wait(50)
                }
            }
            return waited
        }
    }

    class Factory(private val inner: DataSource.Factory, val control: Control) : DataSource.Factory {
        override fun createDataSource(): DataSource = PacedDataSource(inner.createDataSource(), control)
    }

    private var openedAt = 0L
    private var handed = 0L

    override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        openedAt = SystemClock.elapsedRealtime()
        handed = 0
        return inner.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val rate = control.bytesPerSecond
        // After a stop the pace starts again from now, with no burst.
        if (control.waitWhileStopped() && rate > 0) openedAt = SystemClock.elapsedRealtime() - handed * 1_000 / rate
        if (rate > 0) {
            val allowed = (SystemClock.elapsedRealtime() - openedAt) * rate / 1_000 - handed
            if (allowed <= 0) {
                SystemClock.sleep(5)
                return 0
            }
            val n = inner.read(buffer, offset, minOf(length.toLong(), allowed).toInt())
            if (n > 0) handed += n
            return n
        }
        val n = inner.read(buffer, offset, length)
        if (n > 0) handed += n
        return if (n == C.RESULT_END_OF_INPUT) C.RESULT_END_OF_INPUT else n
    }

    override fun getUri(): Uri? = inner.uri

    override fun close() = inner.close()
}

package app.winters.octo.playback

import android.annotation.SuppressLint
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// Linux's F_SETPIPE_SZ, which OsConstants does not name.
private const val F_SETPIPE_SZ = 1031

// One pipe a cover's bytes go through to the app that asked for them.
interface CoverPipe {
    // What the asking app reads.
    val reader: ParcelFileDescriptor

    // Raises the pipe's capacity to at least `bytes`; returns the capacity
    // it has now, or -1 when it cannot be raised.
    fun grow(bytes: Int): Int

    // Makes writes return at once when the pipe is full.
    fun nonBlocking()

    // Writes what fits now, from `from` on; returns the bytes taken.
    // Throws when the reader has gone.
    fun write(bytes: ByteArray, from: Int): Int

    // Done: the reader sees the end of the picture.
    fun finish()

    // Failed: the reader sees an error instead of a picture.
    fun fail(message: String)
}

// Makes pipes, and waits until any of them has room.
interface CoverPipes {
    fun open(): CoverPipe

    // Whether each pipe has room for a write, waiting at most `timeoutMs`.
    fun room(pipes: List<CoverPipe>, timeoutMs: Int): BooleanArray
}

// The phone's pipes: reliable pipes, so a failure reaches the reader as an
// error, never as a broken picture.
class SystemCoverPipes : CoverPipes {
    private class Pipe(override val reader: ParcelFileDescriptor, val writer: ParcelFileDescriptor) : CoverPipe {
        // fcntl on the writer. Os.fcntlInt is public from Android 11; Android
        // 10 has the same method outside the SDK, so where it is missing the
        // call fails as fcntl would, and the picture fails instead of the app.
        @SuppressLint("NewApi")
        private fun fcntl(cmd: Int, arg: Int): Int = try {
            Os.fcntlInt(writer.fileDescriptor, cmd, arg)
        } catch (e: LinkageError) {
            throw ErrnoException("fcntl", OsConstants.ENOSYS)
        }

        override fun grow(bytes: Int): Int = try {
            fcntl(F_SETPIPE_SZ, bytes)
        } catch (e: ErrnoException) {
            -1
        }

        override fun nonBlocking() {
            try {
                val flags = fcntl(OsConstants.F_GETFL, 0)
                fcntl(OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
            } catch (e: ErrnoException) {
                throw IOException(e)
            }
        }

        override fun write(bytes: ByteArray, from: Int): Int = try {
            Os.write(writer.fileDescriptor, bytes, from, bytes.size - from)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.EAGAIN) 0 else throw IOException(e)
        }

        override fun finish() {
            runCatching { writer.close() }
        }

        override fun fail(message: String) {
            runCatching { writer.closeWithError(message) }
        }
    }

    override fun open(): CoverPipe {
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        return Pipe(read, write)
    }

    override fun room(pipes: List<CoverPipe>, timeoutMs: Int): BooleanArray {
        val fds = Array(pipes.size) { i ->
            StructPollfd().apply {
                fd = (pipes[i] as Pipe).writer.fileDescriptor
                events = OsConstants.POLLOUT.toShort()
            }
        }
        try {
            Os.poll(fds, timeoutMs)
        } catch (e: ErrnoException) {
            return BooleanArray(pipes.size)
        }
        return BooleanArray(pipes.size) { fds[it].revents.toInt() and (OsConstants.POLLOUT or OsConstants.POLLERR or OsConstants.POLLHUP) != 0 }
    }
}

// Fills pipes with whole pictures. A picture goes in one write once the
// pipe is raised to hold it, so no reader can hold up the thread that made
// it; when the pipe cannot be raised that far, one writer thread writes
// without blocking to every such pipe as it has room, each with its own
// deadline, after which that pipe fails, so a picture no one reads never
// holds up another.
class CoverFeeder(private val pipes: CoverPipes, private val deadlineMs: () -> Long, private val clock: () -> Long = System::nanoTime) {
    private class Pending(val pipe: CoverPipe, val bytes: ByteArray, var at: Int, val deadline: Long)

    private val queue = ConcurrentLinkedQueue<Pending>()
    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    @Volatile private var writer: Thread? = null

    // How many pictures went the writer thread's way, for checks.
    private val slow = AtomicInteger()
    val slowWrites: Int get() = slow.get()

    fun fill(pipe: CoverPipe, bytes: ByteArray) {
        val room = pipe.grow(bytes.size)
        if (room >= bytes.size) {
            try {
                var at = 0
                while (at < bytes.size) {
                    val n = pipe.write(bytes, at)
                    if (n <= 0) break
                    at += n
                }
                if (at == bytes.size) {
                    pipe.finish()
                    return
                }
                handOver(pipe, bytes, at)
            } catch (e: IOException) {
                pipe.fail("The reader went")
            }
            return
        }
        handOver(pipe, bytes, 0)
    }

    private fun handOver(pipe: CoverPipe, bytes: ByteArray, at: Int) {
        try {
            pipe.nonBlocking()
        } catch (e: IOException) {
            pipe.fail("No pipe")
            return
        }
        slow.incrementAndGet()
        queue.add(Pending(pipe, bytes, at, clock() + TimeUnit.MILLISECONDS.toNanos(deadlineMs())))
        lock.withLock { arrived.signal() }
        startWriter()
    }

    private fun startWriter() {
        if (writer != null) return
        synchronized(this) {
            if (writer != null) return
            writer = Thread({ writeLoop() }, "octo-cover-writer").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun writeLoop() {
        val live = ArrayList<Pending>()
        while (true) {
            while (true) live += queue.poll() ?: break
            if (live.isEmpty()) {
                lock.withLock {
                    if (queue.isEmpty()) arrived.await(1, TimeUnit.SECONDS)
                }
                continue
            }
            val ready = pipes.room(live.map { it.pipe }, POLL_MS)
            val now = clock()
            val it = live.listIterator()
            var i = 0
            while (it.hasNext()) {
                val p = it.next()
                val index = i++
                try {
                    if (ready[index]) {
                        val n = p.pipe.write(p.bytes, p.at)
                        if (n > 0) p.at += n
                    }
                    if (p.at >= p.bytes.size) {
                        p.pipe.finish()
                        it.remove()
                    } else if (now >= p.deadline) {
                        p.pipe.fail("Nobody read the picture")
                        it.remove()
                    }
                } catch (e: IOException) {
                    p.pipe.fail("The reader went")
                    it.remove()
                }
            }
        }
    }

    private companion object {
        // The writer looks again this often for new pipes and deadlines.
        const val POLL_MS = 20
    }
}

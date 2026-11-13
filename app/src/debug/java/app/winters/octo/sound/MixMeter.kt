package app.winters.octo.sound

import android.os.Process
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections

// The debug build's measuring rig on the mixer: every frame it writes to
// a real output, kept for the last `seconds`, every head read, what the
// mixer noted, and the CPU of any thread by its id. Set as DeckMixer.probe.
class MixMeter(private val mixRate: Int, seconds: Int = 60) : MixProbe {
    private val capacity = mixRate * seconds
    private val left = FloatArray(capacity)
    private val right = FloatArray(capacity)
    private var kept = 0L
    private var firstFrame = -1L

    class HeadRead(val output: Int, val nanos: Long, val head: Long, val written: Long)

    class Note(val nanos: Long, val what: String, val frame: Long)

    val heads: MutableList<HeadRead> = Collections.synchronizedList(ArrayList())
    val notes: MutableList<Note> = Collections.synchronizedList(ArrayList())

    @Synchronized
    override fun written(output: Int, frame: Long, samples: FloatArray, offset: Int, frames: Int) {
        if (firstFrame < 0) firstFrame = frame
        for (i in 0 until frames) {
            val at = ((kept + i) % capacity).toInt()
            left[at] = samples[(offset + i) * 2]
            right[at] = samples[(offset + i) * 2 + 1]
        }
        kept += frames
    }

    override fun head(output: Int, nanos: Long, head: Long, written: Long) {
        if (heads.size < MOST_HEAD_READS) heads += HeadRead(output, nanos, head, written)
    }

    override fun note(what: String, frame: Long) {
        notes += Note(System.nanoTime(), what, frame)
    }

    // The frames kept, as a float32 stereo WAV at the mix rate.
    @Synchronized
    fun writeWav(file: File) {
        val n = minOf(kept, capacity.toLong()).toInt()
        val start = if (kept > capacity) (kept % capacity).toInt() else 0
        val data = ByteBuffer.allocate(n * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val at = (start + i) % capacity
            data.putFloat(left[at]).putFloat(right[at])
        }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + n * 8).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(3).putShort(2).putInt(mixRate).putInt(mixRate * 8).putShort(8).putShort(32)
        header.put("data".toByteArray()).putInt(n * 8)
        file.outputStream().use {
            it.write(header.array())
            it.write(data.array())
        }
    }

    companion object {
        private const val MOST_HEAD_READS = 200_000

        // A thread's user plus system CPU time in ms, from /proc, or -1.
        fun cpuMs(tid: Int = Process.myTid()): Long = try {
            RandomAccessFile("/proc/self/task/$tid/stat", "r").use { f ->
                val parts = f.readLine().substringAfterLast(')').trim().split(' ')
                // utime and stime are fields 14 and 15, 12 and 13 after the name.
                val ticks = parts[11].toLong() + parts[12].toLong()
                ticks * 10
            }
        } catch (e: Exception) {
            -1
        }
    }
}

package app.winters.octo.desktop.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

// Writes a quiet sine tone as a 16-bit mono WAV file, for the tests and for
// checking that sound comes out, without anyone's music.
fun writeSine(file: File, seconds: Int, rate: Int = 48_000, hz: Double = 440.0, level: Double = 0.2) {
    file.parentFile?.mkdirs()
    val frames = seconds.toLong() * rate
    val dataBytes = frames * 2
    RandomAccessFile(file, "rw").use { out ->
        out.setLength(0)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(dataBytes.toInt())
        out.write(header.array())
        val chunk = ByteBuffer.allocate(rate * 2).order(ByteOrder.LITTLE_ENDIAN)
        var frame = 0L
        while (frame < frames) {
            chunk.clear()
            val count = minOf(rate.toLong(), frames - frame).toInt()
            repeat(count) { i ->
                val value = sin(2 * PI * hz * (frame + i) / rate) * level * Short.MAX_VALUE
                chunk.putShort(value.toInt().toShort())
            }
            out.write(chunk.array(), 0, count * 2)
            frame += count
        }
    }
}

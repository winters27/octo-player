package app.winters.octo.offline

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

// AAC as servers make it (ADTS: a header before every frame) put into an
// M4A, so the file says its own length exactly: a player reads ADTS's length
// from its bitrate, which is a guess. Only the container changes; the AAC
// frames are copied as they are. The M4A's index comes before its sound, so
// it plays from the first bytes.
object AdtsToM4a {
    // Samples in one AAC frame.
    private const val SAMPLES_PER_FRAME = 1024

    private val SampleRates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    // One AAC frame inside the ADTS file: where its sound starts and how long it is.
    private class Frame(val offset: Long, val size: Int)

    // What every frame of one ADTS file shares.
    private class Stream(val profile: Int, val rateIndex: Int, val channels: Int, val frames: List<Frame>)

    // Writes `input` (ADTS) into `output` as an M4A, then reads `output` back
    // and checks it holds every frame. False, with `output` deleted, when
    // `input` is not ADTS this can carry (another layout part way, more than
    // one frame a header, frames of mixed settings) or the written file does
    // not read back whole. A last frame cut short is left out, and bytes of
    // zero after the last frame (padding) end the stream.
    fun rewrap(input: File, output: File): Boolean {
        val stream = try {
            read(input)
        } catch (e: IOException) {
            null
        }
        if (stream == null || stream.frames.isEmpty()) {
            output.delete()
            return false
        }
        val written = try {
            write(input, stream, output)
            samplesIn(output) == stream.frames.size
        } catch (e: IOException) {
            false
        }
        if (!written) output.delete()
        return written
    }

    // The ADTS file's frames, or null when it is not one this can carry.
    private fun read(input: File): Stream? = RandomAccessFile(input, "r").use { file ->
        val length = file.length()
        val header = ByteArray(9)
        val frames = ArrayList<Frame>()
        var at = 0L
        var settings: Triple<Int, Int, Int>? = null
        while (at + 7 <= length) {
            file.seek(at)
            file.readFully(header, 0, 7)
            val b = IntArray(7) { header[it].toInt() and 0xFF }
            if (b[0] != 0xFF || (b[1] and 0xF6) != 0xF0) {
                // Padding of zeros ends the stream; anything else is not ADTS.
                return@use if (allZero(file, at, length)) Stream(settings ?: return@use null, frames) else null
            }
            val protectionAbsent = b[1] and 0x01
            val profile = (b[2] shr 6) and 0x3
            val rateIndex = (b[2] shr 2) and 0xF
            val channels = ((b[2] and 0x01) shl 2) or ((b[3] shr 6) and 0x3)
            val frameLength = ((b[3] and 0x03) shl 11) or (b[4] shl 3) or ((b[5] shr 5) and 0x7)
            val blocks = b[6] and 0x03
            val headerLength = if (protectionAbsent == 1) 7 else 9
            if (rateIndex >= SampleRates.size || channels == 0 || blocks != 0 || frameLength <= headerLength) return@use null
            val now = Triple(profile, rateIndex, channels)
            if (settings != null && settings != now) return@use null
            settings = now
            // A last frame cut short is left out.
            if (at + frameLength > length) break
            frames += Frame(at + headerLength, frameLength - headerLength)
            at += frameLength
        }
        val (profile, rateIndex, channels) = settings ?: return@use null
        Stream(profile, rateIndex, channels, frames)
    }

    private fun Stream(settings: Triple<Int, Int, Int>, frames: List<Frame>) = Stream(settings.first, settings.second, settings.third, frames)

    private fun allZero(file: RandomAccessFile, from: Long, to: Long): Boolean {
        file.seek(from)
        val buffer = ByteArray(8192)
        var left = to - from
        while (left > 0) {
            val read = file.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read <= 0) return false
            for (i in 0 until read) if (buffer[i].toInt() != 0) return false
            left -= read
        }
        return true
    }

    private fun write(input: File, stream: Stream, output: File) {
        val rate = SampleRates[stream.rateIndex]
        val sizes = stream.frames.map { it.size }
        val soundBytes = sizes.fold(0L) { sum, size -> sum + size }
        val ftyp = box("ftyp") {
            writeBytes("M4A ")
            writeInt(0)
            writeBytes("M4A ")
            writeBytes("mp42")
            writeBytes("isom")
        }
        // The index is built twice: the first time to learn its size, which
        // decides where the sound starts.
        val draft = moov(stream, rate, sizes, chunkOffset = 0)
        val soundStart = ftyp.size.toLong() + draft.size + 8
        if (soundStart + soundBytes > 0xFFFFFFFFL) throw IOException("too long for an M4A of 32-bit offsets")
        val moov = moov(stream, rate, sizes, chunkOffset = soundStart)
        BufferedOutputStream(FileOutputStream(output), 64 * 1024).use { out ->
            out.write(ftyp)
            out.write(moov)
            DataOutputStream(out).apply {
                writeInt((8 + soundBytes).toInt())
                writeBytes("mdat")
                flush()
            }
            RandomAccessFile(input, "r").use { file ->
                val buffer = ByteArray(8192)
                for (frame in stream.frames) {
                    file.seek(frame.offset)
                    file.readFully(buffer, 0, frame.size)
                    out.write(buffer, 0, frame.size)
                }
            }
        }
    }

    private fun moov(stream: Stream, rate: Int, sizes: List<Int>, chunkOffset: Long): ByteArray {
        val samples = sizes.size
        val duration = samples.toLong() * SAMPLES_PER_FRAME
        val largest = sizes.maxOrNull() ?: 0
        val totalBytes = sizes.fold(0L) { sum, size -> sum + size }
        val average = if (duration > 0) (totalBytes * 8 * rate / duration) else 0L
        val peak = largest.toLong() * 8 * rate / SAMPLES_PER_FRAME
        val matrix = intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)
        // AudioSpecificConfig: object type (profile + 1), rate index, channels,
        // then three bits of zero.
        val objectType = stream.profile + 1
        val asc0 = (objectType shl 3) or (stream.rateIndex shr 1)
        val asc1 = ((stream.rateIndex and 0x1) shl 7) or (stream.channels shl 3)
        return box("moov") {
            write(box("mvhd") {
                writeInt(0)
                writeInt(0)
                writeInt(0)
                writeInt(rate)
                writeInt(duration.toInt())
                writeInt(0x00010000)
                writeShort(0x0100)
                write(ByteArray(10))
                matrix.forEach(::writeInt)
                write(ByteArray(24))
                writeInt(2)
            })
            write(box("trak") {
                write(box("tkhd") {
                    writeInt(0x00000003)
                    writeInt(0)
                    writeInt(0)
                    writeInt(1)
                    writeInt(0)
                    writeInt(duration.toInt())
                    write(ByteArray(8))
                    writeShort(0)
                    writeShort(0)
                    writeShort(0x0100)
                    writeShort(0)
                    matrix.forEach(::writeInt)
                    writeInt(0)
                    writeInt(0)
                })
                write(box("mdia") {
                    write(box("mdhd") {
                        writeInt(0)
                        writeInt(0)
                        writeInt(0)
                        writeInt(rate)
                        writeInt(duration.toInt())
                        writeShort(0x55C4)
                        writeShort(0)
                    })
                    write(box("hdlr") {
                        writeInt(0)
                        writeInt(0)
                        writeBytes("soun")
                        write(ByteArray(12))
                        writeBytes("SoundHandler")
                        writeByte(0)
                    })
                    write(box("minf") {
                        write(box("smhd") {
                            writeInt(0)
                            writeShort(0)
                            writeShort(0)
                        })
                        write(box("dinf") {
                            write(box("dref") {
                                writeInt(0)
                                writeInt(1)
                                write(box("url ") { writeInt(1) })
                            })
                        })
                        write(box("stbl") {
                            write(box("stsd") {
                                writeInt(0)
                                writeInt(1)
                                write(box("mp4a") {
                                    write(ByteArray(6))
                                    writeShort(1)
                                    write(ByteArray(8))
                                    writeShort(stream.channels)
                                    writeShort(16)
                                    writeShort(0)
                                    writeShort(0)
                                    writeInt(if (rate < 65536) rate shl 16 else 0)
                                    write(box("esds") {
                                        writeInt(0)
                                        // ES_Descriptor
                                        writeByte(0x03)
                                        writeByte(3 + 15 + 4 + 3)
                                        writeShort(0)
                                        writeByte(0)
                                        // DecoderConfigDescriptor
                                        writeByte(0x04)
                                        writeByte(13 + 4)
                                        writeByte(0x40)
                                        writeByte(0x15)
                                        writeByte((largest shr 16) and 0xFF)
                                        writeByte((largest shr 8) and 0xFF)
                                        writeByte(largest and 0xFF)
                                        writeInt(peak.toInt())
                                        writeInt(average.toInt())
                                        // DecoderSpecificInfo: the AudioSpecificConfig
                                        writeByte(0x05)
                                        writeByte(2)
                                        writeByte(asc0)
                                        writeByte(asc1)
                                        // SLConfigDescriptor
                                        writeByte(0x06)
                                        writeByte(1)
                                        writeByte(0x02)
                                    })
                                })
                            })
                            write(box("stts") {
                                writeInt(0)
                                writeInt(1)
                                writeInt(samples)
                                writeInt(SAMPLES_PER_FRAME)
                            })
                            write(box("stsc") {
                                writeInt(0)
                                writeInt(1)
                                writeInt(1)
                                writeInt(samples)
                                writeInt(1)
                            })
                            write(box("stsz") {
                                writeInt(0)
                                writeInt(0)
                                writeInt(samples)
                                sizes.forEach(::writeInt)
                            })
                            write(box("stco") {
                                writeInt(0)
                                writeInt(1)
                                writeInt(chunkOffset.toInt())
                            })
                        })
                    })
                })
            })
        }
    }

    // One box: its size, its four letters, then what `fill` writes.
    private fun box(type: String, fill: DataOutputStream.() -> Unit): ByteArray {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).apply {
            fill()
            flush()
        }
        val out = ByteArrayOutputStream(body.size() + 8)
        DataOutputStream(out).apply {
            writeInt(body.size() + 8)
            writeBytes(type)
            write(body.toByteArray())
            flush()
        }
        return out.toByteArray()
    }

    // How many samples an M4A this wrote holds, read back from the file: its
    // sample table's count, when the file starts with `ftyp`, its `moov`
    // comes before an `mdat` that runs to the end of the file, and the one
    // chunk's offset is where the `mdat`'s sound starts. Null otherwise.
    fun samplesIn(file: File): Int? = try {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            var at = 0L
            var samples: Int? = null
            var chunkOffset: Long? = null
            var sawFtyp = false
            var sawMoov = false
            while (at + 8 <= length) {
                raf.seek(at)
                val size = raf.readInt().toLong() and 0xFFFFFFFFL
                val type = ByteArray(4).also(raf::readFully).toString(Charsets.US_ASCII)
                if (size < 8 || at + size > length) return@use null
                when (type) {
                    "ftyp" -> if (at == 0L) sawFtyp = true else return@use null
                    "moov" -> {
                        sawMoov = true
                        val table = findBox(raf, at + 8, at + size, listOf("trak", "mdia", "minf", "stbl")) ?: return@use null
                        val stsz = findChild(raf, table.first, table.second, "stsz") ?: return@use null
                        // After the size and letters: version and flags, the constant size, then the count.
                        raf.seek(stsz.first + 8 + 8)
                        samples = raf.readInt()
                        val stco = findChild(raf, table.first, table.second, "stco") ?: return@use null
                        // After the size and letters: version and flags, the entry count, then the offset.
                        raf.seek(stco.first + 8 + 4)
                        if (raf.readInt() != 1) return@use null
                        chunkOffset = raf.readInt().toLong() and 0xFFFFFFFFL
                    }
                    "mdat" -> {
                        if (!sawFtyp || !sawMoov || at + size != length || chunkOffset != at + 8) return@use null
                        return@use samples
                    }
                }
                at += size
            }
            null
        }
    } catch (e: IOException) {
        null
    }

    // The body (start, end) of the box at the end of `path` under the boxes
    // between `from` and `to`.
    private fun findBox(raf: RandomAccessFile, from: Long, to: Long, path: List<String>): Pair<Long, Long>? {
        var range = from to to
        for (name in path) {
            val found = findChild(raf, range.first, range.second, name) ?: return null
            range = found.first + 8 to found.second
        }
        return range
    }

    // The box named `name` among the boxes between `from` and `to`: its
    // start (at its size) and its end.
    private fun findChild(raf: RandomAccessFile, from: Long, to: Long, name: String): Pair<Long, Long>? {
        var at = from
        while (at + 8 <= to) {
            raf.seek(at)
            val size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also(raf::readFully).toString(Charsets.US_ASCII)
            if (size < 8 || at + size > to) return null
            if (type == name) return at to at + size
            at += size
        }
        return null
    }
}

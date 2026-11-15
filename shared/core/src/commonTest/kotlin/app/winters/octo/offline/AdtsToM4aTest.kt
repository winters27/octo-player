package app.winters.octo.offline

import java.io.DataInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ADTS made in the test: AAC LC (profile 1), 44.1 kHz (index 4), stereo,
// frames with payloads of their own sizes and bytes.
class AdtsToM4aTest {
    private val dir = Files.createTempDirectory("adts").toFile()

    @AfterTest
    fun clean() {
        dir.deleteRecursively()
    }

    private fun header(payload: Int, profile: Int = 1, rateIndex: Int = 4, channels: Int = 2): ByteArray {
        val length = payload + 7
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            ((profile shl 6) or (rateIndex shl 2) or (channels shr 2)).toByte(),
            (((channels and 0x3) shl 6) or (length shr 11)).toByte(),
            ((length shr 3) and 0xFF).toByte(),
            (((length and 0x7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    private fun payload(i: Int, size: Int) = ByteArray(size) { (i * 31 + it).toByte() }

    private fun adts(sizes: List<Int>): Pair<File, ByteArray> {
        val file = File(dir, "in.aac")
        val sound = java.io.ByteArrayOutputStream()
        file.outputStream().use { out ->
            sizes.forEachIndexed { i, size ->
                out.write(header(size))
                val bytes = payload(i, size)
                out.write(bytes)
                sound.write(bytes)
            }
        }
        return file to sound.toByteArray()
    }

    // The boxes at the top of a file, in order, with their sizes.
    private fun topBoxes(file: File): List<Pair<String, Long>> = DataInputStream(file.inputStream().buffered()).use { input ->
        val boxes = ArrayList<Pair<String, Long>>()
        var left = file.length()
        while (left >= 8) {
            val size = input.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
            boxes += type to size
            input.skipNBytes(size - 8)
            left -= size
        }
        boxes
    }

    @Test
    fun everyFrameIsCopiedAndTheIndexComesFirst() {
        val sizes = listOf(300, 280, 310, 120, 400)
        val (input, sound) = adts(sizes)
        val output = File(dir, "out.m4a")
        assertTrue(AdtsToM4a.rewrap(input, output))
        assertEquals(listOf("ftyp", "moov", "mdat"), topBoxes(output).map { it.first })
        assertEquals(sizes.size, AdtsToM4a.samplesIn(output))
        val bytes = output.readBytes()
        assertContentEquals(sound, bytes.copyOfRange(bytes.size - sound.size, bytes.size))
        // The song's length is its frames: 5 frames of 1024 samples at 44.1 kHz.
        val text = bytes.toString(Charsets.ISO_8859_1)
        val mdhd = text.indexOf("mdhd")
        val duration = DataInputStream(bytes.inputStream(mdhd + 4 + 16, 4)).readInt()
        assertEquals(sizes.size * 1024, duration)
        val timescale = DataInputStream(bytes.inputStream(mdhd + 4 + 12, 4)).readInt()
        assertEquals(44100, timescale)
    }

    @Test
    fun theConfigNamesTheProfileRateAndChannels() {
        val (input, _) = adts(listOf(200, 200))
        val output = File(dir, "out.m4a")
        assertTrue(AdtsToM4a.rewrap(input, output))
        val bytes = output.readBytes()
        val at = bytes.toString(Charsets.ISO_8859_1).indexOf("esds")
        // DecoderSpecificInfo tag 5, length 2, then the AudioSpecificConfig
        // of AAC LC (object type 2), 44.1 kHz (index 4), two channels.
        val tag = (at + 4 + 4 until bytes.size).first { bytes[it] == 0x05.toByte() && bytes[it + 1] == 0x02.toByte() }
        assertEquals(0x12.toByte(), bytes[tag + 2])
        assertEquals(0x10.toByte(), bytes[tag + 3])
    }

    @Test
    fun aCutLastFrameIsLeftOutAndPaddingEndsTheStream() {
        val (input, _) = adts(listOf(200, 200, 200))
        // A fourth frame that says 300 bytes but has only 50.
        input.appendBytes(header(300) + ByteArray(50) { 7 })
        val output = File(dir, "out.m4a")
        assertTrue(AdtsToM4a.rewrap(input, output))
        assertEquals(3, AdtsToM4a.samplesIn(output))

        val (padded, _) = adts(listOf(200, 200))
        padded.appendBytes(ByteArray(4096))
        val second = File(dir, "out2.m4a")
        assertTrue(AdtsToM4a.rewrap(padded, second))
        assertEquals(2, AdtsToM4a.samplesIn(second))
    }

    @Test
    fun whatIsNotAdtsIsRefused() {
        val notAdts = File(dir, "x.aac").apply { writeBytes("<!DOCTYPE html>".toByteArray()) }
        val output = File(dir, "out.m4a")
        assertFalse(AdtsToM4a.rewrap(notAdts, output))
        assertFalse(output.exists())

        // Junk after good frames is not padding.
        val (junk, _) = adts(listOf(200, 200))
        junk.appendBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertFalse(AdtsToM4a.rewrap(junk, output))

        // Frames of mixed settings are not one stream.
        val mixed = File(dir, "mixed.aac")
        mixed.writeBytes(header(100) + ByteArray(100) + header(100, rateIndex = 3) + ByteArray(100))
        assertFalse(AdtsToM4a.rewrap(mixed, output))
    }

    @Test
    fun aDamagedM4aDoesNotReadBack() {
        val (input, _) = adts(listOf(200, 200, 200))
        val output = File(dir, "out.m4a")
        assertTrue(AdtsToM4a.rewrap(input, output))
        val cut = File(dir, "cut.m4a").apply { writeBytes(output.readBytes().copyOf(output.length().toInt() - 10)) }
        assertNull(AdtsToM4a.samplesIn(cut))
    }
}

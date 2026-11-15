package app.winters.octo.offline

import app.winters.octo.playback.DownloadFormat
import app.winters.octo.playback.StreamRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DownloadArrivalTest {
    private val dir = Files.createTempDirectory("arrival").toFile()

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private val oggOpus: ByteArray = "OggS".toByteArray() + bytes(0, 2) + ByteArray(20) + bytes(1, 19) + "OpusHead".toByteArray() + ByteArray(11)
    private val adts = bytes(0xFF, 0xF1, 0x50, 0x80, 0x10, 0x1F, 0xFC)
    private val mp3 = bytes(0xFF, 0xFB, 0x90, 0x64)
    private val flac = "fLaC".toByteArray() + ByteArray(8)
    private val raw = StreamRequest(null)
    private val opus = StreamRequest(128, DownloadFormat.Opus, estimate = false)
    private val aac = StreamRequest(128, DownloadFormat.Aac, estimate = false)

    @Test
    fun aSmallerDownloadIsNamedByItsBytes() {
        assertEquals(Arrival("audio/opus", madeSmaller = true, rewrap = false), arrivalOf(oggOpus, oggOpus.size, "audio/ogg", "audio/flac", opus))
        // A server that sent its file as it is: named by the file's bytes.
        assertEquals(Arrival("audio/flac", madeSmaller = true, rewrap = false), arrivalOf(flac, flac.size, "audio/flac", "audio/flac", opus))
        // AAC is put into an M4A.
        assertEquals(Arrival("audio/mp4", madeSmaller = true, rewrap = true), arrivalOf(adts, adts.size, "audio/aac", "audio/flac", aac))
    }

    @Test
    fun aFileAskedAsItIsKeepsItsListedTypeUnlessItIsAnotherKind() {
        assertEquals(Arrival("audio/flac", madeSmaller = false, rewrap = false), arrivalOf(flac, flac.size, "audio/flac", "audio/flac", raw))
        assertEquals(Arrival("audio/ogg", madeSmaller = false, rewrap = false), arrivalOf(oggOpus, oggOpus.size, "audio/ogg", "audio/ogg", raw))
        // A family limit had the server send an MP3 for a listed FLAC.
        assertEquals(Arrival("audio/mpeg", madeSmaller = true, rewrap = false), arrivalOf(mp3, mp3.size, "audio/mpeg", "audio/flac", raw))
        assertEquals(Arrival("audio/opus", madeSmaller = true, rewrap = false), arrivalOf(oggOpus, oggOpus.size, "audio/ogg", "audio/flac", raw))
        // A raw ADTS library file is put into an M4A too, so it has its real length; it is not a smaller copy.
        assertEquals(Arrival("audio/mp4", madeSmaller = false, rewrap = true), arrivalOf(adts, adts.size, "audio/aac", "audio/aac", raw))
    }

    @Test
    fun theNameOfASmallerDownloadCarriesTheMarkAndItsType() {
        val arrival = arrivalOf(oggOpus, oggOpus.size, "audio/ogg", "audio/flac", opus)
        val name = downloadFileName("Drake", "Hotline Bling", "server:a", "tr-1", arrival.label, arrival.madeSmaller)
        assertTrue(name, name.matches(Regex("Drake - Hotline Bling \\[[0-9a-f]{8}~s]\\.opus")))
        assertTrue(isMadeSmallerName(name))
        val m4a = downloadFileName("Drake", "Hotline Bling", "server:a", "tr-1", "audio/mp4", madeSmaller = true)
        assertTrue(m4a.endsWith("~s].m4a"))
    }

    @Test
    fun aFinishedArrivalIsRenamedAndItsPartGoes() = runBlocking {
        val part = File(dir, "a.opus.part").apply { writeBytes(oggOpus) }
        val target = File(dir, "a.opus")
        assertTrue(finishArrival(part, target, rewrap = false) { true })
        assertTrue(target.exists())
        assertFalse(part.exists())
    }

    // A download removed while its file was being finished leaves nothing.
    @Test
    fun aRemovalDuringTheRewrapLeavesNoFile() = runBlocking {
        val frames = (0 until 3).fold(ByteArray(0)) { all, _ -> all + adtsFrame(200) }
        val part = File(dir, "a.aac.part").apply { writeBytes(frames) }
        val target = File(dir, "a.m4a")
        assertFalse(finishArrival(part, target, rewrap = true) { false })
        assertFalse(target.exists())
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun aRewrappedArrivalIsAnM4a() = runBlocking {
        val frames = (0 until 3).fold(ByteArray(0)) { all, _ -> all + adtsFrame(200) }
        val part = File(dir, "a.aac.part").apply { writeBytes(frames) }
        val target = File(dir, "a.m4a")
        assertTrue(finishArrival(part, target, rewrap = true) { true })
        assertEquals(3, AdtsToM4a.samplesIn(target))
        assertEquals(listOf("a.m4a"), dir.list()!!.toList())
    }

    @Test
    fun onlyTopLevelPartsAreLeftovers() {
        File(dir, "a.opus.part").writeText("x")
        File(dir, "b.flac").writeText("x")
        File(dir, ".nomedia").writeText("")
        File(dir, ".loops").mkdirs()
        File(dir, ".loops/c.part").writeText("x")
        assertEquals(listOf("a.opus.part"), leftoverParts(dir).map { it.name })
    }

    // One ADTS frame of AAC LC, 44.1 kHz, stereo, with `payload` bytes.
    private fun adtsFrame(payload: Int): ByteArray {
        val length = payload + 7
        return bytes(0xFF, 0xF1, 0x50, 0x80 or (length shr 11), (length shr 3) and 0xFF, ((length and 0x7) shl 5) or 0x1F, 0xFC) + ByteArray(payload) { 3 }
    }
}

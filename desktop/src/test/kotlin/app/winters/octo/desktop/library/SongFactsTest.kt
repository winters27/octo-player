package app.winters.octo.desktop.library

import app.winters.octo.desktop.ui.playingNow
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SongReplayGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

class SongFactsTest {
    private val flac = Song(
        "s1", "Karma Police", artist = "Radiohead", album = "OK Computer", suffix = "flac", bitDepth = 24, samplingRate = 96_000,
        size = 48_000_000, playCount = 1234, bpm = 75, explicitStatus = "clean", isrc = listOf("GBAYE9700001"),
        replayGain = SongReplayGain(trackGain = -7.4f, trackPeak = 0.998f),
    )

    private fun facts(song: Song) = songFacts(song, "Your server (Octo)", ZoneOffset.UTC, Locale.UK).associate { it.label to it.value }

    @Test
    fun whatIsKnownIsListedInThePhonesWords() {
        val f = facts(flac)
        assertEquals("FLAC, lossless", f["Format"])
        assertEquals("96 kHz", f["Sample rate"])
        assertEquals("24-bit", f["Bit depth"])
        assertEquals("45.8 MB", f["File size"])
        assertEquals("1,234", f["Plays"])
        assertEquals("Clean", f["Lyrics"])
        assertEquals("-7.4 dB", f["Track gain"])
        assertEquals("GBAYE9700001", f["ISRC"])
        assertEquals("Your server (Octo)", f["Source"])
    }

    @Test
    fun whatIsNotKnownIsLeftOut() {
        val f = facts(Song("s2", "Bare"))
        assertFalse("Format" in f)
        assertFalse("Rating" in f)
        assertEquals("0", f["Plays"])
    }

    @Test
    fun aFileOnThisComputerSaysSo() {
        assertEquals("A file on this computer", facts(Song("file:abc", "Local"))["Source"])
    }

    @Test
    fun theFormatLineIsShort() {
        assertEquals("FLAC 24/96", formatLine(flac))
        assertEquals("FLAC 16/44.1", formatLine(Song("a", suffix = "flac", samplingRate = 44_100)))
        assertEquals("MP3 320", formatLine(Song("b", suffix = "mp3", bitRate = 320)))
        assertEquals("AAC", formatLine(Song("c", suffix = "m4a")))
        assertNull(formatLine(Song("d")))
    }

    @Test
    fun playingNowNamesTheFormatAndTheDevice() {
        val line = playingNow(flac, "Speakers")
        assertTrue(line, line.startsWith("Playing FLAC, lossless, 24-bit, 96 kHz on Speakers"))
        assertEquals("Playing.", playingNow(Song("x"), null))
    }
}

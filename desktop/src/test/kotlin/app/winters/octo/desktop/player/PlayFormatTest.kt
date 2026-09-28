package app.winters.octo.desktop.player

import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayFormatTest {
    private val cd = SongFormat("flac", lossless = true, sampleRate = 44_100, bits = 16, channels = 2)
    private val hires = SongFormat("flac", lossless = true, sampleRate = 96_000, bits = 24, channels = 2)
    private val mp3 = SongFormat("mp3", lossless = false, sampleRate = 44_100, channels = 2, bitRate = 320)
    private val speakers = DeviceFormat(48_000, 2, 32, float = true)

    @Test
    fun ratesReadInKilohertz() {
        assertEquals("44.1", kilohertz(44_100))
        assertEquals("48", kilohertz(48_000))
        assertEquals("88.2", kilohertz(88_200))
        assertEquals("352.8", kilohertz(352_800))
        assertEquals("11.025", kilohertz(11_025))
    }

    @Test
    fun thePlayerBarLabelIsShort() {
        assertEquals("FLAC 16/44.1", formatLabel(PlayFormat(cd, speakers)))
        assertEquals("FLAC 24/96", formatLabel(PlayFormat(hires, null)))
        assertEquals("MP3 320", formatLabel(PlayFormat(mp3, speakers)))
        assertEquals("Opus", formatLabel(PlayFormat(SongFormat("opus", lossless = false), null)))
        assertEquals("ALAC 48", formatLabel(PlayFormat(SongFormat("alac", lossless = true, sampleRate = 48_000), null)))
        assertNull(formatLabel(PlayFormat(null, speakers)))
        assertNull(formatLabel(null))
    }

    @Test
    fun theSongReadsInWords() {
        assertEquals("Lossless FLAC, 24-bit, 96 kHz, stereo", songFormatWords(PlayFormat(hires, null)))
        assertEquals("MP3, 320 kbps, 44.1 kHz, stereo", songFormatWords(PlayFormat(mp3, null)))
        assertEquals("Lossless WAV, 16-bit, 48 kHz, mono", songFormatWords(PlayFormat(SongFormat("wav", true, 48_000, 16, 1), null)))
    }

    @Test
    fun theOutputSaysWhenTheSongIsResampled() {
        val format = PlayFormat(cd, speakers)
        assertTrue(format.resampled)
        assertEquals(
            "Playing at 48 kHz, 32-bit float on Speakers (Focusrite), resampled from 44.1 kHz",
            outputSentence(format, "Speakers (Focusrite)"),
        )
        val same = PlayFormat(cd.copy(sampleRate = 48_000), DeviceFormat(48_000, 2, 24, float = false))
        assertFalse(same.resampled)
        assertEquals("Playing at 48 kHz, 24-bit on USB DAC", outputSentence(same, "USB DAC"))
        assertEquals("Playing at 44.1 kHz, 16-bit, mono", outputSentence(PlayFormat(null, DeviceFormat(44_100, 1, 16, false)), null))
        // A song whose rate is not known is never called resampled.
        assertFalse(PlayFormat(SongFormat("mp3", false), speakers).resampled)
        assertNull(outputSentence(PlayFormat(cd, null), "Speakers"))
    }

    @Test
    fun theLibraryGivesAFormatBeforeTheSongStarts() {
        val flac = Song("a", suffix = "FLAC", samplingRate = 44_100, bitDepth = 16, bitRate = 900)
        assertEquals(SongFormat("flac", true, 44_100, 16, null, 900), libraryFormat(flac))
        assertEquals("alac", libraryFormat(Song("b", suffix = "m4a", bitDepth = 24))?.codec)
        assertEquals("aac", libraryFormat(Song("c", suffix = "m4a", bitRate = 256))?.codec)
        assertEquals("MP3 320", formatLabel(PlayFormat(libraryFormat(Song("d", suffix = "mp3", bitRate = 320)), null)))
        assertNull(libraryFormat(Song("e")))
    }
}

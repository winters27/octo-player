package app.winters.octo.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualityTest {
    @Test
    fun cdQualityFlacIsLossless() {
        val quality = audioQuality("audio/flac", "audio/flac", C.ENCODING_PCM_16BIT, 44_100, Format.NO_VALUE)
        assertEquals(AudioQuality("Lossless", "FLAC 16/44.1"), quality)
    }

    @Test
    fun above48KilohertzIsHiRes() {
        val quality = audioQuality("audio/flac", "audio/flac", C.ENCODING_PCM_24BIT, 96_000, Format.NO_VALUE)
        assertEquals(AudioQuality("Hi-Res Lossless", "FLAC 24/96"), quality)
    }

    @Test
    fun twentyFourBitAt48IsStillLossless() {
        val quality = audioQuality("audio/flac", "audio/flac", C.ENCODING_PCM_24BIT, 48_000, Format.NO_VALUE)
        assertEquals(AudioQuality("Lossless", "FLAC 24/48"), quality)
    }

    @Test
    fun lossyShowsItsBitrate() {
        val quality = audioQuality("audio/mpeg", "audio/mpeg", Format.NO_VALUE, 44_100, 320_000)
        assertEquals(AudioQuality("MP3", "320 kbps"), quality)
    }

    @Test
    fun lossyWithoutBitrateShowsSampleRate() {
        val quality = audioQuality("audio/opus", "audio/ogg", Format.NO_VALUE, 48_000, Format.NO_VALUE)
        assertEquals(AudioQuality("Opus", "48 kHz"), quality)
    }

    @Test
    fun beforeTheDecoderReportsTheFileTypeIsUsed() {
        val quality = audioQuality(null, "audio/flac", Format.NO_VALUE, Format.NO_VALUE, Format.NO_VALUE)
        assertEquals(AudioQuality("Lossless", "FLAC"), quality)
    }

    @Test
    fun unknownTypeHasNoBadge() {
        assertNull(audioQuality(null, "audio/x-unknown", Format.NO_VALUE, Format.NO_VALUE, Format.NO_VALUE))
    }
}

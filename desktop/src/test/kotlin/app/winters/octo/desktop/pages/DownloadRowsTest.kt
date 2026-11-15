package app.winters.octo.desktop.pages

import app.winters.octo.playback.DownloadFormat
import app.winters.octo.subsonic.StreamQuality
import org.junit.Assert.assertEquals
import org.junit.Test

// The words of the Download quality and Download format rows.
class DownloadRowsTest {
    // Red when the quality caption names MP3, or no format, whatever is chosen.
    @Test
    fun theQualityCaptionNamesTheFormat() {
        assertEquals(
            "For songs fetched from now on, as 160 kbps Opus at most. Smaller files fit more songs.",
            downloadQualityCaption(StreamQuality.Standard, DownloadFormat.Opus),
        )
        assertEquals(
            "For songs fetched from now on, as 96 kbps MP3 at most. Smaller files fit more songs.",
            downloadQualityCaption(StreamQuality.DataSaver, DownloadFormat.Mp3),
        )
        assertEquals(
            "For songs fetched from now on. Smaller files fit more songs.",
            downloadQualityCaption(StreamQuality.Original, DownloadFormat.Aac),
        )
    }

    @Test
    fun theFormatCaptionSaysWhatItIsForAndWhatTheChoiceIsLike() {
        // The last two words are joined by a no-break space, so a wrap never
        // leaves one alone.
        assertEquals("What smaller downloads are saved as. Best sound for its size.", downloadFormatCaption(DownloadFormat.Opus))
        assertEquals("What smaller downloads are saved as. Close to Opus, opens almost anywhere.", downloadFormatCaption(DownloadFormat.Aac))
    }
}

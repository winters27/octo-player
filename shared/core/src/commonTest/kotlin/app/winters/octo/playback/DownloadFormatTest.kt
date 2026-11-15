package app.winters.octo.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadFormatTest {
    @Test
    fun anUnknownOrMissingNameIsOpus() {
        assertEquals(DownloadFormat.Opus, DownloadFormat.named(null))
        assertEquals(DownloadFormat.Opus, DownloadFormat.named("Flac"))
        assertEquals(DownloadFormat.Aac, DownloadFormat.named("Aac"))
        assertEquals(DownloadFormat.Mp3, DownloadFormat.named("Mp3"))
        assertEquals(listOf("Opus", "Aac", "Mp3"), DownloadFormat.entries.map { it.name })
    }

    @Test
    fun theQualityReadsInTheFormat() {
        assertEquals("128 kbps Opus", downloadQualityLabel(128, DownloadFormat.Opus))
        assertEquals("256 kbps AAC", downloadQualityLabel(256, DownloadFormat.Aac))
        assertEquals("320 kbps MP3", downloadQualityLabel(320, DownloadFormat.Mp3))
        assertEquals("Original", downloadQualityLabel(null, DownloadFormat.Opus))
    }

    @Test
    fun theWordsAreTheOnesPeopleRead() {
        assertEquals("Best sound for its size", DownloadFormat.Opus.detail)
        assertEquals("Close to Opus, opens almost anywhere", DownloadFormat.Aac.detail)
        assertEquals("Opens anywhere, a little worse for its size", DownloadFormat.Mp3.detail)
        assertEquals("What smaller downloads are saved as", DOWNLOAD_FORMAT_HELP)
    }
}

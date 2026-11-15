package app.winters.octo.offline

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.winters.octo.playback.DownloadFormat
import app.winters.octo.playback.StreamQuality
import org.junit.Assert.assertEquals
import org.junit.Test

// A phone updated from before Download format existed has Opus as its
// format, its own download quality as it was.
class OfflinePrefsReadTest {
    @Test
    fun nothingStoredIsOpus() {
        assertEquals(DownloadFormat.Opus, offlinePrefsOf(preferencesOf()).downloadFormat)
        val before = offlinePrefsOf(preferencesOf(stringPreferencesKey("download_quality") to "Kbps128"))
        assertEquals(DownloadFormat.Opus, before.downloadFormat)
        assertEquals(StreamQuality.Kbps128, before.downloadQuality)
    }

    @Test
    fun aPickIsReadBack() {
        assertEquals(DownloadFormat.Aac, offlinePrefsOf(preferencesOf(stringPreferencesKey("download_format") to "Aac")).downloadFormat)
        assertEquals(DownloadFormat.Mp3, offlinePrefsOf(preferencesOf(stringPreferencesKey("download_format") to "Mp3")).downloadFormat)
        assertEquals(DownloadFormat.Opus, offlinePrefsOf(preferencesOf(stringPreferencesKey("download_format") to "Flac")).downloadFormat)
    }
}

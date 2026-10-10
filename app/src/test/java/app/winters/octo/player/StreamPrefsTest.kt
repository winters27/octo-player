package app.winters.octo.player

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import app.winters.octo.playback.StreamQuality
import org.junit.Assert.assertEquals
import org.junit.Test

// The streaming quality choices as they are stored and read back.
class StreamPrefsTest {
    @Test
    fun nothingStoredMeansOriginalOnBoth() {
        val prefs = streamPrefsOf(emptyPreferences())
        assertEquals(StreamQuality.Original, prefs.wifi)
        assertEquals(StreamQuality.Original, prefs.mobile)
    }

    @Test
    fun aPickIsReadBack() {
        val stored = mutablePreferencesOf(STREAM_WIFI to StreamQuality.Kbps320.name, STREAM_MOBILE to StreamQuality.Kbps128.name)
        val prefs = streamPrefsOf(stored)
        assertEquals(StreamQuality.Kbps320, prefs.wifi)
        assertEquals(StreamQuality.Kbps128, prefs.mobile)
        // A stored Original stays Original.
        assertEquals(StreamQuality.Original, streamPrefsOf(mutablePreferencesOf(STREAM_MOBILE to "Original")).mobile)
    }
}

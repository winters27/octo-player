package app.winters.octo.playback

import app.winters.octo.player.StreamPrefs
import app.winters.octo.subsonic.DeviceQualityMode
import app.winters.octo.ui.family.appPicksQuality
import org.junit.Assert.assertEquals
import org.junit.Test

// Which quality a stream asks for: the phone's own on Wi-Fi or mobile data,
// unless a family account leaves it to the server.
class StreamQualityForTest {
    private val prefs = StreamPrefs(wifi = StreamQuality.Kbps320, mobile = StreamQuality.Kbps128)

    @Test
    fun withoutAFamilyTheConnectionDecides() {
        val picks = appPicksQuality(familyOn = false, mode = null)
        assertEquals(StreamQuality.Kbps320, streamQualityFor(picks, onMobile = false, prefs = prefs))
        assertEquals(StreamQuality.Kbps128, streamQualityFor(picks, onMobile = true, prefs = prefs))
    }

    @Test
    fun aPhoneLeftToTheAppUsesItsOwnSettings() {
        val picks = appPicksQuality(familyOn = true, mode = DeviceQualityMode.App)
        assertEquals(StreamQuality.Kbps320, streamQualityFor(picks, onMobile = false, prefs = prefs))
        assertEquals(StreamQuality.Kbps128, streamQualityFor(picks, onMobile = true, prefs = prefs))
    }

    @Test
    fun aPhoneThatFollowsItsAccountAsksForTheFileAsItIs() {
        val picks = appPicksQuality(familyOn = true, mode = DeviceQualityMode.Account)
        assertEquals(StreamQuality.Original, streamQualityFor(picks, onMobile = false, prefs = prefs))
        assertEquals(StreamQuality.Original, streamQualityFor(picks, onMobile = true, prefs = prefs))
        // Not read yet: the server's default, the account.
        assertEquals(StreamQuality.Original, streamQualityFor(appPicksQuality(true, null), onMobile = true, prefs = prefs))
    }
}

// Original is where every phone starts, and it asks for the file untouched.
class OriginalStreamTest {
    @Test
    fun originalIsTheDefaultOnWifiAndMobileData() {
        val prefs = StreamPrefs()
        assertEquals(StreamQuality.Original, prefs.wifi)
        assertEquals(StreamQuality.Original, prefs.mobile)
        assertEquals(StreamQuality.Original, StreamQuality.entries.first())
    }

    @Test
    fun originalSendsNoTranscodeAsk() {
        assertEquals(mapOf("format" to "raw"), streamRequest("audio/flac", 900_000, StreamQuality.Original).params)
        assertEquals("audio/flac", streamRequest("audio/flac", 900_000, StreamQuality.Original).mimeType("audio/flac"))
    }
}

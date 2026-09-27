package app.winters.octo.discovery

import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchingTest {
    private fun song(id: String, title: String, artist: String, seconds: Int = 180, suffix: String = "m4a") =
        Song(id = id, title = title, artist = artist, album = title, albumId = "alb$id", duration = seconds, suffix = suffix, bitRate = 128, coverArt = id)

    @Test
    fun aGuessedLengthCountsAsUnknown() {
        assertEquals(0, knownLengthMs(song("x", "A", "B", seconds = 180)))
        assertEquals(0, knownLengthMs(song("x", "A", "B", seconds = 0)))
        assertEquals(215_000, knownLengthMs(song("x", "A", "B", seconds = 215)))
    }

    @Test
    fun titleKeysIncludeTheBareTitle() {
        assertEquals(listOf("one dance (feat. wizkid)", "one dance"), titleKeys("One Dance (feat. Wizkid)"))
    }

    @Test
    fun titleKeysIncludeTheTitleAPersonWouldSay() {
        assertEquals(listOf("01 - teardrop - remastered 2011", "teardrop"), titleKeys("01 - Teardrop - Remastered 2011"))
    }

    @Test
    fun lengthsOnlyCountWhenBothAreKnown() {
        assertFalse(sameSong("Around the World", "Daft Punk", 238_000, "Around the World", "Daft Punk", 429_000))
        assertTrue(sameSong("Around the World", "Daft Punk", 0, "Around the World", "Daft Punk", 429_000))
        assertFalse(sameSong("Nightcall - Live", "Kavinsky", 0, "Nightcall", "Kavinsky", 179_000))
    }
}

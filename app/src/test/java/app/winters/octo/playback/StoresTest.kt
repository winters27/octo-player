package app.winters.octo.playback

import app.winters.octo.catalog.relinkKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoresTest {
    @Test
    fun halfTheSongCountsAsAPlay() {
        assertTrue(countsAsPlay(playedMs = 90_000, durationMs = 180_000))
        assertFalse(countsAsPlay(playedMs = 89_000, durationMs = 180_000))
    }

    @Test
    fun fourMinutesCountsForLongSongs() {
        assertTrue(countsAsPlay(playedMs = 240_000, durationMs = 1_200_000))
        assertFalse(countsAsPlay(playedMs = 239_000, durationMs = 1_200_000))
    }

    @Test
    fun shortClipsNeverCount() {
        assertFalse(countsAsPlay(playedMs = 29_000, durationMs = 29_000))
    }

    @Test
    fun relinkKeyIgnoresCaseAndAccentsButNotLength() {
        val a = relinkKey("Beyoncé", "Lemonade", 1, 3, "Hold Up", 221_400)
        val b = relinkKey("beyonce", "lemonade", 1, 3, "hold up", 221_900)
        assertEquals(a, b)
        assertNotEquals(a, relinkKey("Beyoncé", "Lemonade", 1, 3, "Hold Up", 230_000))
    }
}

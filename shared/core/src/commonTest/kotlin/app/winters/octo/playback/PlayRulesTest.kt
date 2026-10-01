package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayRulesTest {
    @Test
    fun whatIsLeftIsHalfTheSongOrFourMinutes() {
        assertEquals(100_000L, msLeftToCount(0, 200_000))
        assertEquals(40_000L, msLeftToCount(60_000, 200_000))
        assertEquals(0L, msLeftToCount(150_000, 200_000))
        assertEquals(240_000L, msLeftToCount(0, 3_600_000))
    }

    @Test
    fun clipsAndUnknownLengthsNeverCount() {
        assertNull(msLeftToCount(0, 20_000))
        assertNull(msLeftToCount(0, 0))
    }
}

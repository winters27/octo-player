package app.winters.octo.playback

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayTest {
    @Test
    fun itIsTimeOnlyNearTheEndOfTheLastSongWithRepeatOff() {
        val off = Player.REPEAT_MODE_OFF
        assertTrue(autoplayDue(true, off, hasNext = false, remainingMs = 15_000, leadMs = 20_000))
        assertFalse(autoplayDue(true, off, hasNext = false, remainingMs = 90_000, leadMs = 20_000))
        assertFalse(autoplayDue(true, off, hasNext = true, remainingMs = 15_000, leadMs = 20_000))
        assertFalse(autoplayDue(true, Player.REPEAT_MODE_ALL, hasNext = false, remainingMs = 15_000, leadMs = 20_000))
        assertFalse(autoplayDue(true, Player.REPEAT_MODE_ONE, hasNext = false, remainingMs = 15_000, leadMs = 20_000))
        // Switched off: never.
        assertFalse(autoplayDue(false, off, hasNext = false, remainingMs = 0, leadMs = 20_000))
    }
}

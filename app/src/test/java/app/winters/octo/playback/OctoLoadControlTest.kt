package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// When a stream has enough to start, by the "Start playing after" setting.
class OctoLoadControlTest {
    @Test
    fun aStreamStartsAtTheSetAmountAndNotBefore() {
        assertFalse(hasEnoughToStart(2_499_000, 1f, rebuffering = false, startAfterMs = 2_500))
        assertTrue(hasEnoughToStart(2_500_000, 1f, rebuffering = false, startAfterMs = 2_500))
        assertTrue(hasEnoughToStart(250_000, 1f, rebuffering = false, startAfterMs = StartAfter.Instant.ms))
        assertFalse(hasEnoughToStart(250_000, 1f, rebuffering = false, startAfterMs = StartAfter.Short.ms))
    }

    // Faster playing uses up what is buffered sooner.
    @Test
    fun theWaitIsInPlayingTime() {
        assertFalse(hasEnoughToStart(1_500_000, 2f, rebuffering = false, startAfterMs = 1_000))
        assertTrue(hasEnoughToStart(2_000_000, 2f, rebuffering = false, startAfterMs = 1_000))
    }

    // After running dry it waits at least as long as Media3 does, so it
    // does not stutter again at once; a longer setting still counts.
    @Test
    fun afterRunningDryItWaitsAtLeastTwoSeconds() {
        assertFalse(hasEnoughToStart(1_000_000, 1f, rebuffering = true, startAfterMs = 250))
        assertTrue(hasEnoughToStart(2_000_000, 1f, rebuffering = true, startAfterMs = 250))
        assertFalse(hasEnoughToStart(4_000_000, 1f, rebuffering = true, startAfterMs = 5_000))
    }

    @Test
    fun filesOnThePhoneKeepMedia3sOwnRule() {
        assertTrue(isLocalScheme("file"))
        assertTrue(isLocalScheme("content"))
        assertTrue(isLocalScheme("FILE"))
        assertFalse(isLocalScheme("https"))
        assertFalse(isLocalScheme(null))
    }

    // The default is what Media3 waits, so nothing changes until it is set.
    @Test
    fun theDefaultIsOneSecond() {
        assertEquals(StartAfter.Short, app.winters.octo.player.StreamPrefs().startAfter)
        assertEquals(1_000, OctoLoadControl().startAfterMs)
    }
}

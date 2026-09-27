package app.winters.octo.lyrics.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricClockTest {
    private val frame = 1_000_000_000L / 60

    @Test
    fun projectsSmoothlyBetweenCoarseReportsAtThePlaybackSpeed() {
        val clock = LyricClock()
        // The player reports only every third frame, at 1.5x.
        var reported = 10.0
        for (n in 0..60) {
            val elapsed = n / 60.0
            if (n % 3 == 0) reported = 10.0 + 1.5 * elapsed
            val time = clock.frame(n * frame, reported, rate = 1.5, playing = true)
            assertEquals(10.0 + 1.5 * elapsed, time, 1e-6)
            assertFalse(clock.seeked)
        }
    }

    @Test
    fun snapsToThePlayerOnlyWhenItDriftsMoreThanAFewFrames() {
        val clock = LyricClock()
        clock.frame(0, 10.0, 1.0, true)
        // 0.1 s off: the projection stands.
        val kept = clock.frame(frame, 10.0 + 1.0 / 60 + 0.1, 1.0, true)
        assertEquals(10.0 + 1.0 / 60, kept, 1e-9)
        // 0.2 s off: the player wins, and that is not a seek.
        val snapped = clock.frame(2 * frame, 10.0 + 2.0 / 60 + 0.2, 1.0, true)
        assertEquals(10.0 + 2.0 / 60 + 0.2, snapped, 1e-9)
        assertFalse(clock.seeked)
    }

    @Test
    fun aJumpOfMoreThanSixTenthsIsASeek() {
        val clock = LyricClock()
        clock.frame(0, 10.0, 1.0, true)
        clock.frame(frame, 10.5 + 1.0 / 60, 1.0, true)
        assertFalse(clock.seeked)
        clock.frame(2 * frame, 11.2 + 2.0 / 60, 1.0, true)
        assertTrue(clock.seeked)
        clock.frame(3 * frame, 11.2 + 3.0 / 60, 1.0, true)
        assertFalse(clock.seeked)
    }

    @Test
    fun sixStillFramesMeanPausedWhateverThePlayerSays() {
        val clock = LyricClock()
        clock.frame(0, 20.0, 1.0, true)
        // Five frames standing still: still projecting.
        for (n in 1..5) assertTrue(clock.frame(n * frame, 20.0, 1.0, true) > 20.0)
        // The sixth: paused, on the player's own position.
        assertEquals(20.0, clock.frame(6 * frame, 20.0, 1.0, true), 0.0)
    }

    @Test
    fun pausedShowsThePlayersPositionLessTheLatency() {
        val clock = LyricClock(latency = 0.05)
        assertEquals(30.0 - 0.05, clock.frame(0, 30.0, 1.0, false), 1e-12)
        assertEquals(30.0 - 0.05, clock.frame(frame * 30, 30.0, 1.0, false), 1e-12)
    }

    @Test
    fun aTapShowsItsTargetUntilThePlayerGetsThere() {
        val clock = LyricClock()
        clock.frame(0, 10.0, 1.0, true)
        clock.holdSeek(40.0, 0)
        // The player has not moved yet: the target shows, as a seek.
        assertEquals(40.0, clock.frame(frame, 10.0 + 1.0 / 60, 1.0, true), 0.0)
        assertTrue(clock.seeked)
        assertEquals(40.0, clock.frame(2 * frame, 10.0 + 2.0 / 60, 1.0, true), 0.0)
        // Within half a second of it: the player takes over.
        val near = clock.frame(3 * frame, 39.7, 1.0, true)
        assertEquals(39.7, near, 1e-9)
        assertFalse(clock.seeked)
    }

    @Test
    fun aTapsHoldGivesUpAfterTwoAndAHalfSeconds() {
        val clock = LyricClock()
        clock.frame(0, 10.0, 1.0, false)
        clock.holdSeek(40.0, 0)
        assertEquals(40.0, clock.frame(2_400_000_000L, 10.0, 1.0, false), 0.0)
        assertEquals(10.0, clock.frame(2_600_000_000L, 10.0, 1.0, false), 0.0)
    }

    @Test
    fun theStepGivenToTheSpringsIsKeptSmall() {
        val clock = LyricClock()
        clock.frame(0, 10.0, 1.0, true)
        clock.frame(frame, 10.0, 1.0, true)
        assertEquals(1.0 / 60, clock.step, 1e-6)
        clock.frame(frame + 200_000_000L, 10.2, 1.0, true)
        assertEquals(MAX_FRAME_STEP_S, clock.step, 0.0)
    }

    @Test
    fun startingOverIsNotASeek() {
        val clock = LyricClock()
        clock.frame(0, 10.0, 1.0, true)
        clock.reset()
        clock.frame(frame, 90.0, 1.0, true)
        assertFalse(clock.seeked)
        assertEquals(0.0, clock.step, 0.0)
    }
}

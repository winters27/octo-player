package app.winters.octo.playback

import androidx.media3.common.Player
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {
    // Stands in for the player and remembers what the timer did to it.
    private class FakePlayer : SleepTarget {
        val fades = mutableListOf<Float>()
        override var sleepFade = 1f
            set(value) {
                field = value
                fades += value
            }
        override var pauseAtEndOfSong = false
        var paused = false
        var listener: Player.Listener? = null

        override fun pause() {
            paused = true
        }

        override fun addListener(listener: Player.Listener) {
            this.listener = listener
        }

        override fun removeListener(listener: Player.Listener) {
            if (this.listener === listener) this.listener = null
        }
    }

    private fun TestScope.timerWith(player: FakePlayer) =
        SleepTimer({ testScheduler.currentTime }, backgroundScope).also { it.attach(player) }

    private fun TestScope.elapse(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun fadeIsFullUntilTheLastThirtySecondsThenFallsToSilence() {
        assertEquals(1f, sleepFade(60_000), 0f)
        assertEquals(1f, sleepFade(30_000), 0f)
        assertEquals(0.25f, sleepFade(15_000), 0.0001f)
        assertEquals(0f, sleepFade(0), 0f)
    }

    @Test
    fun countsDownThenPausesAndPutsTheVolumeBack() = runTest {
        val player = FakePlayer()
        val timer = timerWith(player)

        timer.start(1)
        assertEquals(SleepState.Counting(60_000), timer.state.value)

        elapse(10_000)
        assertEquals(SleepState.Counting(50_000), timer.state.value)
        assertEquals(1f, player.sleepFade, 0f)
        assertFalse(player.paused)

        elapse(50_000)
        assertTrue(player.paused)
        assertEquals(SleepState.Off, timer.state.value)
        // Silent at the moment it pauses, then back to full.
        assertEquals(listOf(0f, 1f), player.fades.takeLast(2))
        assertEquals(1f, player.sleepFade, 0f)
    }

    @Test
    fun fadesThroughThePlayerOverTheLastThirtySeconds() = runTest {
        val player = FakePlayer()
        val timer = timerWith(player)

        timer.start(1)
        elapse(30_000)
        assertEquals(SleepState.Counting(30_000), timer.state.value)
        assertEquals(1f, player.sleepFade, 0f)

        elapse(15_000)
        assertEquals(SleepState.Counting(15_000), timer.state.value)
        assertEquals(0.25f, player.sleepFade, 0.0001f)

        elapse(14_900)
        assertTrue(player.sleepFade < 0.0001f)
        assertFalse(player.paused)
    }

    @Test
    fun cancellingMidFadeRestoresTheVolume() = runTest {
        val player = FakePlayer()
        val timer = timerWith(player)

        timer.start(1)
        elapse(45_000)
        assertEquals(0.25f, player.sleepFade, 0.0001f)

        timer.cancel()
        assertEquals(SleepState.Off, timer.state.value)
        assertEquals(1f, player.sleepFade, 0f)

        elapse(60_000)
        assertFalse(player.paused)
        assertEquals(1f, player.sleepFade, 0f)
    }

    @Test
    fun endOfSongIsClearedOnceThePlayerPausesThere() = runTest {
        val player = FakePlayer()
        val timer = timerWith(player)

        timer.startEndOfSong()
        assertEquals(SleepState.EndOfSong, timer.state.value)
        assertTrue(player.pauseAtEndOfSong)

        // A pause by hand leaves it waiting for the end of the song.
        player.listener?.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        assertTrue(player.pauseAtEndOfSong)

        player.listener?.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
        assertFalse(player.pauseAtEndOfSong)
        assertEquals(SleepState.Off, timer.state.value)
    }

    @Test
    fun endOfSongIsClearedOnCancel() = runTest {
        val player = FakePlayer()
        val timer = timerWith(player)

        timer.startEndOfSong()
        timer.cancel()
        assertFalse(player.pauseAtEndOfSong)
        assertEquals(SleepState.Off, timer.state.value)
    }

    @Test
    fun aNewTimerReplacesTheOldOne() = runTest {
        val player = FakePlayer()
        val timer = timerWith(player)

        timer.start(1)
        elapse(45_000)
        timer.start(5)
        assertEquals(SleepState.Counting(300_000), timer.state.value)
        assertEquals(1f, player.sleepFade, 0f)

        // The first timer's end passes without a pause.
        elapse(60_000)
        assertFalse(player.paused)
        assertEquals(SleepState.Counting(240_000), timer.state.value)

        timer.startEndOfSong()
        assertTrue(player.pauseAtEndOfSong)
        elapse(300_000)
        assertFalse(player.paused)
        assertEquals(SleepState.EndOfSong, timer.state.value)

        timer.start(1)
        assertFalse(player.pauseAtEndOfSong)
        assertEquals(SleepState.Counting(60_000), timer.state.value)
    }

    @Test
    fun detachingStopsTheTimerAndLetsGoOfThePlayer() = runTest {
        val player = FakePlayer()
        val timer = timerWith(player)

        timer.start(1)
        elapse(45_000)
        timer.detach()
        assertEquals(SleepState.Off, timer.state.value)
        assertEquals(1f, player.sleepFade, 0f)
        assertNull(player.listener)
    }
}

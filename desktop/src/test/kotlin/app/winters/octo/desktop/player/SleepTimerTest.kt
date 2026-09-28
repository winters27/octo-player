package app.winters.octo.desktop.player

import app.winters.octo.playback.SleepState
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {
    // Five songs of 100 seconds each.
    private val songs = (1..5).map { Song("s$it", "Song $it", duration = 100) }

    private class Setup(val player: SilentPlayer, val timer: SleepTimer)

    private fun TestScope.setUp(): Setup {
        val clock = { testScheduler.currentTime }
        val player = SilentPlayer(clock = clock, random = Random(1))
        player.setVolume(0.7f)
        val timer = SleepTimer(player, backgroundScope, clock)
        runCurrent()
        return Setup(player, timer)
    }

    // Lets time pass for the timer and the player alike.
    private fun TestScope.elapse(setup: Setup, ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
        setup.player.tick()
        runCurrent()
    }

    private fun Setup.now() = player.state.value.current?.song?.id

    @Test
    fun aCountdownFadesOverItsLastThirtySecondsThenPauses() = runTest {
        val s = setUp()
        s.player.play(songs)
        s.timer.start(1)
        assertEquals(SleepState.Counting(60_000), s.timer.state.value)
        elapse(s, 20_000)
        assertEquals(1f, s.player.state.value.fade)
        elapse(s, 25_000)
        assertEquals(SleepState.Counting(15_000), s.timer.state.value)
        assertEquals(0.25f, s.player.state.value.fade, 0.0001f)
        // The listener's volume is never touched.
        assertEquals(0.7f, s.player.state.value.volume)
        elapse(s, 15_000)
        assertEquals(SleepState.Off, s.timer.state.value)
        assertFalse(s.player.state.value.playing)
        // The volume comes back once the pause is done.
        elapse(s, 600)
        assertEquals(1f, s.player.state.value.fade)
    }

    @Test
    fun cancellingOrExtendingLiftsTheFade() = runTest {
        val s = setUp()
        s.player.play(songs)
        s.timer.start(1)
        elapse(s, 50_000)
        assertTrue(s.player.state.value.fade < 1f)
        s.timer.extend(5)
        assertEquals(1f, s.player.state.value.fade)
        assertEquals(SleepState.Counting(310_000), s.timer.state.value)
        s.timer.cancel()
        runCurrent()
        assertEquals(SleepState.Off, s.timer.state.value)
        assertTrue(s.player.state.value.playing)
        // Nothing ticks after a cancel.
        elapse(s, 400_000)
        assertEquals(1f, s.player.state.value.fade)
        assertTrue(s.player.state.value.playing)
    }

    @Test
    fun endOfSongPausesAtTheNextSongAndTurnsOff() = runTest {
        val s = setUp()
        s.player.play(songs)
        s.timer.endOfSong()
        runCurrent()
        assertTrue(s.player.state.value.stopAfterCurrent)
        elapse(s, 100_000)
        assertEquals("s2", s.now())
        assertFalse(s.player.state.value.playing)
        assertFalse(s.player.state.value.stopAfterCurrent)
        assertEquals(SleepState.Off, s.timer.state.value)
    }

    @Test
    fun stopAfterThisSongAndTheTimersEndOfSongAreOneSwitch() = runTest {
        val s = setUp()
        s.player.play(songs)
        // Turned on from the player: the timer shows it.
        s.player.setStopAfterCurrent(true)
        runCurrent()
        assertEquals(SleepState.EndOfSong, s.timer.state.value)
        // Turned off from the player: the timer goes.
        s.player.setStopAfterCurrent(false)
        runCurrent()
        assertEquals(SleepState.Off, s.timer.state.value)
        // Cancelling the timer turns the switch off.
        s.timer.endOfSong()
        runCurrent()
        s.timer.cancel()
        runCurrent()
        assertFalse(s.player.state.value.stopAfterCurrent)
        assertEquals(SleepState.Off, s.timer.state.value)
    }

    @Test
    fun aCountdownAndTheSwitchRunSideBySide() = runTest {
        val s = setUp()
        s.player.play(songs)
        s.timer.start(30)
        s.player.setStopAfterCurrent(true)
        runCurrent()
        assertEquals(SleepState.Counting(1_800_000), s.timer.state.value)
        elapse(s, 100_000)
        assertFalse(s.player.state.value.playing)
        assertFalse(s.player.state.value.stopAfterCurrent)
        assertEquals(SleepState.Counting(1_700_000), s.timer.state.value)
    }

    @Test
    fun aNewTimerReplacesEndOfSong() = runTest {
        val s = setUp()
        s.player.play(songs)
        s.timer.endOfSong()
        runCurrent()
        s.timer.start(30)
        runCurrent()
        assertFalse(s.player.state.value.stopAfterCurrent)
        assertEquals(SleepState.Counting(1_800_000), s.timer.state.value)
    }

    @Test
    fun afterSongsCountsSongsThatPlayOutNotSkips() = runTest {
        val s = setUp()
        s.player.play(songs)
        s.timer.afterSongs(3)
        runCurrent()
        assertFalse(s.player.state.value.stopAfterCurrent)
        elapse(s, 100_000)
        assertEquals(SleepState.Songs(2), s.timer.state.value)
        // A skip is not a song ending.
        s.player.next()
        runCurrent()
        assertEquals(SleepState.Songs(2), s.timer.state.value)
        elapse(s, 100_000)
        assertEquals("s4", s.now())
        assertEquals(SleepState.EndOfSong, s.timer.state.value)
        assertTrue(s.player.state.value.stopAfterCurrent)
        elapse(s, 100_000)
        assertEquals("s5", s.now())
        assertFalse(s.player.state.value.playing)
        assertEquals(SleepState.Off, s.timer.state.value)
    }

    @Test
    fun afterAChosenSongStopsWhenThatSongEnds() = runTest {
        val s = setUp()
        s.player.play(songs)
        val third = s.player.state.value.queue.first { it.song.id == "s3" }
        s.timer.after(third.key, "Song 3")
        runCurrent()
        assertEquals(SleepState.AfterSong(third.key.toString(), "Song 3"), s.timer.state.value)
        assertFalse(s.player.state.value.stopAfterCurrent)
        elapse(s, 100_000)
        assertFalse(s.player.state.value.stopAfterCurrent)
        elapse(s, 100_000)
        assertEquals("s3", s.now())
        assertTrue(s.player.state.value.stopAfterCurrent)
        elapse(s, 100_000)
        assertEquals("s4", s.now())
        assertFalse(s.player.state.value.playing)
        assertEquals(SleepState.Off, s.timer.state.value)
    }

    @Test
    fun theAwaitedSongLeavingTheQueueEndsTheTimer() = runTest {
        val s = setUp()
        s.player.play(songs)
        val last = s.player.state.value.queue.last()
        s.timer.after(last.key, "Song 5")
        runCurrent()
        s.player.remove(last.key)
        runCurrent()
        assertEquals(SleepState.Off, s.timer.state.value)
    }
}

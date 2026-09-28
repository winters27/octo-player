package app.winters.octo.desktop.system

import app.winters.octo.desktop.player.PlayProblem
import app.winters.octo.desktop.player.PlayerState
import app.winters.octo.desktop.player.QueueEntry
import app.winters.octo.desktop.player.RepeatMode
import app.winters.octo.subsonic.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskbarModelTest {
    private val song = Song("s1", "Airbag", album = "OK Computer", albumId = "a1", duration = 284)
    private val other = Song("s2", "Paranoid Android", album = "OK Computer", albumId = "a1", duration = 383)

    private fun state(playing: Boolean, upcoming: Boolean = true, duration: Long = 284_000, repeat: RepeatMode = RepeatMode.Off): PlayerState {
        val first = QueueEntry(1, song)
        val second = QueueEntry(2, other)
        return PlayerState(
            queue = listOf(first, second),
            current = first,
            upcoming = if (upcoming) listOf(second) else emptyList(),
            playing = playing,
            durationMs = duration,
            repeat = repeat,
        )
    }

    @Test
    fun nothingInLeavesTheButtonsGreyAndNoProgress() {
        val view = taskbarViewOf(PlayerState(), 0)
        assertEquals(TaskbarButtons(playing = false, previous = false, toggle = false, next = false), view.buttons)
        assertEquals(TaskbarProgressKind.None, view.progress.kind)
        assertEquals("Play", view.buttons.toggleTip)
    }

    @Test
    fun playingShowsPauseAndTheSongsProgress() {
        val view = taskbarViewOf(state(playing = true), 71_000)
        assertEquals(TaskbarButtons(playing = true, previous = true, toggle = true, next = true), view.buttons)
        assertEquals("Pause", view.buttons.toggleTip)
        assertEquals(TaskbarProgress(TaskbarProgressKind.Normal, 71_000, 284_000), view.progress)
        assertEquals(250, view.progress.thousandths)
    }

    @Test
    fun pausedShowsAPausedBarUnlessItNeverStarted() {
        assertEquals(TaskbarProgress(TaskbarProgressKind.Paused, 30_000, 284_000), taskbarViewOf(state(playing = false), 30_000).progress)
        assertEquals(TaskbarProgressKind.None, taskbarViewOf(state(playing = false), 0).progress.kind, "a queue brought back at its start")
    }

    @Test
    fun nextIsGreyAtTheEndUnlessTheQueueRepeats() {
        assertFalse(taskbarViewOf(state(playing = true, upcoming = false), 0).buttons.next)
        assertTrue(taskbarViewOf(state(playing = true, upcoming = false, repeat = RepeatMode.All), 0).buttons.next)
    }

    @Test
    fun aSongWithNoLengthYetShowsNoProgressUnlessItFailed() {
        val unknown = state(playing = true, duration = 0).let { it.copy(current = it.current!!.copy(song = song.copy(duration = 0))) }
        assertEquals(TaskbarProgressKind.None, taskbarViewOf(unknown, 5_000).progress.kind)
        assertEquals(TaskbarProgressKind.Error, taskbarViewOf(unknown, 5_000, failing = true).progress.kind)
    }

    @Test
    fun aFailureShowsRedForAMomentAndEachNewOneAgain() {
        val failures = TaskbarFailures(showMs = 3_000)
        val problem = PlayProblem("Couldn't play Airbag")
        assertFalse(failures.failing(null, 0))
        assertTrue(failures.failing(problem, 1_000))
        assertTrue(failures.failing(problem, 3_999))
        assertFalse(failures.failing(problem, 4_000), "the same problem does not start it again")
        assertTrue(failures.failing(PlayProblem("Couldn't play Airbag"), 5_000), "a new failure, even in the same words")
        assertEquals(TaskbarProgressKind.Error, taskbarViewOf(state(playing = true), 1_000, failing = true).progress.kind)
    }

    @Test
    fun theThrottleSendsButtonsOnChangeAndProgressAFewTimesASecond() {
        val throttle = TaskbarThrottle(gapMs = 500)
        val playing = taskbarViewOf(state(playing = true), 10_000)
        val (buttons, progress) = throttle.next(playing, 0)
        assertNotNull(buttons)
        assertNotNull(progress)
        // The same again: nothing to send.
        assertEquals(null to null, throttle.next(playing, 100))
        // Moved on, but too soon.
        assertNull(throttle.next(taskbarViewOf(state(playing = true), 12_000), 200).second)
        // Moved on, and time enough since the last.
        assertEquals(12_000, throttle.next(taskbarViewOf(state(playing = true), 12_000), 600).second?.doneMs)
        // Paused: the way it shows changed, so it goes at once.
        val paused = throttle.next(taskbarViewOf(state(playing = false), 12_050), 650)
        assertEquals(TaskbarProgressKind.Paused, paused.second?.kind)
        assertEquals("Play", paused.first?.toggleTip)
        // A step the bar cannot show is not sent, however long it has been.
        assertNull(throttle.next(taskbarViewOf(state(playing = false), 12_100), 5_000).second)
    }

    @Test
    fun aMinuteOfPlayingSendsAtMostTwoUpdatesASecond() {
        val throttle = TaskbarThrottle()
        var sent = 0
        var now = 0L
        while (now < 60_000) {
            if (throttle.next(taskbarViewOf(state(playing = true, duration = 60_000), now), now).second != null) sent++
            now += 50
        }
        assertTrue(sent in 100..121, "sent $sent in a minute")
    }
}

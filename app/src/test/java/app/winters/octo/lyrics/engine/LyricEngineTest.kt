package app.winters.octo.lyrics.engine

import app.winters.octo.lyrics.LyricsLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricEngineTest {
    private val frame = 1_000_000_000L / 60
    private val view = 1000.0
    private val lineHeight = 80.0

    // Ten lines, one every two seconds, each sung for the whole two.
    private fun engine(): LyricEngine {
        val lines = (0 until 10).map { i ->
            SyncLine(i, "line $i", listOf(SyncWord("line", i * 2.0, i * 2.0 + 2, true)), i * 2.0, i * 2.0 + 2)
        }
        return LyricEngine(lines).also { it.setGeometry(DoubleArray(10) { lineHeight }, 10.0, 60.0, view) }
    }

    private fun LyricEngine.centre(i: Int) = states[i].y.value() + states[i].height / 2

    @Test
    fun theFirstFramePutsTheFocusAtItsAnchorInOneJump() {
        val engine = engine()
        engine.frame(0, 4_500, playing = true, rate = 1.0, offsetMs = 0)
        assertEquals(2, engine.focus)
        assertEquals(FOCUS_AT * view, engine.centre(2), 1e-9)
        assertTrue(engine.states.all { it.y.atRest })
        assertEquals(LineStatus.Active, engine.states[2].status)
        assertEquals(LineStatus.Passed, engine.states[1].status)
        assertEquals(LineStatus.Upcoming, engine.states[3].status)
        assertEquals(1.0, engine.states[2].opacity, 0.0)
        // Sung lines stay visible by default.
        assertEquals(PASSED_SHOWN_OPACITY, engine.states[1].opacity, 0.0)
        assertEquals(0.55, engine.states[3].opacity, 0.0)
    }

    @Test
    fun aNewLineRipplesTheLinesBelowItInTurn() {
        val engine = engine()
        var now = 0L
        var position = 3_900L
        engine.frame(now, position, true, 1.0, 0)
        // Play on into line 2.
        repeat(12) {
            now += frame
            position += 1000 / 60
            engine.frame(now, position, true, 1.0, 0)
        }
        assertEquals(2, engine.focus)
        // The top line on screen has started; three lines below the focus
        // still waits.
        assertFalse(engine.states[0].y.hasQueued)
        assertTrue(engine.states[5].y.hasQueued)
        // Half a second on, everything is under way.
        repeat(30) {
            now += frame
            position += 1000 / 60
            engine.frame(now, position, true, 1.0, 0)
        }
        assertFalse(engine.states.any { it.y.hasQueued })
    }

    @Test
    fun aSeekLandsInOneJumpWithNoRipple() {
        val engine = engine()
        engine.frame(0, 1_000, true, 1.0, 0)
        engine.frame(frame, 13_000, true, 1.0, 0)
        assertEquals(6, engine.focus)
        assertEquals(FOCUS_AT * view, engine.centre(6), 1e-9)
        assertTrue(engine.states.all { it.y.atRest && !it.y.hasQueued })
    }

    @Test
    fun theTimingOffsetMovesTheLyricsClock() {
        val engine = engine()
        // Heard half a second later than written: at 4.2 s the lyrics are at 3.7 s.
        engine.frame(0, 4_200, true, 1.0, offsetMs = 500)
        assertEquals(3.7, engine.lyricTime, 1e-9)
        assertEquals(1, engine.focus)
    }

    @Test
    fun reducedMotionJumpsEveryLine() {
        val engine = engine()
        engine.reduceMotion = true
        engine.frame(0, 3_900, true, 1.0, 0)
        engine.frame(frame * 10, 4_100, true, 1.0, 0)
        assertTrue(engine.states.all { it.y.atRest && !it.y.hasQueued })
        assertEquals(FOCUS_AT * view, engine.centre(2), 1e-9)
    }

    @Test
    fun keptLinesStayFaintlyVisible() {
        val engine = engine()
        engine.look = LyricsLook(keepCompleted = true)
        engine.frame(0, 4_500, true, 1.0, 0)
        assertEquals(PASSED_SHOWN_OPACITY, engine.states[1].opacity, 0.0)
    }

    @Test
    fun wordAnimationIsPreparedNearTheFocusOnly() {
        val engine = engine()
        val prepared = mutableListOf<Int>()
        engine.frame(0, 8_500, true, 1.0, 0, prepare = { prepared += it })
        // Focus 4: itself, then ahead, then behind.
        assertEquals(listOf(4, 5, 6, 7, 8, 9, 3, 2, 1, 0), prepared)
    }

    @Test
    fun aScrollHoldsTheFocusThenFollowsAgain() {
        val engine = engine()
        // The player's position `nanos` into the test, from 4.5 s.
        fun at(nanos: Long) = 4_500 + nanos / 1_000_000
        engine.frame(0, at(0), true, 1.0, 0)
        engine.dragStart()
        engine.dragBy(-150.0)
        engine.dragEnd()
        assertTrue(engine.scrolling)
        // Every frame from `from` to `to`, playing on.
        fun play(from: Int, to: Int) {
            for (n in from..to) engine.frame(frame * n, at(frame * n), true, 1.0, 0)
        }
        // The song moves on into line 3, but the lines hold where the finger left them.
        play(1, 96)
        assertEquals(2, engine.focus)
        assertEquals(LineStatus.Active, engine.states[3].status)
        // Passed lines show while scrolling.
        assertTrue(engine.states[1].opacity > 0.0)
        // Two seconds after the finger lifted, the lyrics follow the song again.
        play(97, 126)
        assertFalse(engine.scrolling)
        assertEquals(3, engine.focus)
    }

    @Test
    fun aScrollStopsBeforeTheFirstLinePassesThirtyPercent() {
        val engine = engine()
        engine.frame(0, 500, true, 1.0, 0)
        engine.dragStart()
        engine.dragBy(10_000.0)
        repeat(120) { engine.frame(frame * (it + 1), 500 + it * 16L, true, 1.0, 0) }
        assertEquals(0.3 * view, engine.states[0].y.value() + engine.scrollOffset, 1.0)
    }
}

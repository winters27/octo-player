package app.winters.octo.lyrics.engine

import app.winters.octo.lyrics.OUTPUT_TIMING_LIMIT_MS
import app.winters.octo.lyrics.heardAt
import app.winters.octo.lyrics.stepTiming
import app.winters.octo.lyrics.totalOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

// The output's lyrics timing (the phone's speaker, a pair of earbuds) runs
// in the clock as its latency, on top of the song's own timing: the words
// follow the song less both, a tap plays from the line plus both, and a new
// output moves the words without a jump.
class OutputTimingTest {
    private val frame = 1_000_000_000L / 60
    private val view = 1000.0

    // Ten lines, one every two seconds, each sung for the whole two.
    private fun engine(): LyricEngine {
        val lines = (0 until 10).map { i ->
            SyncLine(i, "line $i", listOf(SyncWord("line", i * 2.0, i * 2.0 + 2, true)), i * 2.0, i * 2.0 + 2)
        }
        return LyricEngine(lines).also { it.setGeometry(DoubleArray(10) { 80.0 }, 10.0, 60.0, view) }
    }

    // One tap on the output's Earlier button.
    private fun earlier(outputMs: Long) = stepTiming(outputMs, -1, OUTPUT_TIMING_LIMIT_MS)

    @Test
    fun earlierShowsTheWordsSoonerByExactlyOneStep() {
        // The words are behind the voice, so Earlier is tapped once.
        val before = engine()
        val after = engine()
        before.frame(0, 6_100, true, 1.0, offsetMs = 0, outputOffsetMs = 0)
        after.frame(0, 6_100, true, 1.0, offsetMs = 0, outputOffsetMs = earlier(0))
        assertEquals(-50L, earlier(0))
        // At the same point in the song the lyrics are a step further on,
        // so every word lights up a step sooner.
        assertEquals(0.05, after.lyricTime - before.lyricTime, 1e-9)

        // A word written at 8.0 s lights at 7.95 s of the song, not 8.0 s.
        val word = 8.0
        val lit = engine()
        lit.frame(0, 7_950, true, 1.0, offsetMs = 0, outputOffsetMs = earlier(0))
        assertEquals(word, lit.lyricTime, 1e-9)
        assertEquals(4, lit.focus)
    }

    @Test
    fun theSongsAndTheOutputsTimingAddUp() {
        val engine = engine()
        // The song's words run 0.25 s late and the earbuds' 0.1 s early.
        engine.frame(0, 10_000, false, 1.0, offsetMs = 250, outputOffsetMs = -100)
        assertEquals(10.0 - 0.25 + 0.1, engine.lyricTime, 1e-9)
    }

    @Test
    fun earlierWhilePlayingMovesTheWordsOnTheNextFrameWithoutASeek() {
        val engine = engine()
        engine.frame(0, 6_100, true, 1.0, 0, 0)
        val before = engine.lyricTime
        engine.frame(frame, 6_100 + 1000 / 60, true, 1.0, 0, earlier(0))
        assertEquals(before + 1.0 / 60 + 0.05, engine.lyricTime, 0.002)
        assertFalse(engine.clock.seeked)
    }

    @Test
    fun aTapPlaysFromWhereTheLineIsHeardOnThisOutputAndLandsOnIt() {
        listOf(0L, 750L, -1_000L).forEach { song ->
            listOf(0L, -150L, 200L).forEach { output ->
                val engine = engine()
                engine.frame(0, 1_000, true, 1.0, song, output)
                // Tapping line 6, as the view does: it seeks to where the line
                // is heard with both timings.
                val total = totalOffset(song, output)
                val target = heardAt((engine.lines[6].start * 1000).roundToLong(), total)
                assertEquals(12_000 + song + output, target)
                engine.tapped(6, target / 1000.0)

                // The player has not got there yet: the line shows at its start.
                engine.frame(frame, 1_000 + 1000 / 60, true, 1.0, song, output)
                assertEquals("song $song output $output", 12.0, engine.lyricTime, 1e-9)
                assertEquals(6, engine.focus)

                // It gets there: still the line's start, with no step.
                engine.frame(2 * frame, target, true, 1.0, song, output)
                assertEquals("song $song output $output", 12.0, engine.lyricTime, 1e-9)
                assertEquals(6, engine.focus)
                assertFalse(engine.clock.seeked)
            }
        }
    }

    @Test
    fun aNewOutputTakesAFreshAnchorAndIsNotASeek() {
        val clock = LyricClock()
        var reported = 30.0
        for (n in 0..10) {
            reported = 30.0 + n / 60.0
            clock.frame(n * frame, reported, 1.0, true)
        }
        // Earbuds come in with their own timing, far enough to be a seek if
        // it were one: the time moves by it, from the player's own position.
        clock.setLatency(-0.8)
        val next = 30.0 + 11 / 60.0 + 0.004
        val time = clock.frame(11 * frame, next, 1.0, true)
        assertEquals(next + 0.8, time, 1e-9)
        assertFalse(clock.seeked)
        // And it runs on from there.
        assertEquals(30.0 + 12 / 60.0 + 0.004 + 0.8, clock.frame(12 * frame, 30.0 + 12 / 60.0, 1.0, true), 1e-6)
        assertFalse(clock.seeked)
        // The same timing again changes nothing.
        clock.setLatency(-0.8)
        assertEquals(30.0 + 13 / 60.0 + 0.004 + 0.8, clock.frame(13 * frame, 30.0 + 13 / 60.0, 1.0, true), 1e-6)
    }

    @Test
    fun aNewOutputMovesTheLinesInsteadOfJumpingThem() {
        val engine = engine()
        // Two seconds of playing from 7.5 s, into line 4.
        for (n in 0 until 120) engine.frame(n * frame, 7_500 + n * 1000L / 60, true, 1.0, 0, 0)
        assertEquals(4, engine.focus)
        assertTrue(engine.states.all { it.y.atRest && !it.y.hasQueued })
        // Earbuds whose words run a second earlier: line 5 is the one sung
        // now, and the lines ripple over to it instead of jumping.
        engine.frame(120 * frame, 7_500 + 2_000, true, 1.0, 0, -1_000)
        assertEquals(5, engine.focus)
        assertFalse(engine.clock.seeked)
        assertTrue(engine.states.any { !it.y.atRest || it.y.hasQueued })
        // A seek, for comparison, is taken as one.
        val sought = engine()
        sought.frame(0, 7_500, true, 1.0, 0, 0)
        sought.frame(frame, 10_500, true, 1.0, 0, 0)
        assertTrue(sought.clock.seeked)
    }
}

package app.winters.octo.lyrics.engine

import app.winters.octo.lyrics.heardAt
import app.winters.octo.lyrics.stepTiming
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToLong

// The timing control moves the flowing lyrics while they play: each frame
// reads the offset afresh, so a step lands on the next frame, with no
// restart, and a tap still plays from where the line is heard.
class LyricTimingLiveTest {
    private val frame = 1_000_000_000L / 60
    private val view = 1000.0

    // Ten lines, one every two seconds, each sung for the whole two.
    private fun engine(): LyricEngine {
        val lines = (0 until 10).map { i ->
            SyncLine(i, "line $i", listOf(SyncWord("line", i * 2.0, i * 2.0 + 2, true)), i * 2.0, i * 2.0 + 2)
        }
        return LyricEngine(lines).also { it.setGeometry(DoubleArray(10) { 80.0 }, 10.0, 60.0, view) }
    }

    @Test
    fun aStepLandsOnTheNextFrameWithoutARestart() {
        val engine = engine()
        var now = 0L
        var position = 6_100L
        engine.frame(now, position, true, 1.0, offsetMs = 0)
        assertEquals(3, engine.focus)

        // Fifteen steps earlier while it plays: the words run 0.75 s sooner.
        val earlier = (1..15).fold(0L) { offset, _ -> stepTiming(offset, -1) }
        assertEquals(-750L, earlier)
        now += frame
        position += 1000 / 60
        engine.frame(now, position, true, 1.0, offsetMs = earlier)
        assertEquals((6_116 + 750) / 1000.0, engine.lyricTime, 0.02)
        assertEquals(3, engine.focus)

        // Moved 1.5 s the other way, the line before it is sung again.
        engine.frame(now + frame, position + 1000 / 60, true, 1.0, offsetMs = earlier + 1_500)
        assertEquals(2, engine.focus)
    }

    @Test
    fun resettingPutsTheWordsBackWhereTheyAreWritten() {
        val engine = engine()
        engine.frame(0, 9_000, true, 1.0, offsetMs = 1_250)
        assertEquals(7.75, engine.lyricTime, 1e-9)
        engine.frame(frame, 9_000 + 1000 / 60, true, 1.0, offsetMs = 0)
        assertEquals(4, engine.focus)
        assertEquals(9.0, engine.lyricTime, 0.02)
    }

    @Test
    fun aTapPlaysFromWhereTheLineIsHeardAndLandsOnIt() {
        listOf(0L, 750L, -1_000L).forEach { offset ->
            val engine = engine()
            engine.frame(0, 1_000, true, 1.0, offset)
            // Tapping line 6, as the view does: it seeks to where it is heard.
            val target = heardAt((engine.lines[6].start * 1000).roundToLong(), offset)
            assertEquals(12_000 + offset, target)
            engine.tapped(6, target / 1000.0)
            engine.frame(frame, target, true, 1.0, offset)
            assertEquals("offset $offset", 6, engine.focus)
        }
    }
}

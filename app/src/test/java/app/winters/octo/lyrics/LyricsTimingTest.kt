package app.winters.octo.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTimingTest {
    private val lines = listOf(
        LyricLine(startMs = 0, text = ""),
        LyricLine(startMs = 10_000, text = "first"),
        LyricLine(startMs = 20_000, text = "second"),
    )

    @Test
    fun noOffsetChangesNothing() {
        assertEquals(12_345, lyricsClock(12_345, 0))
        assertEquals(10_000, heardAt(10_000, 0))
    }

    @Test
    fun laterWordsLightUpLater() {
        // Moved half a second later, the first line is not sung yet at 10.25 s.
        assertEquals(0, lines.lineAt(lyricsClock(10_250, 500)))
        assertEquals(1, lines.lineAt(lyricsClock(10_500, 500)))
    }

    @Test
    fun earlierWordsLightUpSooner() {
        assertEquals(1, lines.lineAt(lyricsClock(9_750, -250)))
        assertEquals(0, lines.lineAt(lyricsClock(9_700, -250)))
    }

    @Test
    fun aTapPlaysFromWhereTheLineIsHeard() {
        assertEquals(20_750, heardAt(20_000, 750))
        assertEquals(19_000, heardAt(20_000, -1_000))
        // And the line tapped is then the one being sung.
        assertEquals(2, lines.lineAt(lyricsClock(heardAt(20_000, 750), 750)))
        assertEquals(2, lines.lineAt(lyricsClock(heardAt(20_000, -1_000), -1_000)))
    }

    @Test
    fun aTapNeverSeeksBeforeTheStart() {
        assertEquals(0, heardAt(0, -2_000))
        assertEquals(0, heardAt(1_000, -1_250))
    }

    @Test
    fun stepsAreAQuarterSecondAndStopAtTheLimit() {
        assertEquals(250, stepTiming(0, 1))
        assertEquals(-500, stepTiming(-250, -1))
        assertEquals(0, stepTiming(250, -1))
        assertEquals(TIMING_LIMIT_MS, stepTiming(TIMING_LIMIT_MS, 1))
        assertEquals(-TIMING_LIMIT_MS, stepTiming(-TIMING_LIMIT_MS, -3))
    }

    @Test
    fun theOffsetReadsInPlainWords() {
        assertEquals("In time", timingLabel(0))
        assertEquals("0.25 s later", timingLabel(250))
        assertEquals("0.5 s earlier", timingLabel(-500))
        assertEquals("1 s later", timingLabel(1_000))
        assertEquals("1.75 s earlier", timingLabel(-1_750))
        assertEquals("10 s later", timingLabel(10_000))
    }

    @Test
    fun theTimingControlShowsASignedOffset() {
        assertEquals("0 s", signedTiming(0))
        assertEquals("+0.75 s", signedTiming(750))
        assertEquals("-0.25 s", signedTiming(-250))
        assertEquals("+1 s", signedTiming(1_000))
        assertEquals("-10 s", signedTiming(-TIMING_LIMIT_MS))
    }
}

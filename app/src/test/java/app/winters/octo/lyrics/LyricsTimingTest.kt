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
    fun stepsAreATwentiethOfASecondAndStopAtTheLimit() {
        assertEquals(50, stepTiming(0, 1))
        assertEquals(-300, stepTiming(-250, -1))
        assertEquals(0, stepTiming(50, -1))
        assertEquals(TIMING_LIMIT_MS, stepTiming(TIMING_LIMIT_MS, 1))
        assertEquals(-TIMING_LIMIT_MS, stepTiming(-TIMING_LIMIT_MS, -3))
        // An output stops sooner.
        assertEquals(OUTPUT_TIMING_LIMIT_MS, stepTiming(OUTPUT_TIMING_LIMIT_MS - 20, 1, OUTPUT_TIMING_LIMIT_MS))
        assertEquals(-OUTPUT_TIMING_LIMIT_MS, stepTiming(-OUTPUT_TIMING_LIMIT_MS, -1, OUTPUT_TIMING_LIMIT_MS))
    }

    @Test
    fun anOutputNeverMovedIsInTime() {
        val stored = mapOf<String, Any?>(
            "lyrics_output_offset:bluetooth:AA:BB" to -150L,
            "lyrics_output_offset:speaker" to 50L,
            // A song's timing and other settings are not outputs.
            "offset:track-1" to 750L,
            "lyrics_style" to "Flowing",
        )
        val outputs = outputOffsetsIn(stored)
        assertEquals(mapOf("bluetooth:AA:BB" to -150L, "speaker" to 50L), outputs)
        assertEquals(-150L, outputOffsetIn(outputs, "bluetooth:AA:BB"))
        assertEquals(50L, outputOffsetIn(outputs, "speaker"))
        assertEquals(0L, outputOffsetIn(outputs, "wired"))
        assertEquals(0L, outputOffsetIn(outputs, "bluetooth:CC:DD"))
        assertEquals(0L, outputOffsetIn(emptyMap(), "speaker"))
    }

    @Test
    fun theSongsAndTheOutputsTimingAddUp() {
        val total = totalOffset(songMs = 250, outputMs = -100)
        assertEquals(150L, total)
        // Line 2 is sung at 20.15 s, and a tap there plays from 20.15 s.
        assertEquals(1, lines.lineAt(lyricsClock(20_149, total)))
        assertEquals(2, lines.lineAt(lyricsClock(20_150, total)))
        assertEquals(20_150L, heardAt(20_000, total))
    }

    @Test
    fun theScreenLeadIsTwoFramesAtItsRefreshRate() {
        assertEquals(-33L, screenLeadMs(60f))
        assertEquals(-22L, screenLeadMs(90f))
        assertEquals(-17L, screenLeadMs(120f))
        // A rate the display could not give is taken as 60.
        assertEquals(-33L, screenLeadMs(0f))
        assertEquals(-33L, screenLeadMs(Float.NaN))
    }

    @Test
    fun theMenuLineNamesWhatWasMoved() {
        assertEquals(null, timingSummary(0, 0, "Galaxy Buds"))
        assertEquals("This song +0.25 s", timingSummary(250, 0, "Galaxy Buds"))
        assertEquals("Galaxy Buds -0.15 s", timingSummary(0, -150, "Galaxy Buds"))
        assertEquals("This song -0.1 s · Phone speaker +0.05 s", timingSummary(-100, 50, "Phone speaker"))
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

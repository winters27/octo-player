package app.winters.octo.lyrics.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Where the word fill's soft edge is, and when a word lights. A typical
// sung word: 0.3 s long, 60 px wide, on a 30 sp line at 2.625 px a dp.
class WordFillTest {
    private val width = 60.0
    private val lineHeight = 30 * 2.625
    private val shape = maskShape(width, lineHeight, softness = 1.0)

    // The soft edge's width in pixels.
    private val edge = shape.edge * width

    // The middle of the soft edge, from the word's left edge, this far
    // through the fill.
    private fun middle(progress: Double): Double = maskLeft(progress, 0.0, width, shape, rtl = false) + shape.size * width / 2

    // How much of the word is lit, 0 to 1: bright left of the edge, dark
    // right of it, a straight ramp across it.
    private fun lit(progress: Double): Double {
        val mid = middle(progress)
        val steps = 6000
        var sum = 0.0
        for (i in 0 until steps) {
            val x = (i + 0.5) * width / steps
            sum += ((mid + edge / 2 - x) / edge).coerceIn(0.0, 1.0)
        }
        return sum / steps
    }

    @Test
    fun theEdgesMiddleRunsFromHalfAnEdgeBeforeTheWordToHalfAnEdgePastIt() {
        // x(q) = -e/2 + q * (W + e): the mask's path, which the lead leaves alone.
        assertEquals(-edge / 2, middle(0.0), 1e-9)
        assertEquals(width / 2, middle(0.5), 1e-9)
        assertEquals(width + edge / 2, middle(1.0), 1e-9)
        assertEquals(0.5, lit(0.5), 1e-3)
    }

    @Test
    fun aWordLightsFromItsFirstLetterAsItStarts() {
        val start = 10.0
        val end = 10.3
        val lead = fillLead(start, end, shape.edge)
        // About 59 ms for this word: the time the edge takes to cross half of itself.
        assertEquals(0.0594, lead, 0.0005)
        // As the word starts, the middle of the edge is on its first letter.
        assertEquals(0.0, middle(fillProgress(start, start, end, shape.edge)), 1e-9)
        // Without the lead it was still half an edge short, and got there
        // only this far into the word.
        val short = middle(wordProgress(start, start, end))
        assertEquals(-edge / 2, short, 1e-9)
        // By the middle of the word it is mostly lit, where it was half lit.
        val halfway = start + 0.15
        assertTrue(lit(fillProgress(halfway, start, end, shape.edge)) > 0.8)
        assertEquals(0.5, lit(wordProgress(halfway, start, end)), 1e-3)
        // It is whole by the lead before its end, and dark until the lead
        // before its start.
        assertEquals(1.0, fillProgress(end - lead, start, end, shape.edge), 1e-9)
        assertEquals(0.0, fillProgress(start - lead, start, end, shape.edge), 1e-9)
    }

    @Test
    fun theLeadIsKeptSmallForLongNarrowWords() {
        val narrow = maskShape(12.0, lineHeight, 1.0)
        assertEquals(FILL_LEAD_MAX_S, fillLead(0.0, 3.0, narrow.edge), 0.0)
        // No edge or no length, no lead.
        assertEquals(0.0, fillLead(1.0, 1.0, shape.edge), 0.0)
        assertEquals(0.0, fillLead(1.0, 2.0, 0.0), 0.0)
        // Always inside the half second a line's words start moving before it.
        assertTrue(FILL_LEAD_MAX_S < 0.5)
    }
}

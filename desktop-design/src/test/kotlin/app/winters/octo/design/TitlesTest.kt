package app.winters.octo.design

import androidx.compose.ui.unit.Constraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitlesTest {
    private fun words(text: String) = wordSpans(text).map { text.substring(it.first, it.last + 1) }

    @Test
    fun aTitleBreaksOnlyBetweenItsWords() {
        assertEquals(listOf("Everything", "I", "have", "ever", "loved"), words("Everything I have ever loved"))
    }

    @Test
    fun aHyphenatedNameMayEndALineAfterItsHyphen() {
        assertEquals(listOf("Radiohead-", "OK_Computer"), words("Radiohead-OK_Computer"))
    }

    @Test
    fun aNameJoinedWithUnderscoresMayWrapAfterThem() {
        assertEquals(3, wordSpans(breakableText("Radiohead_OK_Computer")).size)
        // Nothing is added where an underscore joins no words.
        assertEquals("a_ b __", breakableText("a_ b __"))
        assertEquals("Radiohead_" + Char(0x200B) + "OK", breakableText("Radiohead_OK"))
    }

    @Test
    fun aScriptWithoutSpacesBreaksBetweenItsCharacters() {
        assertTrue(words("夜のドライブ").size > 1)
    }

    @Test
    fun aWordThatFitsKeepsItsSize() {
        assertEquals(1f, fitScale(widest = 300, width = 300), 0f)
    }

    @Test
    fun aWordTooWideIsSetJustSmallEnoughToFit() {
        val scale = fitScale(widest = 600, width = 300)
        assertTrue(scale < 0.5f)
        assertTrue(scale > 0.45f)
        assertTrue(600 * scale <= 300)
    }

    @Test
    fun theButtonsShareTheLineWhenTheWholeTitleFits() {
        assertTrue(actionsBeside(titleWidth = 100, actionsWidth = 200, gap = 12, width = 312))
    }

    @Test
    fun aTitleOnePixelTooWideSendsTheButtonsUnderIt() {
        assertFalse(actionsBeside(titleWidth = 101, actionsWidth = 200, gap = 12, width = 312))
    }

    @Test
    fun withNoLimitToTheWidthTheyShareTheLine() {
        assertTrue(actionsBeside(titleWidth = 5_000, actionsWidth = 200, gap = 12, width = Constraints.Infinity))
    }
}

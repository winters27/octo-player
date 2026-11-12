package app.winters.octo.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The page header on a 360dp phone: the title is one whole line, a long
// one set smaller to fit, and a count joins it only when it fits whole.
// Widths are in dp at the header's sizes.
class HeaderFitTest {
    private val gap = 10

    @Test
    fun theLongestTitlesKeepTheirWholeWidthAfterTheBackButton() {
        // "Recently removed", "Library health", "Server downloads" and a
        // German length one, "Zuletzt entfernte Titel": never cut to the line.
        for (title in listOf(270, 230, 280, 360)) {
            assertEquals(50 to title, titlePlace(back = 40, title = title, gap = gap))
        }
        assertEquals(0 to 200, titlePlace(back = 0, title = 200, gap = gap))
    }

    // "Streaming and downloads" ran off the screen: a title wider than the
    // room after the back button keeps one line and is set just small enough
    // to fit whole; one that fits keeps the full size.
    @Test
    fun aLongTitleShrinksToFitItsOneLine() {
        val room = 360 - 12 - 16 - 50
        assertEquals(1f, titleScale(title = 200, room = room))
        assertEquals(1f, titleScale(title = room, room = room))
        assertEquals(room / 400f, titleScale(title = 400, room = room))
        assertEquals(1f, titleScale(title = 0, room = room))
    }

    // The control line between the page's 16dp edges.
    private val line = 360 - 16 - 16
    @Test
    fun aCountFitsWholeAfterTheTitleOrIsLeftOff() {
        // "Favorites" after the back button with "1,204 songs".
        assertTrue(countFits(titleEnd = 50 + 150, count = 80, gap = gap, width = line))
        // "Recently removed" leaves too little room: the count goes, never cut.
        assertFalse(countFits(titleEnd = 50 + 270, count = 80, gap = gap, width = line))
        // Exactly filling the line still fits.
        assertTrue(countFits(titleEnd = 238, count = 80, gap = gap, width = line))
        assertFalse(countFits(titleEnd = 50, count = 0, gap = gap, width = line))
    }
}

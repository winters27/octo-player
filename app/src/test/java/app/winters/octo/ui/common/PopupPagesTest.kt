package app.winters.octo.ui.common

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import app.winters.octo.design.PageTrail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupPagesTest {
    private val start = PageTrail.of("actions")

    @Test
    fun itStartsOnItsFirstPageWithNoWayBack() {
        assertEquals("actions", start.current)
        assertFalse(start.canGoBack)
    }

    @Test
    fun openingAPageGoesDeeperWithAWayBack() {
        val info = start.open("info")
        assertEquals("info", info.current)
        assertTrue(info.canGoBack)
        assertTrue(info.deeper)
        assertEquals(listOf("actions", "info"), info.pages)
    }

    @Test
    fun backReturnsToThePageBeforeAndSlidesTheOtherWay() {
        val back = start.open("playlists").open("new").back()
        assertEquals("playlists", back.current)
        assertFalse(back.deeper)
        assertTrue(back.canGoBack)
        assertEquals("actions", back.back().current)
    }

    @Test
    fun backOnTheFirstPageChangesNothing() {
        assertSame(start, start.back())
    }

    @Test
    fun replacingKeepsTheDepth() {
        val asked = start.open("question").replace("answer")
        assertEquals(listOf("actions", "answer"), asked.pages)
        assertEquals("actions", asked.back().current)
    }

    @Test
    fun startingOverIsANewOpeningEvenOnTheSamePage() {
        val again = start.open("info").restart("actions")
        assertEquals(listOf("actions"), again.pages)
        assertFalse(again.canGoBack)
        // Not equal to the first opening, so its page starts fresh.
        assertNotEquals(start, again)
        assertEquals(start.opening + 1, again.opening)
        // Moving within an opening keeps its count.
        assertEquals(again.opening, again.open("rate").back().opening)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aTrailAlwaysHasAPage() {
        PageTrail(emptyList<String>())
    }

    @Test
    fun aSmallControlIsItsOwnAnchor() {
        val button = IntRect(900, 200, 1000, 300)
        assertEquals(button, menuAnchor(button, IntOffset(950, 250), windowWidth = 1080))
    }

    @Test
    fun aWideRowIsNarrowedToTheFingerKeepingItsTopAndBottom() {
        val row = IntRect(0, 600, 1080, 760)
        assertEquals(IntRect(300, 600, 300, 760), menuAnchor(row, IntOffset(300, 700), windowWidth = 1080))
    }
}

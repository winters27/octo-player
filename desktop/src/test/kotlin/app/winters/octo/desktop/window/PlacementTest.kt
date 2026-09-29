package app.winters.octo.desktop.window

import app.winters.octo.desktop.settings.WindowSpot
import java.awt.Dimension
import java.awt.Rectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacementTest {
    private val laptop = ScreenArea(0f, 0f, 1920f, 1040f)
    private val rightMonitor = ScreenArea(1920f, 0f, 2560f, 1400f)

    @Test
    fun theFirstTimeItOpensCentredAtItsUsualSize() {
        // 1600 by 1000, at most nine tenths of the screen.
        assertEquals(WindowSpot(160f, 52f, 1600f, 936f), placeWindow(null, listOf(laptop)))
        assertEquals(WindowSpot(2400f, 200f, 1600f, 1000f), placeWindow(null, listOf(rightMonitor)))
    }

    @Test
    fun aSpotSavedAtTheMinimumOpensRoomyAndCentredStillFilled() {
        // What a minimized window used to be saved as.
        val minimumInTheCorner = WindowSpot(0f, 0f, 960f, 600f, maximized = true)
        assertEquals(WindowSpot(160f, 52f, 1600f, 936f, maximized = true), placeWindow(minimumInTheCorner, listOf(laptop)))
    }

    @Test
    fun onlyAWindowAtItsOwnSizeOnAScreenIsKept() {
        assertTrue(keepsSpot(minimized = false, filled = false, x = 120f, y = 80f))
        assertFalse("minimized", keepsSpot(minimized = true, filled = false, x = 120f, y = 80f))
        assertFalse("filling the screen", keepsSpot(minimized = false, filled = true, x = 0f, y = 0f))
        assertFalse("parked off screen", keepsSpot(minimized = false, filled = false, x = -32000f, y = -32000f))
    }

    @Test
    fun itReopensWhereItWas() {
        val saved = WindowSpot(2100f, 100f, 1500f, 900f, maximized = true)
        assertEquals(saved, placeWindow(saved, listOf(laptop, rightMonitor)))
    }

    @Test
    fun aWindowLeftOnAScreenThatIsGoneComesBack() {
        val saved = WindowSpot(2100f, 100f, 1500f, 900f)
        assertEquals(WindowSpot(210f, 70f, 1500f, 900f), placeWindow(saved, listOf(laptop)))
    }

    @Test
    fun itIsNeverSmallerThanTheMinimumNorBiggerThanTheScreen() {
        assertEquals(WindowSpot(10f, 10f, 1000f, 600f), placeWindow(WindowSpot(10f, 10f, 1000f, 200f), listOf(laptop)))
        assertEquals(WindowSpot(0f, 0f, 1920f, 1040f), placeWindow(WindowSpot(0f, 0f, 5000f, 3000f), listOf(laptop)))
    }

    @Test
    fun aTitleBarHalfOffTheTopIsPulledBackOn() {
        val saved = WindowSpot(100f, -25f, 1200f, 800f)
        assertEquals(WindowSpot(100f, 0f, 1200f, 800f), placeWindow(saved, listOf(laptop)))
    }

    @Test
    fun aFilledWindowGoesBackWhereItWasWhileThatScreenIsThere() {
        val before = WindowSpot(2100f, 100f, 1500f, 900f)
        assertEquals(before, restoreSpot(before, listOf(laptop, rightMonitor), here = laptop))
        val partlyOff = WindowSpot(-200f, 100f, 1200f, 800f)
        assertEquals("mostly on a screen is left alone", partlyOff, restoreSpot(partlyOff, listOf(laptop), here = laptop))
    }

    @Test
    fun aFilledWindowWhoseScreenIsGoneComesBackOnTheScreenItIsOn() {
        val before = WindowSpot(2100f, 100f, 1500f, 900f)
        assertEquals(WindowSpot(210f, 70f, 1500f, 900f), restoreSpot(before, listOf(laptop), here = laptop))
        val big = WindowSpot(2000f, 0f, 2500f, 1400f)
        assertEquals("kept inside the screen it lands on", WindowSpot(0f, 0f, 1920f, 1040f), restoreSpot(big, listOf(laptop), here = laptop))
        val mini = WindowSpot(5000f, 5000f, 380f, 124f)
        assertEquals("with the window's own least size", WindowSpot(770f, 458f, 380f, 124f), restoreSpot(mini, listOf(laptop), laptop, 300f, 96f))
    }

    @Test
    fun theMainScreenComesFirst() {
        assertEquals(listOf("main", "left", "right"), mainFirst(listOf("left", "main", "right"), "main"))
        assertEquals(listOf("left", "right"), mainFirst(listOf("left", "right"), "gone"))
        assertEquals(listOf("left"), mainFirst(listOf("left"), null))
    }

    @Test
    fun resizingStaysBetweenTheLeastAndTheMost() {
        val from = Rectangle(100, 100, 380, 124)
        val min = Dimension(300, 96)
        val max = Dimension(720, 320)
        assertEquals(Rectangle(100, 100, 720, 320), resizedBounds(from, 2000, 2000, 1, 1, min, max))
        assertEquals("the right edge stays put", Rectangle(-240, 100, 720, 124), resizedBounds(from, -2000, 0, -1, 0, min, max))
        assertEquals(Rectangle(100, 100, 300, 96), resizedBounds(from, -2000, -2000, 1, 1, min, max))
        assertEquals("the bottom edge stays put", Rectangle(100, 128, 380, 96), resizedBounds(from, 0, 500, 0, -1, min, max))
        assertEquals("no most set", Rectangle(100, 100, 2380, 124), resizedBounds(from, 2000, 0, 1, 0, min, Dimension(Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt())))
    }
}

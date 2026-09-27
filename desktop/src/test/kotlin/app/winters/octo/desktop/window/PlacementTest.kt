package app.winters.octo.desktop.window

import app.winters.octo.desktop.settings.WindowSpot
import org.junit.Assert.assertEquals
import org.junit.Test

class PlacementTest {
    private val laptop = ScreenArea(0f, 0f, 1920f, 1040f)
    private val rightMonitor = ScreenArea(1920f, 0f, 2560f, 1400f)

    @Test
    fun theFirstTimeItOpensCentredAtItsUsualSize() {
        assertEquals(WindowSpot(320f, 120f, 1280f, 800f), placeWindow(null, listOf(laptop)))
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
        assertEquals(WindowSpot(10f, 10f, 960f, 600f), placeWindow(WindowSpot(10f, 10f, 300f, 200f), listOf(laptop)))
        assertEquals(WindowSpot(0f, 0f, 1920f, 1040f), placeWindow(WindowSpot(0f, 0f, 5000f, 3000f), listOf(laptop)))
    }

    @Test
    fun aTitleBarHalfOffTheTopIsPulledBackOn() {
        val saved = WindowSpot(100f, -25f, 1200f, 800f)
        assertEquals(WindowSpot(100f, 0f, 1200f, 800f), placeWindow(saved, listOf(laptop)))
    }
}

package app.winters.octo.ui.common

import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import app.winters.octo.design.PopupSpot
import app.winters.octo.design.placePopup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassPopupTest {
    // A phone of 1080 by 2400 pixels, with a 36 pixel margin and a 18 pixel gap.
    private val window = IntSize(1080, 2400)
    private val menu = IntSize(600, 500)

    private fun place(anchor: IntRect?, size: IntSize = menu, top: Int = 0, bottom: Int = 0): PopupSpot =
        placePopup(anchor, size, window, margin = 36, gap = 18, top = top, bottom = bottom)

    @Test
    fun aControlOnTheLeftGetsTheListUnderItFromItsStartEdge() {
        val spot = place(IntRect(40, 300, 240, 400))
        assertEquals(PopupSpot(x = 40, y = 418, above = false, fromEnd = false), spot)
        assertEquals(TransformOrigin(0f, 0f), spot.origin)
    }

    @Test
    fun aControlOnTheRightGetsTheListLinedUpWithItsEndEdge() {
        val spot = place(IntRect(800, 300, 1040, 400))
        assertEquals(PopupSpot(x = 440, y = 418, above = false, fromEnd = true), spot)
        assertEquals(TransformOrigin(1f, 0f), spot.origin)
    }

    @Test
    fun nearTheBottomItFlipsAboveTheControl() {
        val spot = place(IntRect(800, 2000, 1040, 2100), IntSize(600, 800))
        assertTrue(spot.above)
        assertEquals(2000 - 18 - 800, spot.y)
        assertEquals(TransformOrigin(1f, 1f), spot.origin)
    }

    @Test
    fun theSystemBarsCountAsEdges() {
        // It would fit under the control on a bare screen, but not above the
        // navigation bar, so it goes over the control instead.
        val anchor = IntRect(40, 1700, 240, 1800)
        assertFalse(place(anchor, IntSize(600, 500)).above)
        assertTrue(place(anchor, IntSize(600, 500), bottom = 200).above)
        // Pushed down, it stays clear of the status bar.
        val high = place(IntRect(40, 0, 240, 60), IntSize(600, 500), top = 120)
        assertTrue(high.y >= 120 + 36)
    }

    @Test
    fun tooTallForEitherSideItTakesTheRoomierOneAndStaysOnScreen() {
        val tall = IntSize(600, 1500)
        val spot = place(IntRect(40, 1100, 240, 1200), tall)
        // 1146 below against 1046 above: below, pushed up to fit.
        assertFalse(spot.above)
        assertEquals(2400 - 36 - 1500, spot.y)
        val higher = place(IntRect(40, 1300, 240, 1400), tall)
        assertTrue(higher.above)
        assertEquals(36, higher.y)
    }

    @Test
    fun itKeepsTheMarginFromBothSides() {
        assertEquals(36, place(IntRect(0, 300, 100, 400)).x)
        assertEquals(1080 - 36 - 600, place(IntRect(980, 300, 1080, 400)).x)
        // Wider than the room: it keeps to the start.
        assertEquals(36, place(IntRect(900, 300, 1000, 400), IntSize(1200, 500)).x)
    }

    @Test
    fun withNoControlItSitsInTheMiddle() {
        val spot = place(null)
        assertTrue(spot.centred)
        assertEquals(240, spot.x)
        assertEquals(36 + (2400 - 72 - 500) / 2, spot.y)
        assertEquals(TransformOrigin.Center, spot.origin)
    }
}

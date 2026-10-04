package app.winters.octo.design

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

// Where a tooltip goes, as StreamNook places its own: on its side, flipped
// when it would leave the window, then slid along to stay 12 clear of the
// edges.
class TooltipPlacementTest {
    private val window = IntSize(1000, 700)
    private val bubble = IntSize(100, 30)

    private fun spot(side: TooltipSide, anchor: IntRect, start: Boolean = false) =
        tooltipSpot(side, start, anchor, window, bubble, gap = 8, edge = 12)

    @Test
    fun aboveAndCentredByDefault() {
        val placed = spot(TooltipSide.Top, IntRect(450, 400, 490, 440))
        assertEquals(TooltipSpot(TooltipSide.Top, IntOffset(420, 362)), placed)
    }

    @Test
    fun aControlAtTheTopShowsItBelow() {
        val placed = spot(TooltipSide.Top, IntRect(450, 4, 490, 36))
        assertEquals(TooltipSpot(TooltipSide.Bottom, IntOffset(420, 44)), placed)
    }

    @Test
    fun belowFlipsAboveAtTheFoot() {
        val placed = spot(TooltipSide.Bottom, IntRect(450, 650, 490, 690))
        assertEquals(TooltipSpot(TooltipSide.Top, IntOffset(420, 612)), placed)
    }

    @Test
    fun itSlidesAlongToStayClearOfTheSides() {
        assertEquals(IntOffset(12, 362), spot(TooltipSide.Top, IntRect(0, 400, 30, 440)).offset)
        assertEquals(IntOffset(888, 362), spot(TooltipSide.Top, IntRect(970, 400, 1000, 440)).offset)
    }

    @Test
    fun besideItFlipsAndSlidesTheSameWay() {
        // Right of a rail's button, its middle level with the button's.
        assertEquals(TooltipSpot(TooltipSide.Right, IntOffset(68, 405)), spot(TooltipSide.Right, IntRect(20, 400, 60, 440)))
        // No room on the right: on the left.
        assertEquals(TooltipSpot(TooltipSide.Left, IntOffset(842, 405)), spot(TooltipSide.Right, IntRect(950, 400, 990, 440)))
        // Near the foot, slid up to stay clear of it.
        assertEquals(IntOffset(68, 658), spot(TooltipSide.Right, IntRect(20, 670, 60, 700)).offset)
    }

    @Test
    fun cutWordsShowWholeUnderTheirStart() {
        assertEquals(TooltipSpot(TooltipSide.Bottom, IntOffset(300, 228)), spot(TooltipSide.Bottom, IntRect(300, 200, 500, 220), start = true))
    }
}

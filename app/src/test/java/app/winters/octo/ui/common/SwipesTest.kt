package app.winters.octo.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipesTest {
    private val slop = 8f

    @Test
    fun nothingIsDecidedInsideTheSlop() {
        assertEquals(DragAxis.Undecided, dragAxis(5f, 5f, slop))
        assertEquals(DragAxis.Undecided, dragAxis(0f, -7.9f, slop))
    }

    @Test
    fun theLargerMovementDecides() {
        assertEquals(DragAxis.Horizontal, dragAxis(9f, 3f, slop))
        assertEquals(DragAxis.Horizontal, dragAxis(-9f, 3f, slop))
        assertEquals(DragAxis.Vertical, dragAxis(3f, 9f, slop))
        assertEquals(DragAxis.Vertical, dragAxis(3f, -9f, slop))
    }

    @Test
    fun aDiagonalCrossesTheSlopBeforeEitherAxisDoes() {
        // Neither side reaches 8 on its own, but the finger has gone 9.2.
        assertEquals(DragAxis.Horizontal, dragAxis(7f, 4.5f, slop))
        assertEquals(DragAxis.Vertical, dragAxis(4.5f, 7f, slop))
    }

    @Test
    fun aTieGoesToThePullDown() {
        assertEquals(DragAxis.Vertical, dragAxis(6f, 6f, slop))
        assertEquals(DragAxis.Vertical, dragAxis(-6f, 6f, slop))
    }

    private val distance = 100f
    private val fling = 1_000f

    @Test
    fun farEnoughSkipsLeftToNextRightToPrevious() {
        assertEquals(SwipeSkip.Next, swipeSkip(-120f, 0f, distance, fling))
        assertEquals(SwipeSkip.Previous, swipeSkip(120f, 0f, distance, fling))
        assertEquals(SwipeSkip.Stay, swipeSkip(-60f, 0f, distance, fling))
    }

    @Test
    fun aFlickSkipsOnlyTheWayItWasGoing() {
        assertEquals(SwipeSkip.Next, swipeSkip(-30f, -1_500f, distance, fling))
        assertEquals(SwipeSkip.Previous, swipeSkip(30f, 1_500f, distance, fling))
        // Flicked back toward the start: a change of mind.
        assertEquals(SwipeSkip.Stay, swipeSkip(-30f, 1_500f, distance, fling))
        assertEquals(SwipeSkip.Stay, swipeSkip(0f, -1_500f, distance, fling))
    }

    @Test
    fun farEnoughStillSkipsWhenLetGoMovingBack() {
        assertEquals(SwipeSkip.Next, swipeSkip(-150f, 300f, distance, fling))
    }

    @Test
    fun onlyAPullUpOpens() {
        assertTrue(swipeUp(-40f, 0f, 30f, fling))
        assertTrue(swipeUp(-10f, -1_200f, 30f, fling))
        assertFalse(swipeUp(-10f, 0f, 30f, fling))
        assertFalse(swipeUp(40f, 0f, 30f, fling))
        assertFalse(swipeUp(10f, -1_200f, 30f, fling))
    }
}

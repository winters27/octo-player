package app.winters.octo.design

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlStatesTest {
    @Test
    fun theScrubberRestsHoversAndDrags() {
        assertEquals(ScrubberState.Rest, scrubberState(hovered = false, dragging = false))
        assertEquals(ScrubberState.Hover, scrubberState(hovered = true, dragging = false))
        assertEquals(ScrubberState.Drag, scrubberState(hovered = false, dragging = true))
        // A drag outlasts the pointer leaving the bar.
        assertEquals(ScrubberState.Drag, scrubberState(hovered = true, dragging = true))
    }

    @Test
    fun theScrubberBarIs6Then12Then16Tall() {
        assertEquals(6.dp, scrubberBarHeight(ScrubberState.Rest))
        assertEquals(12.dp, scrubberBarHeight(ScrubberState.Hover))
        assertEquals(16.dp, scrubberBarHeight(ScrubberState.Drag))
    }

    @Test
    fun theTickIsHalfThenFullThenOneAndAHalfTimesAgain() {
        assertEquals(0.5f, scrubberTickScale(ScrubberState.Rest), 0f)
        assertEquals(1f, scrubberTickScale(ScrubberState.Hover), 0f)
        assertEquals(1.6f, scrubberTickScale(ScrubberState.Drag), 0f)
        assertEquals(4.dp, ScrubberTickWidth)
        assertEquals(16.dp, ScrubberTickHeight)
    }

    @Test
    fun onlyTheEndOfASongSweepsNotASeek() {
        assertTrue(isTrackWrap(previous = 0.99f, now = 0f))
        assertTrue(isTrackWrap(previous = 0.92f, now = 0.05f))
        // Seeking back from the middle, or forward, is not the end.
        assertFalse(isTrackWrap(previous = 0.6f, now = 0.02f))
        assertFalse(isTrackWrap(previous = 0.1f, now = 0.95f))
        assertFalse(isTrackWrap(previous = 0.95f, now = 0.5f))
    }

    // A default switch: 36 wide, an 18 thumb, 2 either side.
    private fun thumb(position: Float, stretch: Float) = switchThumb(track = 36f, thumb = 18f, padding = 2f, position = position, stretch = stretch)

    @Test
    fun theSwitchThumbSitsAtEitherEndAtRest() {
        assertEquals(SwitchThumb(2f, 18f), thumb(position = 0f, stretch = 0f))
        assertEquals(SwitchThumb(16f, 18f), thumb(position = 1f, stretch = 0f))
    }

    @Test
    fun aHeldThumbStretches18PercentFromTheSideItIsLeaving() {
        val off = thumb(position = 0f, stretch = 1f)
        assertEquals(18f * SwitchSquish, off.width, 1e-4f)
        // Off, it keeps its start edge and grows toward on.
        assertEquals(2f, off.x, 1e-4f)
        val on = thumb(position = 1f, stretch = 1f)
        assertEquals(18f * SwitchSquish, on.width, 1e-4f)
        // On, it keeps its end edge and grows toward off.
        assertEquals(34f, on.x + on.width, 1e-4f)
    }

    @Test
    fun theSwitchSizesAreTheSpecs() {
        assertEquals(listOf(30.dp, 18.dp, 14.dp), SwitchSize.Small.let { listOf(it.width, it.height, it.thumb) })
        assertEquals(listOf(36.dp, 22.dp, 18.dp), SwitchSize.Default.let { listOf(it.width, it.height, it.thumb) })
        assertEquals(listOf(44.dp, 26.dp, 22.dp), SwitchSize.Large.let { listOf(it.width, it.height, it.thumb) })
    }

    @Test
    fun theButtonSizesAreTheSpecs() {
        assertEquals(
            listOf(24.dp, 30.dp, 36.dp, 50.dp),
            listOf(ButtonSize.ExtraSmall, ButtonSize.Small, ButtonSize.Medium, ButtonSize.Large).map { it.height },
        )
        assertEquals(
            listOf(20.dp, 24.dp, 28.dp, 40.dp),
            listOf(ButtonSize.ExtraSmall, ButtonSize.Small, ButtonSize.Medium, ButtonSize.Large).map { it.dense },
        )
    }
}

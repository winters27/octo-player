package app.winters.octo.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleFitTest {
    @Test
    fun aTitleWithRoomSharesTheLineWithItsButtons() {
        assertTrue(titleFitsBeside(titleWidth = 400, buttonsWidth = 500, width = 1080))
    }

    @Test
    fun aTitleExactlyFillingTheRoomLeftStillFits() {
        assertTrue(titleFitsBeside(titleWidth = 580, buttonsWidth = 500, width = 1080))
    }

    @Test
    fun aTitleOnePixelTooWideSendsTheButtonsBelow() {
        assertFalse(titleFitsBeside(titleWidth = 581, buttonsWidth = 500, width = 1080))
    }

    @Test
    fun wideButtonsNeverSqueezeTheTitle() {
        assertFalse(titleFitsBeside(titleWidth = 300, buttonsWidth = 900, width = 1080))
    }
}

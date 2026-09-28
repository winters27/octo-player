package app.winters.octo.desktop.search

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniPlaceTest {
    // The sidebar's field: 8 in from the window's left, 224 wide.
    private val field = IntRect(8, 48, 232, 84)

    @Test
    fun theListOpensUnderTheFieldFromItsStartAndReachesOverThePage() {
        val place = omniPanelPlace(field, width = 600, window = 1600, gap = 6, margin = 8)
        assertEquals(IntRect(8, 90, 608, 90), place)
    }

    @Test
    fun aNarrowWindowNarrowsTheListButNeverBelowTheField() {
        assertEquals(492, omniPanelPlace(field, 600, window = 508, gap = 6, margin = 8).width)
        assertEquals(field.width, omniPanelPlace(field, 600, window = 100, gap = 6, margin = 8).width)
    }

    @Test
    fun theRailsFieldFloatsBesideItsButton() {
        assertEquals(IntOffset(72, 48), railFieldPlace(IntRect(8, 48, 56, 84), gap = 16))
    }

    @Test
    fun aClickOnTheFieldTheListOrTheRailButtonKeepsTheListOpen() {
        val box = OmniboxState().apply {
            this.field = IntRect(8, 48, 232, 84)
            panel = IntRect(8, 90, 608, 500)
            trigger = IntRect(0, 600, 10, 610)
        }
        assertTrue(box.holds(100, 60))
        assertTrue(box.holds(500, 300))
        assertTrue(box.holds(5, 605))
        assertFalse("the page beside the list", box.holds(700, 300))
    }
}

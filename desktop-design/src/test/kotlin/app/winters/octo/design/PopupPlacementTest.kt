package app.winters.octo.design

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class PopupPlacementTest {
    private val window = IntSize(1000, 700)
    private val size = IntSize(200, 300)

    @Test
    fun aRightClickMenuOpensDownAndRightOfThePointer() {
        assertEquals(IntOffset(100, 100), placePopupAt(IntOffset(100, 100), null, size, window, margin = 10))
    }

    @Test
    fun nearTheCornerItFlipsUpAndLeft() {
        assertEquals(IntOffset(750, 350), placePopupAt(IntOffset(950, 650), null, size, window, margin = 10))
    }

    @Test
    fun underAControlOnTheFarSideItLinesUpWithTheEnd() {
        val anchor = IntRect(800, 40, 900, 80)
        assertEquals(IntOffset(700, 86), placePopupAt(null, anchor, size, window, margin = 10, gap = 6))
    }

    @Test
    fun aControlNearTheBottomOpensItAbove() {
        val anchor = IntRect(100, 600, 150, 640)
        assertEquals(IntOffset(100, 294), placePopupAt(null, anchor, size, window, margin = 10, gap = 6))
    }

    @Test
    fun withNothingToOpenFromItIsCentred() {
        assertEquals(IntOffset(400, 200), placePopupAt(null, null, size, window, margin = 10))
    }
}

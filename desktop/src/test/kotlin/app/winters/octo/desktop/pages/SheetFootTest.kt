package app.winters.octo.desktop.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.winters.octo.design.Space
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// A sheet's foot keeps Test connection, Cancel and the main button on one
// line while they fit, and gives Test connection a line of its own above the
// two when they do not, the main button keeping its whole width and the
// buttons at the end either way. Red with the foot one Row whatever fits
// (the main button squeezed to what is left, as on Linux at 130%).
@OptIn(ExperimentalComposeUiApi::class)
class SheetFootTest {
    private class Drawn(val test: Rect, val cancel: Rect, val main: Rect)

    // The foot `width` wide, with a 170 wide Test connection, a 100 wide
    // Cancel and a 112 wide main button, each 40 high.
    private fun foot(width: Int): Drawn {
        val at = HashMap<String, Rect>()
        fun Modifier.named(name: String) = onGloballyPositioned { at[name] = it.boundsInRoot() }
        val scene = ImageComposeScene(width, 200, Density(1f)) {
            SheetFoot(Modifier.fillMaxWidth(), lead = { Box(Modifier.size(170.dp, 40.dp).named("test")) }) {
                Box(Modifier.size(100.dp, 40.dp).named("cancel"))
                Box(Modifier.size(112.dp, 40.dp).named("main"))
            }
        }
        try {
            scene.render().close()
        } finally {
            scene.close()
        }
        return Drawn(at.getValue("test"), at.getValue("cancel"), at.getValue("main"))
    }

    @Test
    fun allThreeKeepToOneLineWhileTheyFit() {
        val gap = Space.M.value
        val d = foot((170 + 100 + 112 + 2 * gap).toInt())
        assertEquals(0f, d.test.left)
        assertEquals("one line", d.test.top, d.cancel.top)
        assertEquals(d.cancel.top, d.main.top)
        assertEquals(112f, d.main.width)
        assertEquals(gap, d.cancel.left - d.test.right)
    }

    @Test
    fun testConnectionTakesALineOfItsOwnWhenTheyDoNot() {
        // One short of the 398 the three take on a line.
        val width = 397
        val d = foot(width)
        assertEquals("the main button keeps its width", 112f, d.main.width)
        assertEquals("the main button ends the foot", width.toFloat(), d.main.right)
        assertEquals(Space.M.value, d.main.left - d.cancel.right)
        assertEquals(0f, d.test.left)
        assertTrue("Test connection above the buttons: ${d.test} ${d.cancel}", d.test.bottom <= d.cancel.top)
        assertEquals(Space.M.value, d.cancel.top - d.test.bottom)
    }
}

package app.winters.octo.desktop.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.winters.octo.design.GlazeSegments
import app.winters.octo.design.OctoColors
import app.winters.octo.desktop.OctoLook
import app.winters.octo.design.ProvideWindowLook
import app.winters.octo.design.ScrollbarGutter
import app.winters.octo.discovery.CountryRow
import org.jetbrains.skia.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.SwingUtilities

// The chosen segment's ink and check, where a long list opens, and the
// room a list keeps for its scrollbar.
class SelectionPolishTest {
    private fun picture(width: Int, height: Int, content: @Composable () -> Unit): Bitmap {
        var bitmap: Bitmap? = null
        SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(width, height, Density(1f)) {
                ProvideWindowLook(reduceMotion = true) {
                    Box(Modifier.fillMaxSize().background(OctoColors.Background)) {
                        Box(Modifier.width(width.dp).height(height.dp)) { content() }
                    }
                }
            }
            try {
                repeat(10) { scene.render(System.nanoTime() + it * 50_000_000L).close() }
                bitmap = Bitmap.makeFromImage(scene.render(System.nanoTime() + 800_000_000L))
            } finally {
                scene.close()
            }
        }
        return bitmap!!
    }

    private fun near(expected: Int, actual: Int, within: Int = 6): Boolean {
        fun channel(color: Int, shift: Int) = (color shr shift) and 0xFF
        return listOf(16, 8, 0).all { kotlin.math.abs(channel(expected, it) - channel(actual, it)) <= within }
    }

    private fun anyPixel(bitmap: Bitmap, xs: IntRange, ys: IntRange, test: (Int) -> Boolean) = xs.any { x -> ys.any { y -> test(bitmap.getColor(x, y)) } }

    @Test
    fun theChosenSegmentIsAccentWithDarkInkAndNoWhite() {
        val bitmap = picture(260, 60) {
            Box(Modifier.padding(8.dp)) { GlazeSegments(listOf("Low", "High"), "Low", { it }, {}) }
        }
        // The left cell: the accent pill with the check and the word on it.
        assertTrue(anyPixel(bitmap, 14..70, 16..44) { near(OctoLook.accent.toArgb(), it) })
        assertTrue(anyPixel(bitmap, 14..70, 16..44) { near(OctoLook.onAccent.toArgb(), it, within = 40) })
        assertFalse(anyPixel(bitmap, 14..70, 16..44) { (it and 0xFF) > 235 && ((it shr 8) and 0xFF) > 235 && ((it shr 16) and 0xFF) > 235 })
    }

    @Test
    fun theLongListOpensWithTheChosenCountryInView() {
        val rows = (1..60).map { CountryRow("c$it", "Country $it", null, chosen = it == 52, top = false) }
        val bitmap = picture(392, 360) {
            Column(Modifier.height(360.dp)) { CountryMenu(rows, {}, {}) }
        }
        // The accent fill of the chosen row shows inside the list's window.
        assertTrue(anyPixel(bitmap, 100..300, 60..350) { near(OctoLook.accentSelected.toArgb(), it) })
    }

    @Test
    fun theViewCentersTheChosenLineAndStaysInRange() {
        val view = ChosenView()
        assertNull(view.target(max = 500))
        view.viewport = 300
        view.height = 40
        view.top = 1_000
        assertEquals(870, view.target(max = 2_000))
        assertEquals(500, view.target(max = 500))
        view.top = 10
        assertEquals(0, view.target(max = 500))
        // A list that does not scroll has nowhere to go.
        assertNull(view.target(max = 0))
    }

    @Test
    fun theScrollbarGutterIsItsWidestWidthPlus3() {
        assertEquals(12.dp, ScrollbarGutter)
    }
}

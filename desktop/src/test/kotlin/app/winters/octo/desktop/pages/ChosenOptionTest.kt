package app.winters.octo.desktop.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.winters.octo.design.OctoColors
import app.winters.octo.design.ProvideWindowLook
import app.winters.octo.design.mix
import app.winters.octo.desktop.OctoLook
import org.jetbrains.skia.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.SwingUtilities

// The chosen option of a choice drawn on the desktop: the accent fill and a
// check for the chosen one, nothing for the others; and a section shown on a
// sectioned page, which marks a place and so has the lit fill and no check.
class ChosenOptionTest {
    private val width = 200
    private val height = 36

    // Draws `content` in a 200 by 36 window and returns the picture.
    private fun picture(content: @Composable () -> Unit): Bitmap {
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
                repeat(4) { scene.render(System.nanoTime() + it * 50_000_000L).close() }
                bitmap = Bitmap.makeFromImage(scene.render(System.nanoTime() + 400_000_000L))
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

    // Whether any pixel in the right end of the row is light, as a check is.
    private fun checkShown(bitmap: Bitmap): Boolean =
        (150 until 195).any { x -> (0 until height).any { y -> (bitmap.getColor(x, y) and 0xFF) > 200 && ((bitmap.getColor(x, y) shr 8) and 0xFF) > 200 } }

    private fun section(chosen: Boolean) = picture {
        SectionPick(PageSection("look", "Look") {}, chosen, {}, Modifier.width(width.dp))
    }

    // The accent laid over the page at `alpha`, as the place's fill draws it.
    private fun accentAt(alpha: Float): Int = mix(OctoLook.page, OctoLook.accent, alpha).toArgb()

    // A section on Family and Import marks a place, as Settings' list does:
    // the lit accent fill, fading across, and no check.
    @Test
    fun theSectionShownIsLitWithoutACheck() {
        val bitmap = section(chosen = true)
        assertTrue(near(accentAt(0.22f), bitmap.getColor(4, height / 2), within = 10))
        assertTrue(near(accentAt(0.08f), bitmap.getColor(width - 4, height / 2), within = 10))
        assertFalse("no check marks a place", checkShown(bitmap))
    }

    @Test
    fun anotherSectionHasNeitherFillNorCheck() {
        val bitmap = section(chosen = false)
        assertTrue(near(OctoLook.page.toArgb(), bitmap.getColor(width - 4, height / 2), within = 3))
        assertFalse(checkShown(bitmap))
    }

    private val tile = 120

    // A picture choice, 120 wide, in a 200 by 140 window.
    private fun pictureChoice(chosen: Boolean): Bitmap {
        var bitmap: Bitmap? = null
        SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(width, 140, Density(1f)) {
                ProvideWindowLook(reduceMotion = true) {
                    Box(Modifier.fillMaxSize().background(OctoColors.Background)) {
                        PictureChoice("Glow", chosen, {}, Modifier.width(tile.dp)) { Box(Modifier.fillMaxSize()) }
                    }
                }
            }
            try {
                repeat(4) { scene.render(System.nanoTime() + it * 50_000_000L).close() }
                bitmap = Bitmap.makeFromImage(scene.render(System.nanoTime() + 400_000_000L))
            } finally {
                scene.close()
            }
        }
        return bitmap!!
    }

    // Whether a light check is drawn at the end of the name's line, past
    // the name itself.
    private fun pictureCheck(bitmap: Bitmap): Boolean =
        (tile - 34 until tile - 6).any { x -> (88 until 114).any { y -> (bitmap.getColor(x, y) and 0xFF) > 200 && ((bitmap.getColor(x, y) shr 8) and 0xFF) > 200 } }

    // The chosen picture sits on the accent fill, ringed in the accent,
    // with a check by its name; the others have none of it.
    @Test
    fun theChosenPictureIsTheAccentFillAndRingWithACheck() {
        val chosen = pictureChoice(chosen = true)
        // Between the name and the check, on the tile's own fill.
        assertTrue("the accent fill", near(OctoLook.accentSelected.toArgb(), chosen.getColor(tile / 2, 112)))
        assertTrue("the accent ring", near(OctoLook.accent.toArgb(), chosen.getColor(0, 60), within = 12))
        assertTrue("the check", pictureCheck(chosen))
        val other = pictureChoice(chosen = false)
        assertFalse(near(OctoLook.accentSelected.toArgb(), other.getColor(tile / 2, 112)))
        assertFalse(near(OctoLook.accent.toArgb(), other.getColor(0, 60), within = 12))
        assertFalse(pictureCheck(other))
    }

    @Test
    fun theServerInUseIsTheAccentFillAndOthersAreNot() {
        val used = picture { ChosenFill(true, Modifier.fillMaxSize()) }
        val other = picture { ChosenFill(false, Modifier.fillMaxSize()) }
        assertTrue(near(OctoLook.accentSelected.toArgb(), used.getColor(100, height / 2)))
        assertFalse(near(OctoLook.accentSelected.toArgb(), other.getColor(100, height / 2)))
    }

    @Test
    fun theChosenWordsAreMediumWeight() {
        assertEquals(androidx.compose.ui.text.font.FontWeight.Medium, ChosenWords.fontWeight)
    }
}

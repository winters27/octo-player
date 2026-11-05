package app.winters.octo.desktop.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The live player's stop square sits on whole pixels below 2x: across its
// middle every pixel is fully inked or empty, with the same margin each side.
class StopIconPixelsTest {
    @Test
    fun theStopSquaresEdgesAreSolidBelowTwoTimes() {
        for (density in listOf(1f, 1.25f, 1.5f)) {
            for (size in listOf(20, 27, 30)) {
                val px = Math.round(size * density)
                val scene = ImageComposeScene(px, px, Density(density)) { StopGlyph(Color.White, size = size.dp) }
                val alpha = try {
                    scene.render().toComposeImageBitmap().toPixelMap().buffer.map { it ushr 24 }
                } finally {
                    scene.close()
                }
                for (line in listOf(alpha.subList(px / 2 * px, px / 2 * px + px), (0 until px).map { alpha[it * px + px / 2] })) {
                    val soft = line.count { it in 16..216 }
                    assertEquals("$size dp at $density: soft pixels across the middle", 0, soft)
                    val first = line.indexOfFirst { it > 216 }
                    val last = line.indexOfLast { it > 216 }
                    assertTrue("$size dp at $density: margins $first and ${px - 1 - last}", first == px - 1 - last)
                }
            }
        }
    }

    // At 2x the edges are already fine: the square keeps its exact share.
    @Test
    fun atTwoTimesTheSquareIsAsDrawn() {
        val (inset, side) = stopSquare(80f, 2f)
        assertEquals(49.6f, side, 1e-4f)
        assertEquals(15.2f, inset, 1e-4f)
    }
}

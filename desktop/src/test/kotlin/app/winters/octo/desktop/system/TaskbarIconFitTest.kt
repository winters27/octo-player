package app.winters.octo.desktop.system

import app.winters.octo.design.inkStats
import app.winters.octo.design.loadIcon
import app.winters.octo.design.outlinesOf
import app.winters.octo.design.pixelOutlines
import app.winters.octo.design.rasterize
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

// The taskbar's icons are fitted to the pixels Windows asks for, as the
// app's own icons are: each is the icon's kept fit, never softer than the
// icon as written, and crisper overall.
class TaskbarIconFitTest {
    private val files = listOf("sym_fast_rewind", "sym_play_arrow", "sym_pause", "sym_fast_forward")

    @Test
    fun theTaskbarIconsAreTheFittedIcons() {
        var plainSolid = 0
        var plainInked = 0
        var drawnSolid = 0
        var drawnInked = 0
        for (size in listOf(16, 20, 24, 32)) {
            val pixels = taskbarIconPixels(size, taskbarIconColour(lightTaskbar = false))
            files.forEachIndexed { n, file ->
                val icon = loadIcon(file)
                val alpha = ByteArray(size * size) { pixels[n * size * size * 4 + it * 4 + 3] }
                val kept = rasterize(pixelOutlines(icon, size).drawn, icon.viewportWidth, size)
                val worst = alpha.indices.maxOf { abs((alpha[it].toInt() and 0xFF) - (kept[it].toInt() and 0xFF)) }
                assertTrue("$file at $size px is not its kept fit ($worst)", worst <= 2)
                val plain = inkStats(rasterize(outlinesOf(icon), icon.viewportWidth, size), size)
                val drawn = inkStats(alpha, size)
                assertTrue("$file at $size px is softer", drawn.share >= plain.share - 0.005)
                plainSolid += plain.solid
                plainInked += plain.solid + plain.soft
                drawnSolid += drawn.solid
                drawnInked += drawn.solid + drawn.soft
            }
        }
        val before = plainSolid.toDouble() / plainInked
        val after = drawnSolid.toDouble() / drawnInked
        assertTrue("taskbar solid share %.3f against %.3f as written".format(after, before), after > before + 0.01)
    }
}

package app.winters.octo.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asSkiaPath
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

// Icons fitted to the pixel grid, drawn with Skia as the app draws them:
// never softer than as written, the same ink in the same place, and
// clearly crisper overall at the small sizes.
class PixelFitTest {
    private val sizes = listOf(14, 16, 18, 20, 21, 22, 23, 24, 25, 27, 28, 30)

    // The least gain in solid share, in points, for every listed icon
    // together, at the sizes the desktop draws most.
    private val floors = mapOf(14 to 9, 16 to 6, 18 to 8, 20 to 4, 23 to 1, 27 to 4)

    private val listed = IconList.entries.keys.sorted().map { loadIcon(it) }

    @Test
    fun everyIconIsNeverSofterAndKeepsItsInkAndMiddle() {
        val report = StringBuilder()
        val shares = HashMap<Int, Pair<Double, Double>>()
        for (px in sizes) {
            var plainSolid = 0
            var plainInked = 0
            var drawnSolid = 0
            var drawnInked = 0
            var keptPlain = 0
            for (icon in listed) {
                val outlines = pixelOutlines(icon, px)
                val plain = inkStats(rasterize(outlines.plain, icon.viewportWidth, px), px)
                val drawn = inkStats(rasterize(outlines.drawn, icon.viewportWidth, px), px)
                if (outlines.fitted == null) keptPlain++
                val where = "${icon.name} at $px px"
                assertTrue("$where is softer: ${drawn.share} against ${plain.share}", drawn.share >= plain.share)
                assertTrue("$where changes its ink by ${drawn.ink / plain.ink - 1}", abs(drawn.ink / plain.ink - 1) <= 0.15)
                assertTrue("$where moves its middle across", abs(drawn.middleX - plain.middleX) <= 0.5)
                assertTrue("$where moves its middle down", abs(drawn.middleY - plain.middleY) <= 0.5)
                plainSolid += plain.solid
                plainInked += plain.solid + plain.soft
                drawnSolid += drawn.solid
                drawnInked += drawn.solid + drawn.soft
            }
            val before = 100.0 * plainSolid / plainInked
            val after = 100.0 * drawnSolid / drawnInked
            shares[px] = before to after
            report.append("$px px: solid share plain %.1f%%, fitted %.1f%%, kept plain %d of %d\n".format(before, after, keptPlain, listed.size))
        }
        File("build/icon-fit").mkdirs()
        File("build/icon-fit/shares.txt").writeText(report.toString())
        println(report)
        floors.forEach { (px, points) ->
            val (before, after) = shares.getValue(px)
            assertTrue("at $px px the fitted share %.1f%% is not %d points over %.1f%%".format(after, points, before), after >= before + points)
        }
    }

    // A fit that lands a pixel off moves the ink's middle a whole pixel:
    // the guard draws the icon as written instead.
    @Test
    fun aFitMovedAPixelIsDrawnAsWritten() {
        val px = 18
        val (icon, fitted) = listed.firstNotNullOf { icon -> pixelOutlines(icon, px).fitted?.let { icon to it } }
        val plain = outlinesOf(icon)
        assertNotNull(judged(plain, fitted, icon.viewportWidth, px))
        val pixel = icon.viewportWidth / px
        val moved = fitted.map { outline -> outline.mapValues { i, v -> if (i % 2 == 0) v + pixel else v } }
        assertNull("${icon.name} moved a pixel was kept", judged(plain, moved, icon.viewportWidth, px))
    }

    // At 2x and above an icon is drawn exactly as written, however it is
    // asked for.
    @Test
    fun twoTimesLeavesEveryIconAsWritten() {
        runBlocking {
            IconFits.shared.warm(2f).join()
            // Fits for these pixel sizes at hand, so a draw at 2x would find
            // them if it looked.
            allIcons.forEach { (_, icon) -> icon() }
            IconRecipes.fittable().forEach { (vector, recipe) -> iconSizePxs(2f).forEach { px -> IconFits.shared.ask(vector, recipe, px)?.join() } }
        }
        val crisp = drawAll(2f) { icon, size -> Glyph(icon, size = size) }
        val exact = drawAll(2f) { icon, size -> Image(exactPainter(icon), null, Modifier.size(size), colorFilter = ColorFilter.tint(Color.White)) }
        assertTrue(crisp.contentEquals(exact))
        // At 1x the same sheet is not the same: the fits are drawn there.
        runBlocking { IconFits.shared.warm(1f).join() }
        val fitted = drawAll(1f) { icon, size -> Glyph(icon, size = size) }
        val written = drawAll(1f) { icon, size -> Image(exactPainter(icon), null, Modifier.size(size), colorFilter = ColorFilter.tint(Color.White)) }
        assertTrue(!fitted.contentEquals(written))
    }

    // Once the window's icons are worked out, drawing them works out none.
    @Test
    fun drawingAfterTheWarmUpRastersNothing() {
        runBlocking {
            IconFits.shared.forget().join()
            IconFits.shared.warm(1f).join()
        }
        IconSize.Fitted.forEach { size ->
            allIcons.forEach { (name, icon) ->
                val vector = icon()
                if (IconRecipes.of(vector)?.fits == true) assertTrue("$name at $size is known", IconFits.shared.known(vector, size.value.toInt()))
            }
        }
        val before = fitRasters.get()
        drawAll(1f) { icon, size -> Glyph(icon, size = size) }
        drawAll(1f) { icon, size -> IconAction(icon, "icon", {}, iconSize = size) }
        assertEquals(before, fitRasters.get())
    }

    // Every vector made from a file is known by itself once, however often
    // the icons are drawn again.
    @Test
    fun theRecipesStayTheSameAcrossRecomposition() {
        allIcons.forEach { (name, icon) -> assertNotNull("$name has its recipe", IconRecipes.of(icon())) }
        var round by mutableIntStateOf(0)
        val count = IconRecipes.size
        val scene = ImageComposeScene(400, 400, Density(1f)) {
            val shown = allIcons[round % allIcons.size].second()
            Glyph(shown, size = if (round % 2 == 0) IconSize.Toolbar else IconSize.Table)
        }
        try {
            repeat(12) {
                scene.render(it * 16_000_000L)
                round++
                Snapshot.sendApplyNotifications()
            }
        } finally {
            scene.close()
        }
        assertEquals(count, IconRecipes.size)
    }

    // Back is Forward mirrored, at every size: fitted first, then turned,
    // so every point of one is the other's mirrored across the square's
    // middle, and their pixels mirror too. (Skia smooths a path drawn the
    // other way round a few levels apart on a slant; a fit made after
    // mirroring lands whole pixels apart.)
    @Test
    fun backAndForwardArePixelMirrors() {
        val back = IconRecipes.of(OctoIcons.Back)!!
        val forward = IconRecipes.of(OctoIcons.Forward)!!
        var compared = 0
        for (px in sizes) {
            val b = fittedOutlines(back, px)
            val f = fittedOutlines(forward, px)
            assertEquals("both fitted at $px or neither", b == null, f == null)
            if (b == null || f == null) continue
            val side = back.source.viewportWidth.toDouble()
            b.zip(f).forEach { (left, right) ->
                left.steps.zip(right.steps).forEach { (l, r) ->
                    for (i in l.values.indices) {
                        val mirrored = if (i % 2 == 0) side - r.values[i] else r.values[i]
                        assertEquals("at $px px", mirrored, l.values[i], 1e-6)
                    }
                }
            }
            val left = draw(fitIcon(back, px)!!, px)
            val right = draw(fitIcon(forward, px)!!, px)
            for (y in 0 until px) {
                for (x in 0 until px) {
                    val a = left[y * px + x].toInt() and 0xFF
                    val c = right[y * px + (px - 1 - x)].toInt() and 0xFF
                    assertTrue("at $px px ($x, $y): $a against $c", abs(a - c) <= 8)
                }
            }
            compared++
        }
        assertTrue("fitted at some size", compared > 0)
    }

    // Every icon at every icon size in one sheet, drawn by `content`.
    private fun drawAll(density: Float, content: @Composable (ImageVector, Dp) -> Unit): IntArray {
        val width = (IconSize.Fitted.sumOf { it.value.toInt() + 4 } * density).toInt()
        val height = (allIcons.size * 34 * density).toInt()
        val scene = ImageComposeScene(width, height, Density(density)) {
            Column {
                allIcons.forEach { (_, icon) ->
                    Row { IconSize.Fitted.forEach { size -> content(icon(), size) } }
                }
            }
        }
        try {
            return scene.render().toComposeImageBitmap().toPixelMap().buffer
        } finally {
            scene.close()
        }
    }

    private fun draw(icon: FittedIcon, px: Int): ByteArray {
        val info = ImageInfo.makeA8(px, px)
        val bitmap = Bitmap().apply { allocPixels(info) }
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            isAntiAlias = true
            color = 0xFFFFFFFF.toInt()
        }
        icon.paths.forEach { canvas.drawPath(it.asSkiaPath(), paint) }
        return bitmap.readPixels(info, px, 0, 0)!!
    }
}

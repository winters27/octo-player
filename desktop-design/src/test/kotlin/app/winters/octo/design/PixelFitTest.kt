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
                // A fitted icon's ink is held to its ink as written or to its
                // ink with its lines on whole pixels.
                val whole = wholePixelInk(plain, icon.stroke, icon.viewportWidth, px, IconList.lined(icon.name))
                assertTrue("$where changes its ink by ${drawn.ink / plain.ink - 1}", abs(drawn.ink / plain.ink - 1) <= 0.15 || abs(drawn.ink / whole - 1) <= 0.15)
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

    // No line closes up: where two straight edges face each other at least
    // half a pixel apart as written, across a gap (one line's end, then the
    // next one's start) or across a line (its start, then its end), over
    // the same stretch, the drawn icon keeps them at least a whole pixel
    // apart. The keyboard, whose rows of keys are drawn inside a hole and
    // wound the other way, is fitted at the list's and the cards' sizes.
    @Test
    fun noLineClosesUp() {
        val closed = mutableListOf<String>()
        for (px in sizes) {
            for (icon in listed) {
                val outlines = pixelOutlines(icon, px)
                val fitted = outlines.fitted ?: continue
                val unit = icon.viewportWidth.toDouble() / px
                val g = orientation(outlines.plain)
                for (axis in 0..1) {
                    val found = outlines.plain.zip(fitted).flatMap { (plain, fit) -> straightEdges(plain.steps, fit.steps, axis, inkSides(plain, g)) }
                    for (first in found) {
                        val next = found.filter { it.start != first.start && it.at > first.at && it.overlaps(first, unit) }.minByOrNull { it.at } ?: continue
                        if (found.any { it !== first && it.start == first.start && it.at > first.at && it.at < next.at && it.overlaps(first, unit) }) continue
                        if (next.at - first.at >= unit / 2 && next.drawnAt - first.drawnAt < unit - 1e-6) {
                            val what = if (first.start) "line" else "gap"
                            closed += "${icon.name} at $px px: $what ${"xy"[axis]} %.1f to %.1f drawn %.2f px".format(first.at, next.at, (next.drawnAt - first.drawnAt) / unit)
                        }
                    }
                }
            }
        }
        assertTrue("lines close up:\n" + closed.distinct().joinToString("\n"), closed.isEmpty())
        val keyboard = listed.first { it.name == "sym_keyboard" }
        for (px in listOf(16, 18, 20)) assertNotNull("the keyboard is fitted at $px px", pixelOutlines(keyboard, px).fitted)
    }

    // The guard measures a fit's ink against the icon's with its lines on
    // whole pixels: every icon it now keeps that measuring against the
    // drawing as written refused is an icon of lines whose lines change
    // width on whole pixels, with ink within 15% of that. A fit of the
    // keyboard at 20 px with a gap between its rows closed is refused.
    @Test
    fun theGuardMeasuresInkAgainstWholePixelLines() {
        val gained = mutableListOf<String>()
        for (px in sizes) {
            for (icon in listed) {
                val stroke = icon.stroke ?: continue
                val plain = outlinesOf(icon)
                val fitted = fitOutlines(plain, icon.viewportWidth, px, stroke)
                val before = inkStats(rasterize(plain, icon.viewportWidth, px), px)
                val after = inkStats(rasterize(fitted, icon.viewportWidth, px), px)
                val asWritten = fitKept(before, after)
                val kept = judged(plain, fitted, icon.viewportWidth, px, stroke, IconList.lined(icon.name)) != null
                val width = stroke.toDouble() / icon.viewportWidth * px
                val whole = maxOf(1.0, Math.round(width).toDouble())
                val where = "${icon.name} at $px px"
                if (kept && !asWritten) {
                    gained += where
                    assertTrue("$where is kept but is not an icon of lines", IconList.lined(icon.name))
                    assertTrue("$where is kept though whole pixels do not change its lines ($width px)", abs(whole / width - 1) > 0.05)
                    assertTrue("$where is kept with ink off its whole-pixel lines'", abs(after.ink / (before.ink * whole / width) - 1) <= 0.15)
                }
            }
        }
        println("kept now, refused before: ${gained.size}\n" + gained.joinToString("\n"))
        val keyboard = listed.first { it.name == "sym_keyboard" }
        val px = 20
        val outlines = pixelOutlines(keyboard, px)
        val fitted = assertNotNullAndGet(outlines.fitted)
        val unit = keyboard.viewportWidth.toDouble() / px
        // The second row's top edge moved up a pixel, onto the first row.
        val merged = outlines.plain.zip(fitted).map { (plain, fit) ->
            Outline(
                plain.steps.zip(fit.steps).map { (p, f) -> OutlineStep(f.kind, DoubleArray(f.values.size) { i -> if (i % 2 == 1 && abs(p.values[i] - 120.0) < 0.01) f.values[i] - unit else f.values[i] }) },
                fit.evenOdd,
            )
        }
        assertNull("a fit with a gap closed is refused", judged(outlines.plain, merged, keyboard.viewportWidth, px, keyboard.stroke, true))
    }

    // Fits the old guard kept that this one refuses because the fit would
    // close a gap between lines: each was looked at and is drawn as written
    // at that size.
    private val closesAGap = setOf("sym_error" to 13)

    // Nothing is lost: every fit the guard kept when it measured ink only
    // against the drawing as written is still kept, at every size it
    // fits icons to, apart from the gaps listed above.
    @Test
    fun noFitKeptBeforeIsRefusedNow() {
        val lost = mutableListOf<String>()
        val pxs = (sizes + IconSize.Fitted.map { it.value.toInt() }).distinct().sorted()
        for (px in pxs) {
            for (icon in listed) {
                val stroke = icon.stroke ?: continue
                if (icon.viewportWidth != icon.viewportHeight) continue
                val plain = outlinesOf(icon)
                val fitted = fitOutlines(plain, icon.viewportWidth, px, stroke)
                val before = inkStats(rasterize(plain, icon.viewportWidth, px), px)
                val after = inkStats(rasterize(fitted, icon.viewportWidth, px), px)
                val keptBefore = fitKept(before, after)
                val keptNow = judged(plain, fitted, icon.viewportWidth, px, stroke, IconList.lined(icon.name)) != null
                if (keptBefore && !keptNow) {
                    if ((icon.name to px) in closesAGap) {
                        assertTrue("${icon.name} at $px px is listed as closing a gap", !linesKept(plain, fitted, icon.viewportWidth.toDouble() / px))
                    } else {
                        lost += "${icon.name} at $px px"
                    }
                }
            }
        }
        assertTrue("fits the old guard kept, refused now (${lost.size}):\n" + lost.joinToString("\n"), lost.isEmpty())
    }

    private fun <T : Any> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    // A straight edge along one axis: where it is as written and as drawn,
    // whether the ink lies after it, and what stretch of the other axis it
    // covers.
    private class StraightEdge(val at: Double, val drawnAt: Double, val start: Boolean, val from: Double, val to: Double) {
        fun overlaps(other: StraightEdge, unit: Double): Boolean = minOf(to, other.to) - maxOf(from, other.from) >= unit
    }

    private fun straightEdges(plain: List<OutlineStep>, fitted: List<OutlineStep>, axis: Int, sides: IntArray): List<StraightEdge> {
        val other = 1 - axis
        val out = mutableListOf<StraightEdge>()
        var prev: DoubleArray? = null
        var shape = -1
        for ((step, fit) in plain.zip(fitted)) {
            if (step.kind == OutlineStep.Kind.Move) shape++
            val g = sides.getOrElse(shape.coerceAtLeast(0)) { 1 }
            if (step.kind != OutlineStep.Kind.Move && step.kind != OutlineStep.Kind.Line) {
                if (step.kind == OutlineStep.Kind.Cubic) prev = step.values.copyOfRange(4, 6)
                continue
            }
            val p = step.values
            val q = prev
            if (q != null && step.kind == OutlineStep.Kind.Line && abs(p[axis] - q[axis]) < 0.05 && abs(p[other] - q[other]) > 0.5) {
                val travel = p[other] - q[other]
                val start = if (axis == 0) travel * g < 0 else travel * g > 0
                out += StraightEdge(p[axis], fit.values[axis], start, minOf(p[other], q[other]), maxOf(p[other], q[other]))
            }
            prev = p
        }
        return out
    }

    // A fit that lands a pixel off moves the ink's middle a whole pixel:
    // the guard draws the icon as written instead.
    @Test
    fun aFitMovedAPixelIsDrawnAsWritten() {
        val px = 18
        val (icon, fitted) = listed.firstNotNullOf { icon -> pixelOutlines(icon, px).fitted?.let { icon to it } }
        val plain = outlinesOf(icon)
        val lined = IconList.lined(icon.name)
        assertNotNull(judged(plain, fitted, icon.viewportWidth, px, icon.stroke, lined))
        val pixel = icon.viewportWidth / px
        val moved = fitted.map { outline -> outline.mapValues { i, v -> if (i % 2 == 0) v + pixel else v } }
        assertNull("${icon.name} moved a pixel was kept", judged(plain, moved, icon.viewportWidth, px, icon.stroke, lined))
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

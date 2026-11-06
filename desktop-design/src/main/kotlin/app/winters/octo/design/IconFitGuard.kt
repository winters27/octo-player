package app.winters.octo.design

import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asSkiaPath
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Path
import androidx.compose.ui.graphics.Path as ComposePath
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max

// Whether a fitted icon is kept: both forms are drawn at the pixel size and
// the fit is used only if it is at least as crisp, carries about the same
// ink and leaves the ink's middle where it was. Otherwise the icon is drawn
// as written.

// How many icons have been drawn offscreen to judge a fit, for tests.
internal val fitRasters = AtomicLong()

// The outline as a path, every coordinate times `scale`.
fun Outline.toComposePath(scale: Float): ComposePath {
    val path = ComposePath()
    path.fillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
    for (step in steps) {
        val v = step.values
        when (step.kind) {
            OutlineStep.Kind.Move -> path.moveTo((v[0] * scale).toFloat(), (v[1] * scale).toFloat())
            OutlineStep.Kind.Line -> path.lineTo((v[0] * scale).toFloat(), (v[1] * scale).toFloat())
            OutlineStep.Kind.Cubic -> path.cubicTo(
                (v[0] * scale).toFloat(), (v[1] * scale).toFloat(), (v[2] * scale).toFloat(),
                (v[3] * scale).toFloat(), (v[4] * scale).toFloat(), (v[5] * scale).toFloat(),
            )
            OutlineStep.Kind.Close -> path.close()
        }
    }
    return path
}

// The same as a Skia path, for drawing straight onto a Skia canvas.
fun Outline.toSkiaPath(scale: Float): Path = toComposePath(scale).asSkiaPath()

// The outlines drawn white, smoothed, on a `px` square of coverage only:
// one byte a pixel, rows top down.
fun rasterize(outlines: List<Outline>, viewport: Float, px: Int): ByteArray {
    fitRasters.incrementAndGet()
    val info = ImageInfo.makeA8(px, px)
    val bitmap = Bitmap()
    bitmap.allocPixels(info)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply {
        isAntiAlias = true
        color = 0xFFFFFFFF.toInt()
    }
    // Drawn in the icon's own units under a scale, as a vector is.
    canvas.scale(px / viewport, px / viewport)
    outlines.forEach { outline -> canvas.drawPath(outline.toSkiaPath(1f), paint) }
    val pixels = bitmap.readPixels(info, px, 0, 0) ?: ByteArray(px * px)
    paint.close()
    canvas.close()
    bitmap.close()
    return pixels
}

// What a drawn icon's pixels hold: how many are solid (coverage 0.85 and
// up) and how many soft (inked, under 0.85), its total ink, and the ink's
// middle.
class InkStats(val solid: Int, val soft: Int, val ink: Double, val middleX: Double, val middleY: Double) {
    // The share of inked pixels that are solid.
    val share: Double get() = solid.toDouble() / max(1, solid + soft)
}

fun inkStats(pixels: ByteArray, px: Int): InkStats {
    var solid = 0
    var soft = 0
    var ink = 0.0
    var cx = 0.0
    var cy = 0.0
    for (i in pixels.indices) {
        val a = (pixels[i].toInt() and 0xFF) / 255.0
        if (a >= 0.85) solid++ else if (a > 0.06) soft++
        ink += a
        cx += a * (i % px)
        cy += a * (i / px)
    }
    return InkStats(solid, soft, ink, if (ink > 0) cx / ink else 0.0, if (ink > 0) cy / ink else 0.0)
}

// Whether `fitted` may stand in for `plain` at `px`: no softer, ink within
// 15 percent of its ink as written or of `whole` (the ink the icon has with
// its lines on whole pixels), and the ink's middle moved at most half a
// pixel each way.
fun fitKept(plain: InkStats, fitted: InkStats, whole: Double = plain.ink): Boolean {
    if (plain.ink <= 0.0 || whole <= 0.0) return false
    if (fitted.share < plain.share) return false
    if (abs(fitted.ink / plain.ink - 1) > 0.15 && abs(fitted.ink / whole - 1) > 0.15) return false
    return abs(fitted.middleX - plain.middleX) <= 0.5 && abs(fitted.middleY - plain.middleY) <= 0.5
}

// The ink an icon of lines would have at `px` with each line's width
// rounded to whole pixels (never under one): its ink as written, times the
// whole width over the written one. A filled icon, or one with no line
// width, keeps its ink as written.
fun wholePixelInk(plain: InkStats, stroke: Float?, viewport: Float, px: Int, lined: Boolean): Double {
    if (!lined || stroke == null || viewport <= 0f) return plain.ink
    val width = stroke.toDouble() / viewport * px
    if (width <= 0.0) return plain.ink
    return plain.ink * max(1.0, Math.round(width).toDouble()) / width
}

// The icon's outlines for `px` pixels a side: fitted when the icon has a
// stroke width, its square is square and the fit is kept; otherwise as
// written. Null `fitted` in the answer means it is drawn as written.
class PixelOutlines(val plain: List<Outline>, val fitted: List<Outline>?) {
    val drawn: List<Outline> get() = fitted ?: plain
}

fun pixelOutlines(source: IconSource, px: Int): PixelOutlines {
    val plain = outlinesOf(source)
    val stroke = source.stroke
    if (stroke == null || source.viewportWidth != source.viewportHeight || px <= 0) return PixelOutlines(plain, null)
    val fitted = fitOutlines(plain, source.viewportWidth, px, stroke)
    return PixelOutlines(plain, judged(plain, fitted, source.viewportWidth, px, stroke, IconList.lined(source.name)))
}

// `fitted` when the guard keeps it against `plain`, else null: its ink
// measured against the drawing as written and, for an icon of lines with
// `stroke` given, against its lines on whole pixels; and no line or gap
// between lines closed.
fun judged(plain: List<Outline>, fitted: List<Outline>, viewport: Float, px: Int, stroke: Float? = null, lined: Boolean = false): List<Outline>? {
    val before = inkStats(rasterize(plain, viewport, px), px)
    val after = inkStats(rasterize(fitted, viewport, px), px)
    if (!fitKept(before, after, wholePixelInk(before, stroke, viewport, px, lined))) return null
    return fitted.takeIf { linesKept(plain, it, viewport.toDouble() / px) }
}

// Whether every straight edge facing another at least half a pixel apart
// as written (a gap between lines, or a line's two sides, over the same
// stretch) is still at least a whole pixel from it in `fitted`.
fun linesKept(plain: List<Outline>, fitted: List<Outline>, unit: Double): Boolean {
    if (plain.size != fitted.size) return false
    val g = orientation(plain)
    for (axis in 0..1) {
        val found = plain.zip(fitted).flatMap { (p, f) -> straightEdges(p, f, axis, g) }
        for (first in found) {
            val next = found.filter { it.start != first.start && it.at > first.at && it.overlaps(first, unit) }.minByOrNull { it.at } ?: continue
            if (found.any { it !== first && it.start == first.start && it.at > first.at && it.at < next.at && it.overlaps(first, unit) }) continue
            if (next.at - first.at >= unit / 2 && next.drawnAt - first.drawnAt < unit - 1e-6) return false
        }
    }
    return true
}

// A straight edge along one axis: where it is as written and as drawn,
// whether the ink lies after it, and what stretch of the other axis it
// covers.
internal class StraightEdge(val at: Double, val drawnAt: Double, val start: Boolean, val from: Double, val to: Double) {
    fun overlaps(other: StraightEdge, unit: Double): Boolean = minOf(to, other.to) - maxOf(from, other.from) >= unit
}

// The straight edges along `axis` of one outline, written and drawn.
internal fun straightEdges(plain: Outline, fitted: Outline, axis: Int, g: Int): List<StraightEdge> {
    val sides = inkSides(plain, g)
    val other = 1 - axis
    val out = mutableListOf<StraightEdge>()
    var prev: DoubleArray? = null
    var shape = -1
    for ((step, fit) in plain.steps.zip(fitted.steps)) {
        if (step.kind == OutlineStep.Kind.Move) shape++
        val side = sides.getOrElse(shape.coerceAtLeast(0)) { 1 }
        if (step.kind != OutlineStep.Kind.Move && step.kind != OutlineStep.Kind.Line) {
            if (step.kind == OutlineStep.Kind.Cubic) prev = step.values.copyOfRange(4, 6)
            continue
        }
        val p = step.values
        val q = prev
        if (q != null && step.kind == OutlineStep.Kind.Line && abs(p[axis] - q[axis]) < 0.05 && abs(p[other] - q[other]) > 0.5) {
            val travel = p[other] - q[other]
            val start = if (axis == 0) travel * side < 0 else travel * side > 0
            out += StraightEdge(p[axis], fit.values[axis], start, minOf(p[other], q[other]), maxOf(p[other], q[other]))
        }
        prev = p
    }
    return out
}

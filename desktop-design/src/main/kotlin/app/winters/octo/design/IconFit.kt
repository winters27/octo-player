package app.winters.octo.design

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.addPathNodes
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// Fitting an icon to the pixel grid it is drawn on. Phosphor's lines sit on
// whole pixels only at 32 px; smaller, most of an icon's ink lands half on
// a pixel and shows gray. For one pixel size this moves each straight edge
// (and each curve's flat side) of an icon to a whole pixel, the two sides of
// a line together so every line keeps one width, and moves every point in
// between in proportion, so curves keep their shape and nothing closes up.
// Pure: outlines in, outlines out.

// One step of an outline, in absolute coordinates: a move or a line (to x,
// y), a cubic curve (two control points, then its end) or a close.
class OutlineStep(val kind: Kind, val values: DoubleArray) {
    enum class Kind { Move, Line, Cubic, Close }
}

// One filled path of an icon as steps, with its fill rule.
class Outline(val steps: List<OutlineStep>, val evenOdd: Boolean)

// An icon's paths as moves, lines, cubics and closes only: relative steps
// made absolute, horizontal and vertical lines as lines, quadratic and
// smooth curves as cubics, and arcs as the cubics that trace them.
fun outlinesOf(source: IconSource): List<Outline> = source.paths.map { Outline(normalize(addPathNodes(it.data)), it.evenOdd) }

internal fun normalize(nodes: List<PathNode>): List<OutlineStep> {
    val out = ArrayList<OutlineStep>()
    var x = 0.0
    var y = 0.0
    var startX = 0.0
    var startY = 0.0
    // The last cubic's second control point and the last quadratic's
    // control point, for the smooth curves that mirror them.
    var cubic: DoubleArray? = null
    var quad: DoubleArray? = null
    fun line(toX: Double, toY: Double) {
        x = toX
        y = toY
        out += OutlineStep(OutlineStep.Kind.Line, doubleArrayOf(x, y))
    }
    fun curve(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) {
        out += OutlineStep(OutlineStep.Kind.Cubic, doubleArrayOf(x1, y1, x2, y2, x3, y3))
        x = x3
        y = y3
    }
    fun quadratic(qx: Double, qy: Double, ex: Double, ey: Double) {
        curve(x + 2.0 / 3 * (qx - x), y + 2.0 / 3 * (qy - y), ex + 2.0 / 3 * (qx - ex), ey + 2.0 / 3 * (qy - ey), ex, ey)
    }
    fun arc(rx: Double, ry: Double, phi: Double, large: Boolean, sweep: Boolean, ex: Double, ey: Double) {
        arcCubics(x, y, rx, ry, phi, large, sweep, ex, ey).forEach { out += OutlineStep(OutlineStep.Kind.Cubic, it) }
        x = ex
        y = ey
    }
    for (node in nodes) {
        var nextCubic: DoubleArray? = null
        var nextQuad: DoubleArray? = null
        when (node) {
            is PathNode.MoveTo, is PathNode.RelativeMoveTo -> {
                if (node is PathNode.MoveTo) {
                    x = node.x.toDouble()
                    y = node.y.toDouble()
                } else if (node is PathNode.RelativeMoveTo) {
                    x += node.dx
                    y += node.dy
                }
                startX = x
                startY = y
                out += OutlineStep(OutlineStep.Kind.Move, doubleArrayOf(x, y))
            }
            is PathNode.LineTo -> line(node.x.toDouble(), node.y.toDouble())
            is PathNode.RelativeLineTo -> line(x + node.dx, y + node.dy)
            is PathNode.HorizontalTo -> line(node.x.toDouble(), y)
            is PathNode.RelativeHorizontalTo -> line(x + node.dx, y)
            is PathNode.VerticalTo -> line(x, node.y.toDouble())
            is PathNode.RelativeVerticalTo -> line(x, y + node.dy)
            is PathNode.CurveTo -> {
                nextCubic = doubleArrayOf(node.x2.toDouble(), node.y2.toDouble())
                curve(node.x1.toDouble(), node.y1.toDouble(), node.x2.toDouble(), node.y2.toDouble(), node.x3.toDouble(), node.y3.toDouble())
            }
            is PathNode.RelativeCurveTo -> {
                nextCubic = doubleArrayOf(x + node.dx2, y + node.dy2)
                curve(x + node.dx1, y + node.dy1, x + node.dx2, y + node.dy2, x + node.dx3, y + node.dy3)
            }
            is PathNode.ReflectiveCurveTo, is PathNode.RelativeReflectiveCurveTo -> {
                val c = cubic
                val c1x = if (c != null) 2 * x - c[0] else x
                val c1y = if (c != null) 2 * y - c[1] else y
                val (x2, y2, x3, y3) = if (node is PathNode.ReflectiveCurveTo) {
                    listOf(node.x1.toDouble(), node.y1.toDouble(), node.x2.toDouble(), node.y2.toDouble())
                } else {
                    node as PathNode.RelativeReflectiveCurveTo
                    listOf(x + node.dx1, y + node.dy1, x + node.dx2, y + node.dy2)
                }
                nextCubic = doubleArrayOf(x2, y2)
                curve(c1x, c1y, x2, y2, x3, y3)
            }
            is PathNode.QuadTo -> {
                nextQuad = doubleArrayOf(node.x1.toDouble(), node.y1.toDouble())
                quadratic(node.x1.toDouble(), node.y1.toDouble(), node.x2.toDouble(), node.y2.toDouble())
            }
            is PathNode.RelativeQuadTo -> {
                nextQuad = doubleArrayOf(x + node.dx1, y + node.dy1)
                quadratic(x + node.dx1, y + node.dy1, x + node.dx2, y + node.dy2)
            }
            is PathNode.ReflectiveQuadTo, is PathNode.RelativeReflectiveQuadTo -> {
                val q = quad
                val qx = if (q != null) 2 * x - q[0] else x
                val qy = if (q != null) 2 * y - q[1] else y
                nextQuad = doubleArrayOf(qx, qy)
                if (node is PathNode.ReflectiveQuadTo) {
                    quadratic(qx, qy, node.x.toDouble(), node.y.toDouble())
                } else {
                    node as PathNode.RelativeReflectiveQuadTo
                    quadratic(qx, qy, x + node.dx, y + node.dy)
                }
            }
            is PathNode.ArcTo -> arc(
                node.horizontalEllipseRadius.toDouble(), node.verticalEllipseRadius.toDouble(), node.theta.toDouble(),
                node.isMoreThanHalf, node.isPositiveArc, node.arcStartX.toDouble(), node.arcStartY.toDouble(),
            )
            is PathNode.RelativeArcTo -> arc(
                node.horizontalEllipseRadius.toDouble(), node.verticalEllipseRadius.toDouble(), node.theta.toDouble(),
                node.isMoreThanHalf, node.isPositiveArc, x + node.arcStartDx, y + node.arcStartDy,
            )
            PathNode.Close -> {
                out += OutlineStep(OutlineStep.Kind.Close, DoubleArray(0))
                x = startX
                y = startY
            }
        }
        cubic = nextCubic
        quad = nextQuad
    }
    return out
}

// An SVG arc as cubics, cut and shaped exactly as Compose cuts the arcs of
// a vector's own path data (its PathParser), so the outline traces the
// same curves and Skia smooths them the same way. The last cubic ends
// exactly on the arc's end.
internal fun arcCubics(x0: Double, y0: Double, rx: Double, ry: Double, theta: Double, large: Boolean, sweep: Boolean, x1: Double, y1: Double): List<DoubleArray> {
    if (rx == 0.0 || ry == 0.0) return listOf(doubleArrayOf(x0, y0, x1, y1, x1, y1))
    val out = ArrayList<DoubleArray>()
    composeArc(out, x0, y0, x1, y1, rx, ry, theta, large, sweep)
    out.lastOrNull()?.let {
        it[4] = x1
        it[5] = y1
    }
    return out
}

private fun composeArc(out: MutableList<DoubleArray>, x0: Double, y0: Double, x1: Double, y1: Double, a: Double, b: Double, theta: Double, large: Boolean, sweep: Boolean) {
    val thetaD = Math.toRadians(theta)
    val cosTheta = cos(thetaD)
    val sinTheta = sin(thetaD)
    val x0p = (x0 * cosTheta + y0 * sinTheta) / a
    val y0p = (-x0 * sinTheta + y0 * cosTheta) / b
    val x1p = (x1 * cosTheta + y1 * sinTheta) / a
    val y1p = (-x1 * sinTheta + y1 * cosTheta) / b
    val dx = x0p - x1p
    val dy = y0p - y1p
    val xm = (x0p + x1p) / 2
    val ym = (y0p + y1p) / 2
    val dsq = dx * dx + dy * dy
    if (dsq == 0.0) return
    val disc = 1.0 / dsq - 1.0 / 4.0
    if (disc < 0.0) {
        // The radii are too small to reach: grown just enough.
        val adjust = (sqrt(dsq) / 1.99999).toFloat()
        composeArc(out, x0, y0, x1, y1, a * adjust, b * adjust, theta, large, sweep)
        return
    }
    val s = sqrt(disc)
    val sdx = s * dx
    val sdy = s * dy
    var cx = if (large == sweep) xm - sdy else xm + sdy
    var cy = if (large == sweep) ym + sdx else ym - sdx
    val eta0 = atan2(y0p - cy, x0p - cx)
    val eta1 = atan2(y1p - cy, x1p - cx)
    var turn = eta1 - eta0
    if (sweep != (turn >= 0)) turn += if (turn > 0) -2 * PI else 2 * PI
    cx *= a
    cy *= b
    val tcx = cx
    cx = cx * cosTheta - cy * sinTheta
    cy = tcx * sinTheta + cy * cosTheta
    // Cubics of an eighth of a turn at most.
    val n = ceil(abs(turn * 4 / PI)).toInt()
    var e1x = x0
    var e1y = y0
    var start = eta0
    var ep1x = -a * cosTheta * sin(start) - b * sinTheta * cos(start)
    var ep1y = -a * sinTheta * sin(start) + b * cosTheta * cos(start)
    val per = turn / n
    for (i in 0 until n) {
        val end = start + per
        val sinEnd = sin(end)
        val cosEnd = cos(end)
        val e2x = cx + a * cosTheta * cosEnd - b * sinTheta * sinEnd
        val e2y = cy + a * sinTheta * cosEnd + b * cosTheta * sinEnd
        val ep2x = -a * cosTheta * sinEnd - b * sinTheta * cosEnd
        val ep2y = -a * sinTheta * sinEnd + b * cosTheta * cosEnd
        val tanHalf = tan((end - start) / 2)
        val alpha = sin(end - start) * (sqrt(4 + 3 * tanHalf * tanHalf) - 1) / 3
        out += doubleArrayOf(e1x + alpha * ep1x, e1y + alpha * ep1y, e2x - alpha * ep2x, e2y - alpha * ep2y, e2x, e2y)
        start = end
        e1x = e2x
        e1y = e2y
        ep1x = ep2x
        ep1y = ep2y
    }
}

// The fitting works on Phosphor's 256 square, where its thresholds are set.
private const val GRID = 256.0

// The outlines fitted to `px` pixels a side, for an icon whose square is
// `viewport` units and whose lines are `stroke` units wide.
fun fitOutlines(outlines: List<Outline>, viewport: Float, px: Int, stroke: Float): List<Outline> {
    val toGrid = GRID / viewport
    val onGrid = outlines.map { it.mapValues { _, v -> v * toGrid } }
    val unit = GRID / px
    val ex = HashMap<Double, DoubleArray>()
    val ey = HashMap<Double, DoubleArray>()
    val g = orientation(onGrid)
    for (outline in onGrid) {
        val sides = inkSides(outline, g)
        for ((target, axis) in listOf(ex to 0, ey to 1)) {
            edges(outline.steps, axis, sides).forEach { (k, ws) ->
                val sum = target.getOrPut(k) { DoubleArray(2) }
                sum[0] += ws[0]
                sum[1] += ws[1]
            }
        }
    }
    val gridStroke = stroke * toGrid
    val fx = warp(snaps(ex, unit, gridStroke))
    val fy = warp(snaps(ey, unit, gridStroke))
    return onGrid.map { outline -> outline.mapValues { i, v -> (if (i % 2 == 0) fx(v) else fy(v)) / toGrid } }
}

// The outline with every coordinate passed through `f` (its index in the
// step's values says x when even, y when odd).
internal fun Outline.mapValues(f: (Int, Double) -> Double): Outline =
    Outline(steps.map { step -> OutlineStep(step.kind, DoubleArray(step.values.size) { i -> f(i, step.values[i]) }) }, evenOdd)

// Python's round(v, 2): the nearest hundredth of the exact binary value,
// ties to even.
private fun hundredth(v: Double): Double = BigDecimal(v).setScale(2, RoundingMode.HALF_EVEN).toDouble()

// The edges along one axis (0 across, 1 down): where the outline runs
// straight along the other axis, or turns there (a curve's flat side). Each
// holds its weight (how much it matters: a straight edge its length, a flat
// side 4) and its side, above zero when the ink lies after the edge along
// the axis (a line's first edge) and below when before. `sides` holds, for
// each shape of the outline in order, which side of its way round the ink
// is on, from inkSides.
internal fun edges(steps: List<OutlineStep>, axis: Int, sides: IntArray): Map<Double, DoubleArray> {
    val other = 1 - axis
    val found = LinkedHashMap<Double, DoubleArray>()
    var prev: DoubleArray? = null
    var shape = -1
    fun add(at: Double, weight: Double, travel: Double) {
        val key = hundredth(at)
        val ws = found.getOrPut(key) { DoubleArray(2) }
        val g = sides.getOrElse(shape.coerceAtLeast(0)) { sides.lastOrNull() ?: 1 }
        val start = if (axis == 0) travel * g < 0 else travel * g > 0
        ws[0] += weight
        ws[1] += if (start) weight else -weight
    }
    for (step in steps) {
        val v = step.values
        if (step.kind == OutlineStep.Kind.Move) shape++
        when (step.kind) {
            OutlineStep.Kind.Move, OutlineStep.Kind.Line -> {
                val p = doubleArrayOf(v[0], v[1])
                val q = prev
                if (q != null && step.kind == OutlineStep.Kind.Line && abs(p[axis] - q[axis]) < 0.05 && abs(p[other] - q[other]) > 1) {
                    add(p[axis], abs(p[other] - q[other]), p[other] - q[other])
                }
                prev = p
            }
            OutlineStep.Kind.Cubic -> {
                val p0 = prev
                val p = doubleArrayOf(v[4], v[5])
                if (p0 != null && abs(v[axis] - p0[axis]) < 0.05 && abs(v[other] - p0[other]) > 0.5) add(p0[axis], 4.0, v[other] - p0[other])
                if (abs(v[2 + axis] - p[axis]) < 0.05 && abs(v[2 + other] - p[other]) > 0.5) add(p[axis], 4.0, p[other] - v[2 + other])
                prev = p
            }
            OutlineStep.Kind.Close -> Unit
        }
    }
    return found
}

// Which way the icon winds: the sign of its largest shape's area.
internal fun orientation(outlines: List<Outline>): Int {
    var best = 0.0
    var sign = 1
    for (outline in outlines) {
        for (poly in polygons(outline.steps)) {
            var a = 0.0
            for (i in poly.indices) {
                val p = poly[i]
                val q = poly[(i + 1) % poly.size]
                a += p[0] * q[1] - q[0] * p[1]
            }
            a /= 2
            if (abs(a) > best) {
                best = abs(a)
                sign = if (a > 0) 1 else -1
            }
        }
    }
    return sign
}

// For each shape of the outline (each move starts one), which side of its
// way round the ink lies on: 1 when the ink is on the side a quarter turn
// from its way (down from a step to the right), -1 when on the other. Read
// by trying a point just off the shape's first step against the whole
// outline's fill, so a shape wound against the rest (a bar drawn inside a
// hole, as the keyboard's rows of keys are) still has its ink on the right
// side. A shape whose sides cannot be told apart keeps the icon's `g`.
internal fun inkSides(outline: Outline, g: Int): IntArray {
    val shapes = polygons(outline.steps)
    val moves = outline.steps.count { it.kind == OutlineStep.Kind.Move }
    return IntArray(moves) { index ->
        val poly = shapes.getOrNull(index) ?: return@IntArray g
        val from = poly.indices.firstOrNull { i ->
            val p = poly[i]
            val q = poly[(i + 1) % poly.size]
            abs(q[0] - p[0]) + abs(q[1] - p[1]) > 0.5
        } ?: return@IntArray g
        val p = poly[from]
        val q = poly[(from + 1) % poly.size]
        val length = sqrt((q[0] - p[0]) * (q[0] - p[0]) + (q[1] - p[1]) * (q[1] - p[1]))
        val nx = -(q[1] - p[1]) / length * SIDE_PROBE
        val ny = (q[0] - p[0]) / length * SIDE_PROBE
        val mx = (p[0] + q[0]) / 2
        val my = (p[1] + q[1]) / 2
        val turned = inked(shapes, outline.evenOdd, mx + nx, my + ny)
        val back = inked(shapes, outline.evenOdd, mx - nx, my - ny)
        when {
            turned && !back -> 1
            back && !turned -> -1
            else -> g
        }
    }
}

// How far off a shape's step the fill is tried, in the 256 square.
private const val SIDE_PROBE = 0.5

// Whether a point is inside the outline's fill: its shapes' winding round
// it, by the fill's rule.
private fun inked(shapes: List<List<DoubleArray>>, evenOdd: Boolean, x: Double, y: Double): Boolean {
    var winding = 0
    for (poly in shapes) {
        for (i in poly.indices) {
            val p = poly[i]
            val q = poly[(i + 1) % poly.size]
            if (p[1] <= y) {
                if (q[1] > y && cross(p, q, x, y) > 0) winding++
            } else if (q[1] <= y && cross(p, q, x, y) < 0) {
                winding--
            }
        }
    }
    return if (evenOdd) winding % 2 != 0 else winding != 0
}

private fun cross(p: DoubleArray, q: DoubleArray, x: Double, y: Double): Double = (q[0] - p[0]) * (y - p[1]) - (x - p[0]) * (q[1] - p[1])

// The outline's shapes as points, each curve cut into 16 straight steps.
internal fun polygons(steps: List<OutlineStep>): List<List<DoubleArray>> {
    val res = ArrayList<List<DoubleArray>>()
    var cur = ArrayList<DoubleArray>()
    var x = 0.0
    var y = 0.0
    for (step in steps) {
        val v = step.values
        when (step.kind) {
            OutlineStep.Kind.Move -> {
                if (cur.isNotEmpty()) res += cur
                cur = arrayListOf(doubleArrayOf(v[0], v[1]))
                x = v[0]
                y = v[1]
            }
            OutlineStep.Kind.Line -> {
                cur += doubleArrayOf(v[0], v[1])
                x = v[0]
                y = v[1]
            }
            OutlineStep.Kind.Cubic -> {
                for (k in 1..16) {
                    val t = k / 16.0
                    val u = 1 - t
                    cur += doubleArrayOf(
                        u * u * u * x + 3 * u * u * t * v[0] + 3 * u * t * t * v[2] + t * t * t * v[4],
                        u * u * u * y + 3 * u * u * t * v[1] + 3 * u * t * t * v[3] + t * t * t * v[5],
                    )
                }
                x = v[4]
                y = v[5]
            }
            OutlineStep.Kind.Close -> if (cur.isNotEmpty()) {
                res += cur
                cur = ArrayList()
            }
        }
    }
    if (cur.isNotEmpty()) res += cur
    return res
}

// A thing to place on the grid: a stem (a line's two edges, or a dot's two
// sides) that keeps one width and is placed by its first edge, or a lone
// edge. `ideal` is where its first edge would sit unsnapped.
private class SnapUnit(val edges: DoubleArray, val ideal: Double, val w: Double, val weight: Double) {
    var at = 0.0
}

// Where each edge goes, as (edge, place) pairs in order along the axis.
internal fun snaps(found: Map<Double, DoubleArray>, unit: Double, stroke: Double): List<Pair<Double, Double>> {
    val xs = found.keys.sorted()
    // Rounding here is to the nearest whole, ties to even, as Python's is.
    val width = max(1.0, round(stroke / unit)) * unit
    val used = HashSet<Double>()
    val units = ArrayList<SnapUnit>()
    // Lines first (two edges one stroke apart), then wider stems such as dots.
    for (wide in listOf(false, true)) {
        for ((i, a) in xs.withIndex()) {
            if (a in used || found.getValue(a)[1] <= 0) continue
            for (b in xs.subList(i + 1, xs.size)) {
                if (b in used) continue
                val gap = b - a
                if (gap > stroke * 2.5) break
                if (found.getValue(b)[1] >= 0) continue
                val strokeLike = abs(gap - stroke) <= stroke * 0.3
                if ((strokeLike && !wide) || (wide && gap > stroke * 1.3)) {
                    val w = if (strokeLike) width else max(1.0, round(gap / unit)) * unit
                    units += SnapUnit(doubleArrayOf(a, b), (a + b) / 2 - w / 2, w, found.getValue(a)[0] + found.getValue(b)[0])
                    used += a
                    used += b
                    break
                }
            }
        }
    }
    for (a in xs) if (a !in used) units += SnapUnit(doubleArrayOf(a), a, 0.0, found.getValue(a)[0])
    // Each to its nearest pixel, then the drift evened out: while the
    // weighted average move is over a quarter pixel, the unit whose other
    // rounding cuts it most takes that rounding.
    for (u in units) u.at = round(u.ideal / unit) * unit
    val total = units.sumOf { it.weight }.takeIf { it != 0.0 } ?: 1.0
    for (pass in units.indices) {
        val drift = units.sumOf { (it.at - it.ideal) * it.weight } / total
        if (abs(drift) <= unit / 4) break
        var best: SnapUnit? = null
        var bestAt = 0.0
        var gain = 0.0
        for (u in units) {
            val other = if (drift > 0) u.at - unit else u.at + unit
            if (abs(other - u.ideal) > unit) continue
            val new = drift + (other - u.at) * u.weight / total
            if (abs(drift) - abs(new) > gain) {
                best = u
                bestAt = other
                gain = abs(drift) - abs(new)
            }
        }
        val chosen = best ?: break
        chosen.at = bestAt
    }
    // Mirror pairs: a unit with a twin mirrored about the square's middle
    // is placed as that twin's mirror, so a symmetric icon stays symmetric
    // and centered.
    val taken = HashSet<Int>()
    for ((i, u) in units.withIndex()) {
        if (i in taken) continue
        val span = u.w
        for ((j, v) in units.withIndex()) {
            if (j <= i || j in taken || v.edges.size != u.edges.size || abs(v.w - span) > 1e-6) continue
            if (abs((GRID - u.ideal - span) - v.ideal) < 0.5) {
                v.at = GRID - u.at - span
                taken += i
                taken += j
                break
            }
        }
    }
    val out = HashMap<Double, Double>()
    for (u in units) {
        if (u.edges.size == 2) {
            out[u.edges[0]] = u.at
            out[u.edges[1]] = u.at + u.w
        } else {
            out[u.edges[0]] = u.at
        }
    }
    // The order kept: edges that were apart stay at least a pixel apart, and
    // so does a gap between two lines (one's end, then the next one's start)
    // at least half a pixel wide as written, so no line closes up.
    val fixed = ArrayList<Pair<Double, Double>>()
    for ((a, placed) in out.entries.sortedBy { it.key }) {
        var s = placed
        val last = fixed.lastOrNull()
        if (last != null) {
            val (pa, ps) = last
            val gap = found.getValue(pa)[1] < 0 && found.getValue(a)[1] > 0 && a - pa >= unit / 2
            val apart = a - pa > unit / 2
            // Places are whole pixels, so one under half a pixel past the
            // last is the same pixel, whatever rounding left between them.
            if (apart && s < ps + unit / 2) s = ps + unit
            if (!apart && s < ps) s = ps + (a - pa)
            if (gap && s < ps + unit / 2) s = ps + unit
        }
        fixed += a to s
    }
    return fixed
}

// Every coordinate moved in proportion between the placed edges, and by
// the nearest edge's move beyond them.
internal fun warp(fixed: List<Pair<Double, Double>>): (Double) -> Double {
    if (fixed.isEmpty()) return { it }
    val a = DoubleArray(fixed.size) { fixed[it].first }
    val s = DoubleArray(fixed.size) { fixed[it].second }
    return f@{ t ->
        if (t <= a[0]) return@f t + s[0] - a[0]
        if (t >= a.last()) return@f t + s.last() - a.last()
        for (i in 0 until a.size - 1) {
            if (a[i] <= t && t <= a[i + 1]) return@f s[i] + (t - a[i]) * (s[i + 1] - s[i]) / (a[i + 1] - a[i])
        }
        t
    }
}

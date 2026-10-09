package app.winters.octo.covers

import kotlinx.serialization.Serializable

// The glyph a marked list's cover carries in place of words: a heart for
// Liked Songs, a magnifier for Review, two overlapping squares for
// Duplicates. The shapes are numbers in cover-design.json ("glyphs").
enum class CoverGlyph { Heart, Magnifier, Copies }

// The glyph a playlist row's cover carries: Review and Duplicates by the
// server's notice mark, Liked Songs by its list kind. A notice kind this
// app does not know draws the usual cover.
fun playlistGlyph(octoNotice: String?, octoList: String?): CoverGlyph? = when {
    octoNotice == OCTO_NOTICE_REVIEW -> CoverGlyph.Magnifier
    octoNotice == OCTO_NOTICE_DUPLICATES -> CoverGlyph.Copies
    octoList == OCTO_LIST_LIKED -> CoverGlyph.Heart
    else -> null
}

const val OCTO_NOTICE_REVIEW = "review"
const val OCTO_NOTICE_DUPLICATES = "duplicates"
const val OCTO_LIST_LIKED = "liked"

@Serializable
data class GlyphNumbers(
    val box: Float,
    val veilGrow: Float,
    val shadow: GlyphShadow,
    val heart: HeartNumbers,
    val magnifier: MagnifierNumbers,
    val copies: CopiesNumbers,
)

@Serializable
data class GlyphShadow(val alpha: Float, val offsets: List<Float>)

@Serializable
data class HeartNumbers(val from: List<Float>, val lobe: List<List<Float>>)

@Serializable
data class MagnifierNumbers(val center: List<Float>, val radius: Float, val stroke: Float, val handle: List<List<Float>>, val handleWidth: Float)

@Serializable
data class CopiesNumbers(val side: Float, val radius: Float, val back: List<Float>, val front: List<Float>, val stroke: Float, val gap: Float)

// One filled shape of a glyph, in the cover's pixels.
sealed interface GlyphShape {
    // Bounds as left, top, right, bottom.
    val bounds: FloatArray

    data class Circle(val x: Float, val y: Float, val radius: Float) : GlyphShape {
        override val bounds get() = floatArrayOf(x - radius, y - radius, x + radius, y + radius)
    }

    data class RoundSquare(val left: Float, val top: Float, val side: Float, val radius: Float) : GlyphShape {
        override val bounds get() = floatArrayOf(left, top, left + side, top + side)
    }

    // A line `width` wide with round ends.
    data class Capsule(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val width: Float) : GlyphShape {
        override val bounds get() = (width / 2).let { r -> floatArrayOf(minOf(x0, x1) - r, minOf(y0, y1) - r, maxOf(x0, x1) + r, maxOf(y0, y1) + r) }
    }

    // A closed outline from (x, y) through cubics, each six numbers: two
    // control points and the end.
    data class Curves(val x: Float, val y: Float, val cubics: List<FloatArray>) : GlyphShape {
        override val bounds: FloatArray
            get() {
                val xs = listOf(x) + cubics.flatMap { listOf(it[0], it[2], it[4]) }
                val ys = listOf(y) + cubics.flatMap { listOf(it[1], it[3], it[5]) }
                return floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
            }
    }
}

// A glyph as shapes laid one after another: each `add` joins the outline,
// each cut takes its area away from what is there so far.
data class GlyphStep(val shape: GlyphShape, val add: Boolean)

// A glyph ready to paint: its steps, the box it sits in (left, top, side),
// and its shadow's offsets in pixels and opacity.
data class GlyphDrawing(
    val glyph: CoverGlyph,
    val steps: List<GlyphStep>,
    val box: FloatArray,
    val shadowOffsets: List<Float>,
    val shadowAlpha: Float,
)

// Where a glyph's shapes go on a cover `side` pixels square.
fun glyphDrawing(glyph: CoverGlyph, side: Int, numbers: GlyphNumbers): GlyphDrawing {
    val s = side.toFloat()
    val size = s * numbers.box
    val left = (s - size) / 2
    val top = (s - size) / 2
    fun x(v: Float) = left + v * size
    fun y(v: Float) = top + v * size
    fun d(v: Float) = v * size
    val steps = when (glyph) {
        CoverGlyph.Heart -> {
            val h = numbers.heart
            val (c1, c2, end) = h.lobe
            // The left lobe, then the same mirrored back to the point.
            val left = floatArrayOf(x(c1[0]), y(c1[1]), x(c2[0]), y(c2[1]), x(end[0]), y(end[1]))
            val right = floatArrayOf(x(1 - c2[0]), y(c2[1]), x(1 - c1[0]), y(c1[1]), x(1 - h.from[0]), y(h.from[1]))
            listOf(GlyphStep(GlyphShape.Curves(x(h.from[0]), y(h.from[1]), listOf(left, right)), add = true))
        }
        CoverGlyph.Magnifier -> {
            val m = numbers.magnifier
            val cx = x(m.center[0])
            val cy = y(m.center[1])
            listOf(
                GlyphStep(GlyphShape.Circle(cx, cy, d(m.radius + m.stroke / 2)), add = true),
                GlyphStep(GlyphShape.Circle(cx, cy, d(m.radius - m.stroke / 2)), add = false),
                GlyphStep(GlyphShape.Capsule(x(m.handle[0][0]), y(m.handle[0][1]), x(m.handle[1][0]), y(m.handle[1][1]), d(m.handleWidth)), add = true),
            )
        }
        CoverGlyph.Copies -> {
            val c = numbers.copies
            val half = c.stroke / 2
            fun square(at: List<Float>, grow: Float) =
                GlyphShape.RoundSquare(x(at[0] - grow), y(at[1] - grow), d(c.side + 2 * grow), d((c.radius + grow).coerceAtLeast(0f)))
            listOf(
                GlyphStep(square(c.back, half), add = true),
                GlyphStep(square(c.back, -half), add = false),
                GlyphStep(square(c.front, c.gap), add = false),
                GlyphStep(square(c.front, 0f), add = true),
            )
        }
    }
    return GlyphDrawing(glyph, steps, floatArrayOf(left, top, size), numbers.shadow.offsets.map { it * s }, numbers.shadow.alpha)
}

// The veil's region under a glyph: its box grown about its centre, darkened
// as under a title.
fun glyphVeilRegion(drawing: GlyphDrawing, numbers: GlyphNumbers, book: CoverBook = CoverBook.Default): VeilRegion {
    val (left, top, size) = drawing.box
    val grow = size * numbers.veilGrow / 2
    val v = book.veil.title
    val aim = veilLimit(v.aimContrast) * (1 - book.veil.margin)
    val least = veilLimit(v.minContrast) * (1 - book.veil.margin)
    return VeilRegion(listOf(left - grow, top - grow, left + size + grow, top + size + grow), v.falloff[0], v.falloff[1], aim, least, v.maxDrop)
}

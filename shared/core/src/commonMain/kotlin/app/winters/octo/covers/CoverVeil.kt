package app.winters.octo.covers

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// The colour-keeping veil under a cover's words, as cover-design.json's
// "veil" describes it (tools/cover-art/reference.py is the reference): only
// what is too bright for white words is darkened, by OKLab lightness with
// hue and chroma kept, deep yellows turned toward amber (or green), and
// only as far as each block of words needs.

// A block of words' region: its box in pixels (left, top, right, bottom),
// how far the veil fades out beyond it (shares of the side, across and
// down), the luminance aimed for and the most allowed, and the largest
// share of a pixel's luminance it may take to reach the aim.
data class VeilRegion(
    val box: List<Float>,
    val falloffX: Double,
    val falloffY: Double,
    val aim: Double,
    val least: Double,
    val maxDrop: Double,
)

private val Luma = doubleArrayOf(0.2126, 0.7152, 0.0722)

private fun smoothstep(e0: Double, e1: Double, x: Double): Double {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
    return t * t * (3 - 2 * t)
}

// The most a background's luminance may be for white words at
// `inkOpacity` (laid over it in sRGB, as the apps draw) to reach
// `contrast`, taken on a grey background.
fun veilLimit(contrast: Double, inkOpacity: Double = 1.0): Double {
    if (inkOpacity >= 1.0) return 1.05 / contrast - 0.05
    var lo = 0.0
    var hi = 1.0
    repeat(40) {
        val mid = (lo + hi) / 2
        val bg = linearToChannel(mid)
        val ink = channelToLinear(bg + (1 - bg) * inkOpacity)
        if ((ink + 0.05) / (mid + 0.05) >= contrast) lo = mid else hi = mid
    }
    return lo
}

// The regions for a cover's laid-out words: the title block (name and its
// second line together) from the top corner on the words' side, and the
// foot line from the bottom corner.
fun veilRegions(words: List<CoverWords>, side: Int, book: CoverBook = CoverBook.Default): List<VeilRegion> {
    val s = side.toFloat()
    val veil = book.veil
    val out = mutableListOf<VeilRegion>()
    val block = words.filter { it.role != CoverRole.Footer }
    if (block.isNotEmpty()) {
        val v = veil.title
        val pad = v.pad * s
        val bottom = block.maxOf { it.top + it.height } + pad
        val right = block.first().align == CoverAlign.Right
        val box = if (right) listOf(block.minOf { it.inked[0] } - pad, 0f, s, bottom) else listOf(0f, 0f, block.maxOf { it.inked[2] } + pad, bottom)
        val aim = veilLimit(v.aimContrast) * (1 - veil.margin)
        val least = veilLimit(v.minContrast) * (1 - veil.margin)
        out += VeilRegion(box, v.falloff[0], v.falloff[1], aim, least, v.maxDrop)
    }
    words.firstOrNull { it.role == CoverRole.Footer }?.let { w ->
        val v = veil.footer
        val pad = v.pad * s
        val box = if (w.align == CoverAlign.Right) listOf(w.inked[0] - pad, w.top - pad, s, s) else listOf(0f, w.top - pad, w.inked[2] + pad, s)
        val need = veilLimit(v.contrast, book.layout.footer.opacity.toDouble()) * (1 - veil.margin)
        out += VeilRegion(box, v.falloff[0], v.falloff[1], need, need, 1.0)
    }
    return out
}

// The background's pixels (ARGB, `side` square) with the veil laid in.
fun applyVeil(pixels: IntArray, side: Int, regions: List<VeilRegion>, book: CoverBook = CoverBook.Default): IntArray {
    if (regions.isEmpty()) return pixels.copyOf()
    val n = side * side
    val toLinear = DoubleArray(256) { channelToLinear(it / 255.0) }
    val y = DoubleArray(n) { i ->
        val p = pixels[i]
        Luma[0] * toLinear[p shr 16 and 0xFF] + Luma[1] * toLinear[p shr 8 and 0xFF] + Luma[2] * toLinear[p and 0xFF]
    }
    // Each region keeps a share of a pixel's luminance; shares multiply, so
    // where two regions meet the veil stays smooth.
    val keep = DoubleArray(n) { 1.0 }
    val reach = DoubleArray(n)
    for (r in regions) {
        val (x0, y0, x1, y1) = r.box
        val across = DoubleArray(side) { i -> val c = i + 0.5; max(max(x0 - c, c - x1), 0.0) / (r.falloffX * side) }
        val down = DoubleArray(side) { i -> val c = i + 0.5; max(max(y0 - c, c - y1), 0.0) / (r.falloffY * side) }
        for (row in 0 until side) {
            val dy = down[row]
            if (dy >= 1.0) continue
            for (col in 0 until side) {
                val dx = across[col]
                val w = 1 - smoothstep(0.0, 1.0, sqrt(dx * dx + dy * dy))
                if (w <= 0.0) continue
                val i = row * side + col
                val limit = min(max(r.aim, y[i] * (1 - r.maxDrop)), r.least)
                keep[i] *= 1 - w * (1 - min(1.0, limit / max(y[i], 1e-9)))
                if (w > reach[i]) reach[i] = w
            }
        }
    }
    val yellow = book.veil.yellow
    val (lo, fullLo, fullHi, hi) = yellow.hues
    val lab = DoubleArray(3)
    fun labOf(p: Int) = linearToOklab(toLinear[p shr 16 and 0xFF], toLinear[p shr 8 and 0xFF], toLinear[p and 0xFF], lab)
    fun hueOf() = Math.toDegrees(atan2(lab[2], lab[1])).let { ((it % 360) + 360) % 360 }
    fun yellowness(h: Double, c: Double) =
        smoothstep(lo, fullLo, h) * (1 - smoothstep(fullHi, hi, h)) * (c / yellow.chromaFrom).coerceIn(0.0, 1.0)

    // One direction per cover: where the yellows under the veil lean
    // yellow they deepen to amber, where they lean lime, to green.
    var mass = 0.0
    var weighted = 0.0
    for (i in 0 until n) {
        if (reach[i] <= 0.0) continue
        labOf(pixels[i])
        val c = kotlin.math.hypot(lab[1], lab[2])
        val h = hueOf()
        val m = yellowness(h, c) * c * reach[i]
        mass += m
        weighted += h * m
    }
    val lean = if (mass > 0) weighted / mass else 0.0
    val towards = if (lean <= yellow.split) yellow.towards[0] else yellow.towards[1]

    val out = pixels.copyOf()
    val rgb = DoubleArray(3)
    for (i in 0 until n) {
        val y0 = y[i]
        val target = y0 * keep[i]
        if (target >= y0 - 1e-9) continue
        labOf(pixels[i])
        val light = lab[0]
        val c = kotlin.math.hypot(lab[1], lab[2])
        val h = hueOf()
        val wy = yellowness(h, c)
        val drop = 1 - target / y0
        val share = (yellow.turnPerDrop * drop).coerceIn(0.0, 1.0) * wy
        val h2 = Math.toRadians(h + (towards - h) * share)
        val c2 = c * (1 + yellow.chromaLift * drop * wy)
        val a2 = c2 * cos(h2)
        val b2 = c2 * sin(h2)
        var l2 = light * cbrt(target / y0)
        repeat(book.veil.refine) {
            settle(l2, a2, b2, rgb)
            val got = Luma[0] * rgb[0] + Luma[1] * rgb[1] + Luma[2] * rgb[2]
            l2 *= cbrt(target / max(got, 1e-6))
        }
        settle(l2, a2, b2, rgb)
        fun byte(v: Double) = (linearToChannel(v) * 255).roundToInt().coerceIn(0, 255)
        out[i] = (pixels[i] and (0xFF shl 24)) or (byte(rgb[0]) shl 16) or (byte(rgb[1]) shl 8) or byte(rgb[2])
    }
    return out
}

private fun inGamut(rgb: DoubleArray, slack: Double) = rgb.all { it >= -slack && it <= 1 + slack }

// An OKLab colour as linear sRGB the screen can show: one outside is taken
// toward grey at the same lightness (the largest share of its chroma that
// fits, halving 12 times), blended with a plain clip for colours only just
// outside, so nothing jumps.
internal fun settle(l: Double, a: Double, b: Double, out: DoubleArray) {
    oklabToLinear(l, a, b, out)
    if (inGamut(out, 0.0)) return
    val raw = out.copyOf()
    var lo = 0.0
    var hi = 1.0
    val test = DoubleArray(3)
    repeat(12) {
        val mid = (lo + hi) / 2
        oklabToLinear(l, a * mid, b * mid, test)
        if (inGamut(test, 1e-4)) lo = mid else hi = mid
    }
    oklabToLinear(l, a * lo, b * lo, test)
    val excess = max(-raw.min(), raw.max() - 1)
    val t = (excess / 0.05).coerceIn(0.0, 1.0)
    val w = t * t * (3 - 2 * t)
    for (k in 0..2) out[k] = raw[k].coerceIn(0.0, 1.0) * (1 - w) + test[k].coerceIn(0.0, 1.0) * w
}

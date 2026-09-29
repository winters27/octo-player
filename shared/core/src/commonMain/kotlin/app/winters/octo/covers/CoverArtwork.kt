package app.winters.octo.covers

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

// A list's background from the library: with music, one of the few whose
// strongest hues and lightness are nearest the music's colour (a few, so
// lists of one colour do not all look alike); without, or with music too
// dull to say much, any, from a hash of its id. Always the same one for the same list and music.
fun chooseBackground(
    id: String,
    palette: CoverPalette,
    library: CoverBackgrounds = CoverBackgrounds.Default,
    rule: BackgroundRule = CoverBook.Default.background,
): CoverBackground {
    val all = library.backgrounds
    val pick = coverHash(id) ushr 7
    if (!palette.fromMusic || palette.chroma < rule.lowChromaAsGrey) return all[(pick % all.size).toInt()]
    val near = all.sortedBy { backgroundDistance(it, palette, rule) }.take(rule.nearest)
    return near[(pick % near.size).toInt()]
}

// How far a background is from the music's colour: the hue distance to its
// nearest strong hue (a later, weaker hue counts a little less, a near-grey
// one hardly at all) and how differently vivid that hue is, plus the
// difference in lightness.
internal fun backgroundDistance(background: CoverBackground, palette: CoverPalette, rule: BackgroundRule): Double {
    val hue = background.hues.withIndex().minOfOrNull { (i, h) ->
        val grey = if (h.c < rule.greyBelow) rule.greyPenalty else 0.0
        hueDistance(h.h, palette.hue.toDouble()) / 180.0 + i * rule.hueStep + grey + abs(h.c - palette.chroma) * rule.chromaWeight
    } ?: 1.0
    return hue + abs(background.meanLightness - palette.lightness) * rule.lightnessWeight
}

// Which way a list turns its background, 0 to count - 1: v mod 4 quarter
// turns clockwise, then mirrored left to right from 4 up.
fun coverOrientation(id: String, rule: BackgroundRule = CoverBook.Default.background): Int =
    ((coverHash(id) ushr rule.orientation.shift) % rule.orientation.count).toInt()

// ARGB pixels, `side` square, turned (v mod 4) quarter turns clockwise, then
// mirrored left to right when v >= 4.
fun orientBackground(pixels: IntArray, side: Int, v: Int): IntArray {
    val turns = v % 4
    val mirror = v >= 4
    if (turns == 0 && !mirror) return pixels
    return IntArray(side * side) { i ->
        var x = i % side
        val y = i / side
        if (mirror) x = side - 1 - x
        // Where (x, y) of the turned picture comes from in the original.
        val (sx, sy) = when (turns) {
            1 -> y to side - 1 - x
            2 -> side - 1 - x to side - 1 - y
            3 -> side - 1 - y to x
            else -> x to y
        }
        pixels[sy * side + sx]
    }
}

// A background's pixels (ARGB, `from` square) at `side`: halved, each
// pixel the mean of four rounded ((a + b + c + d + 2) / 4), while that
// still leaves at least `side`, so a 600 cover from the 1200 file is
// exactly the reference's; then each output pixel the mean of the area it
// covers.
fun sampleBackground(pixels: IntArray, from: Int, side: Int): IntArray {
    var current = pixels
    var size = from
    while (side * 2 <= size && size % 2 == 0) {
        current = halve(current, size)
        size /= 2
    }
    return if (size == side) current.copyOf() else areaAverage(current, size, side)
}

private fun halve(pixels: IntArray, size: Int): IntArray {
    val half = size / 2
    return IntArray(half * half) { i ->
        val x = (i % half) * 2
        val y = (i / half) * 2
        val a = pixels[y * size + x]
        val b = pixels[y * size + x + 1]
        val c = pixels[(y + 1) * size + x]
        val d = pixels[(y + 1) * size + x + 1]
        fun ch(shift: Int) = ((a shr shift and 0xFF) + (b shr shift and 0xFF) + (c shr shift and 0xFF) + (d shr shift and 0xFF) + 2) / 4
        (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

// The source pixels an output pixel covers, from `start`, and how much of each.
private class Span(val start: Int, val weights: DoubleArray)

// Each output pixel the mean of the source area it covers, part pixels in
// proportion, across then down.
private fun areaAverage(pixels: IntArray, from: Int, side: Int): IntArray {
    val scale = from.toDouble() / side
    val spans = Array(side) { o ->
        val a = o * scale
        val b = (o + 1) * scale
        val start = floor(a).toInt().coerceIn(0, from - 1)
        val end = min(from, ceil(b).toInt()).coerceAtLeast(start + 1)
        Span(start, DoubleArray(end - start) { k ->
            val lo = maxOf(a, (start + k).toDouble())
            val hi = minOf(b, (start + k + 1).toDouble())
            maxOf(0.0, hi - lo) / (b - a)
        })
    }
    val across = Array(3) { DoubleArray(side * from) }
    for (y in 0 until from) for (x in 0 until side) {
        val span = spans[x]
        var r = 0.0
        var g = 0.0
        var bl = 0.0
        for (k in span.weights.indices) {
            val p = pixels[y * from + span.start + k]
            val w = span.weights[k]
            r += (p shr 16 and 0xFF) * w
            g += (p shr 8 and 0xFF) * w
            bl += (p and 0xFF) * w
        }
        across[0][y * side + x] = r
        across[1][y * side + x] = g
        across[2][y * side + x] = bl
    }
    return IntArray(side * side) { i ->
        val x = i % side
        val span = spans[i / side]
        fun ch(c: Int): Int {
            var v = 0.0
            for (k in span.weights.indices) v += across[c][(span.start + k) * side + x] * span.weights[k]
            return v.roundToInt().coerceIn(0, 255)
        }
        (0xFF shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
    }
}

// A colour standing for a background while its picture is made: its strongest hue.
fun backgroundColour(background: CoverBackground): Int =
    background.hues.firstOrNull()?.let { lchToArgb(it.l, it.c, it.h) } ?: 0xFF202020.toInt()

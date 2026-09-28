package app.winters.octo.player.immersive

import kotlin.math.roundToInt

// The choices the wash's settings offer, on the phone and the desktop: the
// brightness cap, saturation and drift speed in percent, the contrast as a
// factor, and the frame rates it can be held to.
val BrightnessCapRange = 20..100
val SaturationRange = 0..300
val ContrastRange = 0.5f..2f
val FpsChoices = listOf(30, 60, 90, 120)
val SpeedRange = 5..100

// Whether words over a wash made from a cover with this main colour
// should be dark: when the colour, prepared as the covers are, is light.
fun washDarkWords(dominant: Int?, tuning: WashTuning): Boolean =
    dominant != null && washIsLight(prepareColor(dominant, tuning))

// The brightest the wash gets over a prepared cover, as a colour: the
// pixel at the 98th percentile of brightness (every `step`th pixel is
// looked at), dimmed as the final pass leaves it. The wash only mixes,
// blurs and stretches the cover's colours, so it is never brighter than
// its brightest pixels; the last 2% are too few to survive the blur.
fun washPeak(pixels: IntArray, step: Int = 7): Int {
    if (pixels.isEmpty()) return 0xFF000000.toInt()
    val picked = IntArray((pixels.size + step - 1) / step) { pixels[it * step] }
    val light = DoubleArray(picked.size) { relativeLuminance(picked[it]) }
    val line = light.sortedArray()[((picked.size - 1) * 0.98f).roundToInt()]
    val bright = picked[light.indexOfFirst { it == line }]
    val gain = FinalGain.coerceAtMost(1f)
    fun channel(shift: Int) = ((bright shr shift and 0xFF) * gain).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

// One colour laid over another at some opacity, channel by channel, as the
// screen blends them.
fun overlay(top: Int, alpha: Float, under: Int): Int {
    fun channel(shift: Int): Int {
        val t = top shr shift and 0xFF
        val u = under shr shift and 0xFF
        return (u + (t - u) * alpha).roundToInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

// How much of a wash can show over a page's colour (`base`) while words in
// `ink` keep at least `contrast` against the wash's brightest part (`peak`,
// from `washPeak`): the most, up to `most`, that still reads. Words on a
// light wash cannot flip dark on a page the way the player's do, so a
// bright cover shows fainter instead.
fun readableAlpha(peak: Int, base: Int, ink: Int, contrast: Double, most: Float): Float {
    fun reads(alpha: Float) = contrastRatio(ink, overlay(peak, alpha, base)) >= contrast
    if (reads(most)) return most
    if (!reads(0f)) return 0f
    var low = 0f
    var high = most
    repeat(20) {
        val mid = (low + high) / 2f
        if (reads(mid)) low = mid else high = mid
    }
    return low
}

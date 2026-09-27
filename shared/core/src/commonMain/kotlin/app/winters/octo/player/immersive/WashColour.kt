package app.winters.octo.player.immersive

import kotlin.math.pow
import kotlin.math.roundToInt

// How a cover is prepared before the background draws it: contrast, then
// saturation, then a cap on brightness so white covers never glare behind
// the words. Contrast and saturation are factors (1 leaves the colour as
// it is); the cap is a fraction of full brightness.
data class WashTuning(
    val contrast: Float = 1.3f,
    val saturation: Float = 1.8f,
    val brightnessCap: Float = 0.5f,
)

// How much red, green and blue count toward grey.
private const val LumaR = 0.299f
private const val LumaG = 0.587f
private const val LumaB = 0.114f

// Contrast and saturation together, as the 4 x 5 colour matrix Android's
// ColorMatrix takes: channels in 0 to 255, each row one output channel.
// Contrast is (c - 0.5) x contrast + 0.5; saturation then moves each
// colour away from its grey by the saturation factor.
fun washColorMatrix(tuning: WashTuning): FloatArray {
    val c = tuning.contrast
    val s = tuning.saturation
    // The contrast's shift, carried through saturation unchanged, since
    // each saturation row adds up to 1.
    val shift = 127.5f * (1f - c)
    fun row(own: Int): FloatArray {
        val luma = floatArrayOf(LumaR, LumaG, LumaB)
        return FloatArray(5) { j ->
            when (j) {
                in 0..2 -> c * ((1f - s) * luma[j] + if (j == own) s else 0f)
                4 -> shift
                else -> 0f
            }
        }
    }
    return row(0) + row(1) + row(2) + floatArrayOf(0f, 0f, 0f, 1f, 0f)
}

// One colour through a colour matrix, the way the drawing does it: worked
// in floats, then rounded and held to 0 to 255.
fun applyColorMatrix(argb: Int, matrix: FloatArray): Int {
    val a = argb ushr 24 and 0xFF
    val r = argb shr 16 and 0xFF
    val g = argb shr 8 and 0xFF
    val b = argb and 0xFF
    fun channel(row: Int): Int {
        val o = row * 5
        val v = matrix[o] * r + matrix[o + 1] * g + matrix[o + 2] * b + matrix[o + 3] * a + matrix[o + 4]
        return v.roundToInt().coerceIn(0, 255)
    }
    return argb(channel(3), channel(0), channel(1), channel(2))
}

// The brightness cap on one colour: when the mean of its channels is over
// the cap, it is darkened by (mean - cap) / cap of itself.
fun capBrightness(argb: Int, cap: Float): Int {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val mean = (r + g + b) / 3f
    if (cap <= 0f || mean <= cap) return argb
    val keep = (1f - (mean - cap) / cap).coerceIn(0f, 1f)
    return argb(argb ushr 24 and 0xFF, (r * keep * 255f).roundToInt(), (g * keep * 255f).roundToInt(), (b * keep * 255f).roundToInt())
}

// The same cap over a whole picture's pixels, in place.
fun capBrightness(pixels: IntArray, cap: Float) {
    for (i in pixels.indices) pixels[i] = capBrightness(pixels[i], cap)
}

// One colour through every step of the preparation.
fun prepareColor(argb: Int, tuning: WashTuning): Int =
    capBrightness(applyColorMatrix(argb, washColorMatrix(tuning)), tuning.brightnessCap)

// How bright a colour looks, 0 for black to 1 for white, by the sRGB curve.
fun relativeLuminance(argb: Int): Double {
    fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear(argb shr 16 and 0xFF) + 0.7152 * linear(argb shr 8 and 0xFF) + 0.0722 * linear(argb and 0xFF)
}

// How far apart two colours are for reading, from 1 (the same) to 21.
fun contrastRatio(a: Int, b: Int): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

private const val White = 0xFFFFFFFF.toInt()

// Whether white words would struggle on this colour: under 3:1 against
// white and brighter than 0.3.
fun washIsLight(argb: Int): Boolean = contrastRatio(argb, White) < 3.0 && relativeLuminance(argb) > 0.3

private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
    (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

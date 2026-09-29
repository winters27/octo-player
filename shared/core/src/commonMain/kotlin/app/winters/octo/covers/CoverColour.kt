package app.winters.octo.covers

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// A colour as lightness (0 black to 1 white), chroma (0 grey, about 0.3 at
// the most vivid) and hue in degrees, in the OKLCH space, where equal steps
// look like equal steps. Covers are designed in it: a colour keeps its hue
// while its lightness is set to what the design needs.
data class Lch(val l: Double, val c: Double, val h: Double)

private fun linear(channel: Int): Double {
    val c = channel / 255.0
    return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}

private fun encoded(v: Double): Double =
    if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055

fun toLch(argb: Int): Lch {
    val r = linear(argb shr 16 and 0xFF)
    val g = linear(argb shr 8 and 0xFF)
    val b = linear(argb and 0xFF)
    val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    val lightness = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
    val a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
    val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
    val hue = Math.toDegrees(atan2(bb, a)).let { if (it < 0) it + 360 else it }
    return Lch(lightness, hypot(a, bb), hue)
}

// The colour's red, green and blue as light, before the screen's curve;
// outside 0 to 1 when the colour cannot be shown.
private fun linearRgb(l: Double, c: Double, h: Double): DoubleArray {
    val rad = Math.toRadians(h)
    val a = c * cos(rad)
    val b = c * sin(rad)
    val l1 = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
    val m1 = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
    val s1 = (l - 0.0894841775 * a - 1.2914855480 * b).pow(3)
    return doubleArrayOf(
        4.0767416621 * l1 - 3.3077115913 * m1 + 0.2309699292 * s1,
        -1.2684380046 * l1 + 2.6097574011 * m1 - 0.3413193965 * s1,
        -0.0041960863 * l1 - 0.7034186147 * m1 + 1.7076147010 * s1,
    )
}

private fun shown(rgb: DoubleArray) = rgb.all { it in -1e-4..1.0001 }

// The colour at this lightness, chroma and hue as ARGB. A chroma the screen
// cannot show is lowered until it can, keeping the lightness and hue.
fun lchToArgb(l: Double, c: Double, h: Double): Int {
    val lightness = l.coerceIn(0.0, 1.0)
    var rgb = linearRgb(lightness, c, h)
    if (!shown(rgb)) {
        var low = 0.0
        var high = c
        repeat(24) {
            val mid = (low + high) / 2
            if (shown(linearRgb(lightness, mid, h))) low = mid else high = mid
        }
        rgb = linearRgb(lightness, low, h)
    }
    fun byte(v: Double) = (encoded(v.coerceIn(0.0, 1.0)) * 255).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (byte(rgb[0]) shl 16) or (byte(rgb[1]) shl 8) or byte(rgb[2])
}

fun Lch.toArgb(): Int = lchToArgb(l, c, h)

// How far apart two hues are around the circle, 0 to 180 degrees.
fun hueDistance(a: Double, b: Double): Double {
    val d = ((a - b) % 360 + 360) % 360
    return if (d > 180) 360 - d else d
}

// The hue a share `t` of the way from `a` to `b`, the short way round.
fun hueBetween(a: Double, b: Double, t: Double): Double {
    var d = ((b - a) % 360 + 360) % 360
    if (d > 180) d -= 360
    return ((a + d * t) % 360 + 360) % 360
}

// How far apart two colours look, in the same space (0 the same).
fun colourDistance(a: Int, b: Int): Double = distance(toLch(a), toLch(b))

internal fun distance(x: Lch, y: Lch): Double {
    val ra = Math.toRadians(x.h)
    val rb = Math.toRadians(y.h)
    val da = x.c * cos(ra) - y.c * cos(rb)
    val db = x.c * sin(ra) - y.c * sin(rb)
    return kotlin.math.sqrt((x.l - y.l).pow(2) + da * da + db * db)
}

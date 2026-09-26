package app.winters.octo.ambient

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// How strongly the artwork's colours show behind the pages.
enum class AmbientStrength { Off, Subtle, Rich }

// The parts of the app that can wear the artwork's colours.
enum class AmbientArea { Home, Library, Search, Settings }

// Where the ambient colour shows, and how strongly. Subtle everywhere by
// default: the glow was asked for, and at this strength it stays a faint
// wash that Off turns away in one tap.
@Serializable
data class AmbientPrefs(
    val strength: AmbientStrength = AmbientStrength.Subtle,
    val home: Boolean = true,
    val library: Boolean = true,
    val search: Boolean = true,
    val settings: Boolean = true,
    // A faint tint in the floating bar's glass.
    val bar: Boolean = true,
    // Album and artist pages glow with their own artwork, not the song on now.
    val pageArtwork: Boolean = true,
) {
    fun shows(area: AmbientArea): Boolean = strength != AmbientStrength.Off && when (area) {
        AmbientArea.Home -> home
        AmbientArea.Library -> library
        AmbientArea.Search -> search
        AmbientArea.Settings -> settings
    }

    val tintsBar: Boolean get() = strength != AmbientStrength.Off && bar
}

// How much of the colour shows over the app's near-black background.
fun ambientAlpha(strength: AmbientStrength): Float = when (strength) {
    AmbientStrength.Off -> 0f
    AmbientStrength.Subtle -> 0.14f
    AmbientStrength.Rich -> 0.24f
}

// How much of the colour tints the bar's glass: about half the page's, so
// the glass stays glass.
fun barTintAlpha(strength: AmbientStrength): Float = when (strength) {
    AmbientStrength.Off -> 0f
    AmbientStrength.Subtle -> 0.06f
    AmbientStrength.Rich -> 0.10f
}

// Artwork colours are held to this lightness and saturation before they
// glow, so a white or neon cover still comes through as a dark, soft tint.
const val AmbientMaxLightness = 0.5f
const val AmbientMaxSaturation = 0.55f

// The page background the glow sits on, as ARGB. Matches OctoColors.Background.
const val AmbientBase = 0xFF0C0C0D.toInt()

// An artwork colour made safe to glow: the same hue, no brighter and no more
// saturated than the limits above.
fun ambientColor(argb: Int): Int {
    val hsl = toHsl(argb)
    return fromHsl(hsl[0], min(hsl[1], AmbientMaxSaturation), min(hsl[2], AmbientMaxLightness))
}

// A colour laid over another at some opacity, as the screen draws it.
fun composite(argb: Int, alpha: Float, over: Int = AmbientBase): Int {
    fun channel(shift: Int): Int {
        val top = (argb shr shift) and 0xFF
        val bottom = (over shr shift) and 0xFF
        return Math.round(bottom + (top - bottom) * alpha).coerceIn(0, 255)
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

// Relative luminance, as WCAG defines it.
fun luminance(argb: Int): Double {
    fun linear(shift: Int): Double {
        val c = ((argb shr shift) and 0xFF) / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
}

// The WCAG contrast ratio between two colours, from 1 to 21.
fun contrast(a: Int, b: Int): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

private fun toHsl(argb: Int): FloatArray {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val high = max(r, max(g, b))
    val low = min(r, min(g, b))
    val lightness = (high + low) / 2f
    val spread = high - low
    if (spread == 0f) return floatArrayOf(0f, 0f, lightness)
    val saturation = spread / (1f - abs(2f * lightness - 1f))
    val hue = when (high) {
        r -> ((g - b) / spread).mod(6f)
        g -> (b - r) / spread + 2f
        else -> (r - g) / spread + 4f
    } * 60f
    return floatArrayOf(hue, saturation.coerceIn(0f, 1f), lightness)
}

private fun fromHsl(hue: Float, saturation: Float, lightness: Float): Int {
    val chroma = (1f - abs(2f * lightness - 1f)) * saturation
    val x = chroma * (1f - abs((hue / 60f).mod(2f) - 1f))
    val m = lightness - chroma / 2f
    val (r, g, b) = when ((hue / 60f).toInt().coerceIn(0, 5)) {
        0 -> Triple(chroma, x, 0f)
        1 -> Triple(x, chroma, 0f)
        2 -> Triple(0f, chroma, x)
        3 -> Triple(0f, x, chroma)
        4 -> Triple(x, 0f, chroma)
        else -> Triple(chroma, 0f, x)
    }
    fun byte(v: Float) = Math.round((v + m) * 255f).coerceIn(0, 255)
    return (0xFF shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
}

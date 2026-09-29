package app.winters.octo.covers

import kotlin.math.roundToInt
import kotlin.math.sqrt

// The colour of a list's music that picks its cover's background: the hue,
// chroma and lightness (OKLCH) of the strongest colour of its first covers,
// or one from its id when it has none.
class CoverPalette private constructor(
    val hue: Int,
    val chroma: Double,
    val lightness: Double,
    // Whether the colour came from the music's covers.
    val fromMusic: Boolean,
) {
    // The colour as short text, the same for the same colour, for cache keys.
    val key: String get() = "$hue.${(chroma * 1000).roundToInt()}.${(lightness * 1000).roundToInt()}"

    override fun equals(other: Any?) = other is CoverPalette && other.key == key && other.fromMusic == fromMusic

    override fun hashCode() = key.hashCode()

    override fun toString() = "CoverPalette($key, fromMusic=$fromMusic)"

    companion object {
        fun of(hue: Double, chroma: Double, lightness: Double, fromMusic: Boolean): CoverPalette =
            CoverPalette(wrap(hue), round3(chroma.coerceIn(0.0, 0.4)), round3(lightness.coerceIn(0.0, 1.0)), fromMusic)

        // Back from `key`, for a colour kept on disk; null for anything else.
        fun fromKey(key: String, fromMusic: Boolean = true): CoverPalette? {
            val parts = key.split('.').map { it.toIntOrNull() ?: return null }
            if (parts.size != 3) return null
            return of(parts[0].toDouble(), parts[1] / 1000.0, parts[2] / 1000.0, fromMusic)
        }

        private fun wrap(h: Double) = ((h.roundToInt() % 360) + 360) % 360
        private fun round3(v: Double) = (v * 1000).roundToInt() / 1000.0
    }
}

// Colours under this chroma read as grey, and give no hue to work from.
private const val Colourless = 0.035

// A list's colour from its music: the main colours of the covers of its
// first few songs (a list of swatches for each), weighed by how much of its
// cover each colour fills and how vivid it is; the strongest wins. With no
// covers, or only grey ones, a colour of its own from the id (`seed`),
// always the same for the same list.
fun coverPalette(covers: List<List<Swatch>>, seed: String): CoverPalette {
    val withColour = covers.filter { it.isNotEmpty() }
    if (withColour.isEmpty()) return seededPalette(seed)
    class Seen(val lch: Lch, val weight: Double)
    val seen = withColour.flatMap { swatches ->
        swatches.take(4).map { Seen(toLch(it.argb), it.share.toDouble() / withColour.size) }
    }
    val colourful = seen.filter { it.lch.c >= Colourless && it.lch.l in 0.15..0.97 }
    if (colourful.isEmpty()) return seededPalette(seed)
    val first = colourful.maxBy { sqrt(it.weight) * (0.3 + it.lch.c * 5) }
    return CoverPalette.of(first.lch.h, first.lch.c, first.lch.l, fromMusic = true)
}

// A colour from the id alone, for a list with no covers.
fun seededPalette(seed: String): CoverPalette = CoverPalette.of((coverHash(seed) % 360).toDouble(), 0.12, 0.5, fromMusic = false)

// A number from some text, always the same for the same text on every
// device (FNV-1a over its UTF-8), never negative.
fun coverHash(text: String): Long {
    var hash = -0x340d631b7bdddcdbL
    for (byte in text.encodeToByteArray()) {
        hash = hash xor (byte.toLong() and 0xFF)
        hash *= 0x100000001b3L
    }
    return hash ushr 1
}

// The hash well mixed, for picking a background and its turn: FNV-1a alone
// barely changes its middle bits when only an id's last letter differs, so
// ids like "1", "2", "3" all picked alike. The raw 64-bit FNV-1a goes
// through MurmurHash3's finaliser (fmix64), then >>> 1.
fun coverPick(text: String): Long {
    var k = -0x340d631b7bdddcdbL
    for (byte in text.encodeToByteArray()) {
        k = k xor (byte.toLong() and 0xFF)
        k *= 0x100000001b3L
    }
    k = k xor (k ushr 33)
    k *= -0xae502812aa7333L
    k = k xor (k ushr 33)
    k *= -0x3b314601e57a13adL
    k = k xor (k ushr 33)
    return k ushr 1
}

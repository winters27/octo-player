package app.winters.octo.covers

import kotlin.math.roundToInt
import kotlin.math.sqrt

// The colours a playlist's cover is designed from: two hues and how vivid
// each is, taken from its music, or picked from its id when it has none.
// Each part of a design sets its own lightness from them.
class CoverPalette private constructor(
    val hue: Int,
    val chroma: Double,
    val hue2: Int,
    val chroma2: Double,
    // Whether the colours came from the music's covers.
    val fromMusic: Boolean,
) {
    // The first hue at lightness `l`, its chroma scaled by `vivid`.
    fun first(l: Double, vivid: Double = 1.0): Int = lchToArgb(l, chroma * vivid, hue.toDouble())

    fun second(l: Double, vivid: Double = 1.0): Int = lchToArgb(l, chroma2 * vivid, hue2.toDouble())

    // Between the two hues, a share `t` of the way.
    fun between(l: Double, t: Double, vivid: Double = 1.0): Int =
        lchToArgb(l, (chroma + (chroma2 - chroma) * t) * vivid, hueBetween(hue.toDouble(), hue2.toDouble(), t))

    // The palette as short text, the same for the same colours, for cache keys.
    val key: String get() = "$hue.${(chroma * 1000).roundToInt()}.$hue2.${(chroma2 * 1000).roundToInt()}"

    override fun equals(other: Any?) = other is CoverPalette && other.key == key && other.fromMusic == fromMusic

    override fun hashCode() = key.hashCode()

    override fun toString() = "CoverPalette($key, fromMusic=$fromMusic)"

    companion object {
        // Held to these, so a dull cover still gives a rich field and a neon
        // one never glares.
        val FirstChroma = 0.07..0.15
        val SecondChroma = 0.09..0.19

        fun of(hue: Double, chroma: Double, hue2: Double, chroma2: Double, fromMusic: Boolean): CoverPalette = CoverPalette(
            wrap(hue),
            round3(chroma.coerceIn(FirstChroma)),
            wrap(hue2),
            round3(chroma2.coerceIn(SecondChroma)),
            fromMusic,
        )

        // Back from `key`, for a palette kept on disk; null for anything else.
        fun fromKey(key: String, fromMusic: Boolean = true): CoverPalette? {
            val parts = key.split('.').map { it.toIntOrNull() ?: return null }
            if (parts.size != 4) return null
            return of(parts[0].toDouble(), parts[1] / 1000.0, parts[2].toDouble(), parts[3] / 1000.0, fromMusic)
        }

        private fun wrap(h: Double) = ((h.roundToInt() % 360) + 360) % 360
        private fun round3(v: Double) = (v * 1000).roundToInt() / 1000.0
    }
}

// Colours under this chroma read as grey, and give no hue to work from.
private const val Colourless = 0.035

// Hues at least this far apart make a pair worth showing.
private const val PairApart = 28.0

// A playlist's palette from its music: the main colours of the covers of
// its first few songs (a list of swatches for each), weighed by how much of
// its cover each colour fills and how vivid it is. The strongest gives the
// first hue; the strongest of a clearly different hue the second, or a
// neighbour of the first when every cover is one colour. With no covers,
// or only grey ones, a palette of its own from the id (`seed`), always the
// same for the same playlist.
fun coverPalette(covers: List<List<Swatch>>, seed: String): CoverPalette {
    val withColour = covers.filter { it.isNotEmpty() }
    if (withColour.isEmpty()) return seededPalette(seed)
    class Seen(val lch: Lch, val weight: Double)
    val seen = withColour.flatMap { swatches ->
        swatches.take(4).map { Seen(toLch(it.argb), it.share.toDouble() / withColour.size) }
    }
    val colourful = seen.filter { it.lch.c >= Colourless && it.lch.l in 0.15..0.97 }
    if (colourful.isEmpty()) return seededPalette(seed)
    fun score(s: Seen) = sqrt(s.weight) * (0.3 + s.lch.c * 5)
    val first = colourful.maxBy(::score)
    val second = colourful.filter { hueDistance(it.lch.h, first.lch.h) >= PairApart }.maxByOrNull(::score)
    val turn = if ((coverHash(seed) and 1L) == 0L) 38.0 else -38.0
    return CoverPalette.of(
        first.lch.h,
        first.lch.c * 1.15,
        second?.lch?.h ?: (first.lch.h + turn),
        (second?.lch?.c ?: first.lch.c) * 1.2,
        fromMusic = true,
    )
}

// A palette from the id alone: a hue from its hash, and a second one a
// little way round from it.
fun seededPalette(seed: String): CoverPalette {
    val hash = coverHash(seed)
    val hue = (hash % 360).toDouble()
    val step = 30 + (hash ushr 12) % 50
    val turn = if (((hash ushr 20) and 1L) == 0L) step else -step
    return CoverPalette.of(hue, 0.12, hue + turn, 0.16, fromMusic = false)
}

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

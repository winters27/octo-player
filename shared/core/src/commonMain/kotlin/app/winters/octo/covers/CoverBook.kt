package app.winters.octo.covers

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The covers' design as numbers: the layout and the curated gradients, read
// from cover-design.json beside this file. The phone, the desktop and the
// server all draw from that one file, so their covers match.
@Serializable
data class CoverBook(
    val version: Int,
    val fonts: CoverFonts,
    val layout: CoverLayoutNumbers,
    val music: MusicRule,
    val gradients: List<CoverGradient>,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): CoverBook = json.decodeFromString(serializer(), text)

        // The book the apps draw from.
        val Default: CoverBook by lazy {
            val stream = CoverBook::class.java.getResourceAsStream("cover-design.json")
                ?: error("cover-design.json is missing from the build")
            parse(stream.use { it.readBytes().decodeToString() })
        }
    }
}

@Serializable
data class CoverFonts(val family: String, val title: String, val line: String, val footer: String)

@Serializable
data class CoverLayoutNumbers(
    val margin: Float,
    val contrast: Double,
    val tinyBelowPx: Int,
    val tinyMargin: Float,
    val tinySize: Float,
    val title: TitleNumbers,
    val line: LineNumbers,
    val footer: FooterNumbers,
)

@Serializable
data class TitleNumbers(
    val top: Float,
    val weight: Int,
    val size: Float,
    val oneLineDownTo: Float,
    // One line never smaller than this; a small cover wraps instead.
    val oneLineMinPx: Float = 0f,
    val wrapSize: Float,
    // Wrapped lines start at least this big (never over `size`).
    val wrapMinPx: Float = 0f,
    val minSize: Float,
    val minPx: Float,
    val maxLines: Int,
    val lineHeight: Float,
    val tracking: Float,
)

@Serializable
data class LineNumbers(
    val weight: Int,
    val shareOfTitle: Float,
    val minPx: Float,
    val lineHeight: Float,
    val tracking: Float,
    val showFromPx: Int,
)

@Serializable
data class FooterNumbers(
    val weight: Int,
    val size: Float,
    val minPx: Float,
    val bottom: Float,
    val opacity: Float,
    val lineHeight: Float,
    val tracking: Float,
    val showFromPx: Int,
)

@Serializable
data class MusicRule(val maxChroma: Double, val minChroma: Double = 0.0)

// A linear gradient from one point to another, its stops as (place, colour index).
@Serializable
data class CoverBase(val from: List<Float>, val to: List<Float>, val stops: List<List<Float>>)

// A disc of one colour, solid out to (1 - soft) of its radius, then fading
// to nothing at the radius.
@Serializable
data class CoverFold(val centre: List<Float>, val radius: Float, val soft: Float, val colour: Int)

// A faint round light, `opacity` at its centre and nothing at its radius.
@Serializable
data class CoverLight(val centre: List<Float>, val radius: Float, val colour: String, val opacity: Float)

@Serializable
data class CoverGradient(
    val name: String,
    val colours: List<String>,
    val base: CoverBase,
    val folds: List<CoverFold> = emptyList(),
    val light: CoverLight? = null,
)

// "#rrggbb" as opaque ARGB.
fun hexColour(hex: String): Int = (0xFF shl 24) or hex.removePrefix("#").toInt(16)

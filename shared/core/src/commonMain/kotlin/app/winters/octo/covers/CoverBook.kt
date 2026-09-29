package app.winters.octo.covers

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The covers' design as numbers: the layout, the fonts and the veil, read
// from cover-design.json beside this file. The phone, the desktop and the
// server all draw from that one file, so their covers match.
@Serializable
data class CoverBook(
    val version: Int,
    val fonts: CoverFonts,
    val layout: CoverLayoutNumbers,
    val background: BackgroundRule,
    val veil: VeilNumbers,
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

// How music picks a background (see "background" in cover-design.json).
@Serializable
data class BackgroundRule(
    val nearest: Int,
    // Music duller than this picks like a list with no covers: near-grey
    // colours all sat nearest the same few backgrounds.
    val lowChromaAsGrey: Double,
    val hueStep: Double,
    val greyBelow: Double,
    val greyPenalty: Double,
    val chromaWeight: Double,
    val lightnessWeight: Double,
    val orientation: OrientationRule,
)

// How a list turns its background, so lists sharing one do not look like copies.
@Serializable
data class OrientationRule(val shift: Int, val count: Int)

// The veil's numbers (see "veil" in cover-design.json for what each means).
@Serializable
data class VeilNumbers(
    val margin: Double,
    val refine: Int,
    val title: TitleVeil,
    val footer: FooterVeil,
    val yellow: YellowTurn,
)

@Serializable
data class TitleVeil(val pad: Float, val falloff: List<Double>, val aimContrast: Double, val minContrast: Double, val maxDrop: Double)

@Serializable
data class FooterVeil(val pad: Float, val falloff: List<Double>, val contrast: Double)

@Serializable
data class YellowTurn(
    val hues: List<Double>,
    val split: Double,
    val towards: List<Double>,
    val turnPerDrop: Double,
    val chromaFrom: Double,
    val chromaLift: Double,
)

// The library of painted backgrounds, from backgrounds/backgrounds.json:
// each file's strongest hues (OKLCH, strongest first) and mean lightness.
@Serializable
data class CoverBackgrounds(val version: Int, val size: Int, val backgrounds: List<CoverBackground>) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): CoverBackgrounds = json.decodeFromString(serializer(), text)

        val Default: CoverBackgrounds by lazy {
            val stream = CoverBackgrounds::class.java.getResourceAsStream("backgrounds/backgrounds.json")
                ?: error("backgrounds.json is missing from the build")
            parse(stream.use { it.readBytes().decodeToString() })
        }

        // A background's file as stored (WebP, `size` square).
        fun bytes(file: String): ByteArray =
            CoverBackgrounds::class.java.getResourceAsStream("backgrounds/$file")?.use { it.readBytes() }
                ?: error("$file is missing from the covers' backgrounds")
    }
}

@Serializable
data class CoverBackground(
    val file: String,
    val name: String,
    val family: String = "",
    val hues: List<BackgroundHue>,
    val meanLightness: Double,
)

@Serializable
data class BackgroundHue(val l: Double, val c: Double, val h: Double)


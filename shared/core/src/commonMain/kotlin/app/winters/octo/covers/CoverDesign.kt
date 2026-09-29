package app.winters.octo.covers

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// A playlist's cover: one of the painted backgrounds (chosen by the list's
// music), the colour-keeping veil where the words sit, and white words at
// the top left (its name large, a light line under it saying what it is)
// with a small line at the foot. The numbers are in cover-design.json
// (CoverBook), the backgrounds in backgrounds/backgrounds.json.

// What a cover is made from: whose it is (its id), the name, the light line
// under it ("Playlist", "Live list"), the small line at the foot ("12
// songs", "By sam"), and its music's colour.
data class CoverSpec(
    val id: String,
    val name: String,
    val line: String?,
    val footer: String?,
    val palette: CoverPalette,
)

enum class CoverAlign { Left, Right }

// Which words: the name, the line under it, or the foot line.
enum class CoverRole { Title, Line, Footer }

// Words on a cover: which, what, how they are set, the box they are set in,
// how they sit in it, their colour (white, perhaps partly see-through), and
// what they measured.
data class CoverWords(
    val role: CoverRole,
    val text: String,
    val type: CoverType,
    val left: Float,
    val top: Float,
    val width: Float,
    val align: CoverAlign,
    val ink: Int,
    val measured: Measured,
) {
    val height: Float get() = measured.height

    // Where the letters themselves are, as left, top, right, bottom.
    val inked: FloatArray
        get() {
            val w = measured.width.coerceAtMost(width)
            val x = if (align == CoverAlign.Left) left else left + width - w
            return floatArrayOf(x, top, x + w, top + measured.height)
        }
}

// A cover ready to paint: its side in pixels, its background and which way
// it is turned, its words, and the veil's regions under them.
data class CoverPlan(val side: Int, val background: CoverBackground, val orientation: Int, val words: List<CoverWords>, val veil: List<VeilRegion>)

private const val White = 0xFFFFFFFF.toInt()

// Everything about a cover `side` pixels square but its pixels: the words
// measured by the app's own text engine.
fun planCover(
    spec: CoverSpec,
    side: Int,
    setter: CoverTypesetter,
    book: CoverBook = CoverBook.Default,
    library: CoverBackgrounds = CoverBackgrounds.Default,
): CoverPlan {
    val words = coverWords(spec, side, setter, book)
    return CoverPlan(side, chooseBackground(spec.id, spec.palette, library, book.background), coverOrientation(spec.id, book.background), words, veilRegions(words, side, book))
}

// Where the words go.
fun coverWords(spec: CoverSpec, side: Int, setter: CoverTypesetter, book: CoverBook = CoverBook.Default): List<CoverWords> {
    val layout = book.layout
    val s = side.toFloat()
    val rtl = isRightToLeft(spec.name)
    val align = if (rtl) CoverAlign.Right else CoverAlign.Left
    val name = spec.name.trim()
    // A small cover is its artwork alone: at a list's row size the name
    // is beside it anyway.
    if (side < layout.tinyBelowPx) return emptyList()
    val margin = (s * layout.margin).roundToInt().toFloat()
    val width = s - 2 * margin
    val out = mutableListOf<CoverWords>()
    var foot: CoverWords? = null

    // The foot line first, so the name knows how far down it may go.
    var floor = s - margin
    val footerText = spec.footer?.trim()?.takeIf { it.isNotEmpty() && side >= layout.footer.showFromPx }
    if (footerText != null) {
        val f = layout.footer
        val look = CoverType(0f, f.weight, f.tracking, coverLineHeight(coverScript(footerText), f.lineHeight), 1)
        val size = max(f.minPx, s * f.size)
        val fit = fitCoverText(footerText, width, s * 0.2f, 1, f.minPx, size, look, setter)
        val top = s - s * f.bottom - fit.measured.height
        foot = CoverWords(CoverRole.Footer, footerText, fit.type, margin, top, width, align, alpha(White, f.opacity), fit.measured)
        floor = top - s * 0.04f
    }
    if (name.isEmpty()) return listOfNotNull(foot)

    val t = layout.title
    val top = s * t.top
    val lineText = spec.line?.trim()?.takeIf { it.isNotEmpty() && side >= layout.line.showFromPx && !nameSaysWhatItIs(name) }
    val lineRoom = if (lineText == null) 0f else max(s * t.wrapSize, t.wrapMinPx) * layout.line.shareOfTitle * layout.line.lineHeight
    val room = floor - top - lineRoom
    val look = CoverType(0f, t.weight, t.tracking, coverLineHeight(coverScript(name), t.lineHeight), 1)
    val minPx = max(t.minPx, s * t.minSize)
    val oneLineLeast = max(minPx, max(s * t.oneLineDownTo, t.oneLineMinPx))
    val wrapMost = max(minPx, max(s * t.wrapSize, min(t.wrapMinPx, s * t.size)))
    val title = (if (oneLineLeast <= s * t.size) fitOrNull(name, width, room, 1, oneLineLeast, s * t.size, look, setter) else null)
        ?: fitCoverText(name, width, room, t.maxLines, minPx, wrapMost, look, setter)
    out += CoverWords(CoverRole.Title, name, title.type, margin, top, width, align, White, title.measured)

    if (lineText != null) {
        val l = layout.line
        val lineTop = top + title.measured.height
        val most = max(l.minPx, title.type.sizePx * l.shareOfTitle)
        val lineLook = CoverType(0f, l.weight, l.tracking, coverLineHeight(coverScript(lineText), l.lineHeight), 1)
        val fit = fitCoverText(lineText, width, s, 1, min(l.minPx, most), most, lineLook, setter)
        if (lineTop + fit.measured.height <= floor) out += CoverWords(CoverRole.Line, lineText, fit.type, margin, lineTop, width, align, White, fit.measured)
    }
    return out + listOfNotNull(foot)
}

// Whether a name already ends with the word the second line would add ("Road
// Trip Playlist", "Your Mix"), so the cover leaves that line out, as the
// server's covers do. Only a whole last word counts: "Mixtape" does not.
fun nameSaysWhatItIs(name: String): Boolean {
    val last = name.trim().split(Regex("""[^\p{L}\p{N}]+""")).lastOrNull { it.isNotEmpty() } ?: return false
    return last.lowercase() in KindWords
}

private val KindWords = setOf("mix", "mixes", "radio", "radios", "station", "stations", "playlist", "playlists")

// The colour with this opacity.
internal fun alpha(argb: Int, a: Float): Int = ((a.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (argb and 0xFFFFFF)

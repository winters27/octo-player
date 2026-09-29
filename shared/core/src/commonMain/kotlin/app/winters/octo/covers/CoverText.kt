package app.winters.octo.covers

import kotlin.math.floor

// The kinds of writing a cover sets differently: Latin, Greek, Cyrillic
// and the like (words between spaces, tracked tight); Chinese, Japanese
// and Korean (wide, breaking between characters); scripts with marks
// above and below the letters (Arabic, Hebrew, Thai, Devanagari and
// more), which need room between lines; and emoji.
enum class CoverScript { Latin, Wide, Tall, Emoji }

private val WideScripts = setOf(
    Character.UnicodeScript.HAN,
    Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.HANGUL,
    Character.UnicodeScript.BOPOMOFO,
)

private val RightToLeftScripts = setOf(
    Character.UnicodeScript.ARABIC,
    Character.UnicodeScript.HEBREW,
    Character.UnicodeScript.SYRIAC,
    Character.UnicodeScript.THAANA,
    Character.UnicodeScript.NKO,
)

private val SpacedScripts = setOf(
    Character.UnicodeScript.LATIN,
    Character.UnicodeScript.GREEK,
    Character.UnicodeScript.CYRILLIC,
    Character.UnicodeScript.ARMENIAN,
    Character.UnicodeScript.GEORGIAN,
)

internal fun isEmoji(cp: Int): Boolean =
    cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp in 0x1F900..0x1F9FF

private fun codePointsOf(text: String): IntArray = text.codePoints().toArray()

private fun scriptOf(cp: Int): Character.UnicodeScript? =
    runCatching { Character.UnicodeScript.of(cp) }.getOrNull()

// The writing most of the text is in; digits, spaces and marks do not count.
fun coverScript(text: String): CoverScript {
    var latin = 0
    var wide = 0
    var tall = 0
    var emoji = 0
    for (cp in codePointsOf(text)) {
        when {
            isEmoji(cp) -> emoji++
            !Character.isLetter(cp) -> Unit
            scriptOf(cp) in WideScripts -> wide++
            scriptOf(cp) in SpacedScripts -> latin++
            else -> tall++
        }
    }
    val most = maxOf(latin, wide, tall, emoji)
    return when {
        most == 0 || most == latin -> CoverScript.Latin
        most == wide -> CoverScript.Wide
        most == tall -> CoverScript.Tall
        else -> CoverScript.Emoji
    }
}

// Whether the text reads right to left: its first letter decides.
fun isRightToLeft(text: String): Boolean {
    for (cp in codePointsOf(text)) {
        if (Character.isLetter(cp)) return scriptOf(cp) in RightToLeftScripts
    }
    return false
}

// The first character as a reader sees it: a letter with its accents, an
// emoji with its skin tone, flag pair or joined parts.
fun firstGrapheme(text: String): String {
    if (text.isEmpty()) return ""
    var end = Character.charCount(text.codePointAt(0))
    val first = text.codePointAt(0)
    val flag = first in 0x1F1E6..0x1F1FF
    while (end < text.length) {
        val cp = text.codePointAt(end)
        val joins = when {
            cp == 0x200D -> {
                // A joiner takes the character after it too.
                end += Character.charCount(cp)
                if (end < text.length) end += Character.charCount(text.codePointAt(end))
                continue
            }
            Character.getType(cp).let {
                it == Character.NON_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt()
            } -> true
            cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF -> true
            cp in 0x1F3FB..0x1F3FF -> true
            cp in 0xE0020..0xE007F -> true
            flag && cp in 0x1F1E6..0x1F1FF && end == Character.charCount(first) -> true
            else -> false
        }
        if (!joins) break
        end += Character.charCount(cp)
    }
    return text.substring(0, end)
}

// The one character a tiny cover shows: the first letter, number or emoji
// of the name, a capital where the writing has them.
fun monogram(name: String): String {
    val start = name.indexOfFirst { !it.isWhitespace() && (it.isLetterOrDigit() || it.isSurrogate() || it.code >= 0x2600) }
    if (start < 0) return name.trim().take(1)
    val first = firstGrapheme(name.substring(start))
    return if (first.length == 1) first.uppercase() else first
}

// The pieces of the text that cannot be broken across lines: words between
// spaces, while wide characters (Chinese, Japanese, Korean) each stand alone.
fun unbreakableRuns(text: String): List<String> {
    val runs = mutableListOf<String>()
    val word = StringBuilder()
    fun end() {
        if (word.isNotEmpty()) runs += word.toString()
        word.clear()
    }
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val size = Character.charCount(cp)
        when {
            Character.isWhitespace(cp) -> end()
            scriptOf(cp) in WideScripts -> {
                end()
                runs += text.substring(i, i + size)
            }
            else -> word.append(text, i, i + size)
        }
        i += size
    }
    end()
    return runs
}

// How words on a cover are set: size in pixels, weight (600 semibold and
// so on), the space between letters (a share of the size) and between
// lines (times the size), and how many lines at most.
data class CoverType(
    val sizePx: Float,
    val weight: Int,
    val tracking: Float,
    val lineHeight: Float,
    val maxLines: Int,
)

// What the app's text engine found for some words set in a width: how many
// lines, the widest line, the height, and whether some words did not fit.
data class Measured(val lines: Int, val width: Float, val height: Float, val cut: Boolean)

// The app's text engine, as the cover's design needs it.
interface CoverTypesetter {
    // The text wrapped in `width`, at most `type.maxLines` lines.
    fun measure(text: String, type: CoverType, width: Float): Measured

    // The text on one line.
    fun widthOf(text: String, type: CoverType): Float
}

// The space between lines for this writing: at least `wanted`, with room
// for the marks above and below the letters in the scripts that have them.
fun coverLineHeight(script: CoverScript, wanted: Float): Float = when (script) {
    CoverScript.Latin -> wanted
    CoverScript.Wide, CoverScript.Emoji -> maxOf(wanted, 1.15f)
    CoverScript.Tall -> maxOf(wanted, 1.4f)
}

// Words set to fit: the type chosen and what it measured.
data class FittedText(val text: String, val type: CoverType, val measured: Measured)

// The largest size from `minPx` to `maxPx` (whole pixels) at which the text
// fits a box `width` by `height` in at most `maxLines` lines with no word
// broken, or null when even the smallest does not.
fun fitOrNull(
    text: String,
    width: Float,
    height: Float,
    maxLines: Int,
    minPx: Float,
    maxPx: Float,
    look: CoverType,
    setter: CoverTypesetter,
): FittedText? {
    val runs = unbreakableRuns(text)
    fun typeAt(size: Float) = look.copy(sizePx = size, maxLines = maxLines)
    fun fits(size: Float): Measured? {
        val type = typeAt(size)
        if (runs.any { setter.widthOf(it, type) > width }) return null
        val measured = setter.measure(text, type, width)
        return measured.takeIf { !it.cut && it.lines <= maxLines && it.height <= height }
    }
    var low = floor(minPx).coerceAtLeast(1f)
    var high = floor(maxPx).coerceAtLeast(low)
    fits(high)?.let { return FittedText(text, typeAt(high), it) }
    var best: Measured = fits(low) ?: return null
    while (high - low > 1f) {
        val mid = floor((low + high) / 2)
        val measured = fits(mid)
        if (measured != null) {
            low = mid
            best = measured
        } else {
            high = mid
        }
    }
    return FittedText(text, typeAt(low), best)
}

// As fitOrNull, but when nothing fits: the smallest size, with as many
// lines as the box holds and the rest cut.
fun fitCoverText(
    text: String,
    width: Float,
    height: Float,
    maxLines: Int,
    minPx: Float,
    maxPx: Float,
    look: CoverType,
    setter: CoverTypesetter,
): FittedText {
    fitOrNull(text, width, height, maxLines, minPx, maxPx, look, setter)?.let { return it }
    val size = floor(minPx).coerceAtLeast(1f)
    // A word too long for the width even at the smallest is cut on one
    // line rather than broken across two.
    val tooLong = unbreakableRuns(text).any { setter.widthOf(it, look.copy(sizePx = size)) > width }
    val lines = if (tooLong) 1 else (height / (size * look.lineHeight)).toInt().coerceIn(1, maxLines)
    val type = look.copy(sizePx = size, maxLines = lines)
    return FittedText(text, type, setter.measure(text, type, width))
}

package app.winters.octo.lyrics.engine

import app.winters.octo.lyrics.LyricLine
import app.winters.octo.lyrics.LyricWord
import app.winters.octo.lyrics.Lyrics

// A wait between sung lines at least this long shows the interlude dots.
// The dots' own steps take 1.75 s (half a second unseen, half a second to
// fade in, three quarters of a second to go), and one slow breath on top
// needs about 1.5 s more, so anything much under 4 s would only flicker.
const val INTERLUDE_GAP_S = 4.0

// An empty line in the source marks a gap on purpose, so it gets dots with
// less room: just enough for them to appear and go.
const val MARKED_INTERLUDE_MIN_S = 2.0

// How long a last line with no known end is held.
const val LAST_LINE_HOLD_S = 5.0

// Lines some sources put in for who wrote the song, which the view skips.
private val SourceCredit = Regex(
    """^\s*(written by|lyrics by|words by|music by|composed by|composer|lyricist|作词|作詞|作曲|编曲|編曲|词|詞|曲)\s*[:：]""",
    RegexOption.IGNORE_CASE,
)

// Turns a song's lyrics, as any source gave them, into the flat list of
// lines the flowing view draws. Plain lyrics give nothing: the view is for
// synced ones.
object LyricMapper {
    fun map(lyrics: Lyrics, writers: List<String> = emptyList()): List<SyncLine> {
        if (!lyrics.synced || lyrics.instrumental) return emptyList()
        val source = lyrics.lines.sortedBy { it.startMs }.filterNot { SourceCredit.containsMatchIn(it.text) && it.words.isEmpty() }
        val out = mutableListOf<SyncLine>()
        // The last lead or background line, whose end a short wait stretches to meet the next.
        var lastSung = -1
        // Whether an interlude was just added, so no second one goes after it.
        var afterInterlude = false

        source.forEachIndexed { index, line ->
            val nextStart = source.getOrNull(index + 1)?.startMs
            if (line.isGap) {
                val next = source.drop(index + 1).firstOrNull { !it.isGap } ?: return@forEachIndexed
                val start = seconds(line.startMs)
                val end = seconds(next.startMs)
                if (end - start >= MARKED_INTERLUDE_MIN_S) {
                    if (lastSung >= 0) out[lastSung] = stretched(out[lastSung], start)
                    out += interlude(start, end, next)
                    afterInterlude = true
                }
                return@forEachIndexed
            }

            // A line only backing voices sing, whichever way the source put it.
            val backingOnly = line.background || line.text.isBlank()
            val main = when {
                line.background -> backing(line.text, line.words, line, nextStart)
                line.text.isBlank() -> backing(line.backingText, line.backing, line, nextStart)
                else -> lead(line, nextStart)
            }
            if (!afterInterlude) {
                val waitFrom = if (lastSung >= 0) out[lastSung].end else 0.0
                val wait = main.start - waitFrom
                if (wait >= INTERLUDE_GAP_S) {
                    out += interlude(waitFrom, main.start, line)
                } else if (lastSung >= 0) {
                    out[lastSung] = stretched(out[lastSung], main.start)
                }
            }
            afterInterlude = false
            out += main
            lastSung = out.lastIndex
            if (!backingOnly && line.backingText.isNotBlank()) {
                out += backing(line.backingText, line.backing, line, nextStart).let {
                    // Backing vocals take the side of the voice they back.
                    it.copy(isDuet = main.isDuet, start = maxOf(it.start, main.start))
                }
            }
        }

        if (writers.isNotEmpty() && out.isNotEmpty()) {
            val last = out.maxOf { it.end }
            out += SyncLine(
                index = 0,
                text = "Written by ${names(writers)}",
                words = emptyList(),
                start = last,
                end = Double.POSITIVE_INFINITY,
                isCredit = true,
            )
        }
        return out.mapIndexed { index, line -> line.copy(index = index) }
    }

    private fun lead(line: LyricLine, nextStartMs: Long?): SyncLine {
        val text = line.text.trim()
        val end = seconds(lineEndMs(line, nextStartMs))
        val words = timedWords(text, line.words, end)
        val start = seconds(line.startMs)
        return SyncLine(
            index = 0,
            text = text,
            words = words ?: listOf(SyncWord(text, start, end, trailingSpace = true)),
            start = start,
            end = end,
            rtl = isRtl(text),
            translation = line.translation?.trim()?.takeUnless { it.isEmpty() || sameText(it, text) },
            romanization = line.romanization?.trim()
                ?.takeUnless { it.isEmpty() || sameText(it, text) || (line.translation != null && sameText(it, line.translation)) },
            isDuet = line.duet,
            isLineTimed = words == null,
        )
    }

    // A backing vocal line, its brackets gone.
    private fun backing(rawText: String, rawWords: List<LyricWord>, line: LyricLine, nextStartMs: Long?): SyncLine {
        val lineEnd = seconds(lineEndMs(line, nextStartMs))
        val timed = timedWords(rawText.trim(), rawWords, lineEnd)
        val text = withoutBrackets(rawText)
        val words = timed?.let(::wordsWithoutBrackets)?.takeIf { it.isNotEmpty() }
        val start = words?.first()?.start ?: seconds(line.startMs)
        val end = words?.maxOf { it.end } ?: lineEnd
        return SyncLine(
            index = 0,
            text = text,
            words = words ?: listOf(SyncWord(text, start, end, trailingSpace = true)),
            start = start,
            end = end,
            rtl = isRtl(text),
            isBackground = true,
            isDuet = line.duet,
            isLineTimed = words == null,
        )
    }

    // An interlude, set on the side of the line that follows it.
    private fun interlude(start: Double, end: Double, next: LyricLine): SyncLine {
        val text = next.text.ifBlank { next.backingText }
        return SyncLine(
            index = 0,
            text = "",
            words = emptyList(),
            start = start,
            end = end,
            rtl = isRtl(text),
            isInterlude = true,
            isDuet = next.duet,
        )
    }
}

// A line's words, or null when it has none or they do not cover its text
// (then the line is shown timed by line). Syllables of one word stay
// together: only a piece followed by a space, or the last, ends a group.
internal fun timedWords(text: String, words: List<LyricWord>, lineEnd: Double): List<SyncWord>? {
    if (words.isEmpty()) return null
    val letters = text.filterNot(Char::isWhitespace)
    if (words.joinToString("") { it.text }.filterNot(Char::isWhitespace) != letters) return null
    val kept = words.withIndex().filter { it.value.text.isNotBlank() }
    return kept.mapIndexed { position, (index, word) ->
        val next = kept.getOrNull(position + 1)?.value
        val start = seconds(word.startMs)
        val end = word.endMs?.let(::seconds) ?: words.getOrNull(index + 1)?.startMs?.let(::seconds) ?: lineEnd
        val between = if (next != null) text.substring(word.to.coerceIn(0, text.length), next.from.coerceIn(word.to.coerceIn(0, text.length), text.length)) else ""
        val spaced = next == null ||
            word.text.last().isWhitespace() ||
            next.text.first().isWhitespace() ||
            between.any(Char::isWhitespace)
        SyncWord(word.text.trim(), start, maxOf(start, end), trailingSpace = spaced)
    }
}

// When a line ends: its own end, its last word's, or the next line's start.
private fun lineEndMs(line: LyricLine, nextStartMs: Long?): Long =
    line.endMs ?: line.words.lastOrNull()?.endMs ?: nextStartMs ?: (line.startMs + (LAST_LINE_HOLD_S * 1000).toLong())

// A line held on until `until`, so the focus never goes dark in a short
// wait. Lines that overlap the next are left as they are.
private fun stretched(line: SyncLine, until: Double): SyncLine = if (line.end < until) line.copy(end = until) else line

// Whether a line reads right to left, from its first letter with a
// direction: Arabic, Hebrew, Syriac, Thaana, N'Ko and the like do.
fun isRtl(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        val point = text.codePointAt(i)
        when (Character.getDirectionality(point)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
        }
        i += Character.charCount(point)
    }
    return false
}

// The same words, ignoring spaces and case.
internal fun sameText(a: String, b: String): Boolean =
    a.filterNot(Char::isWhitespace).lowercase() == b.filterNot(Char::isWhitespace).lowercase()

private val Opening = charArrayOf('(', '（')
private val Closing = charArrayOf(')', '）')

// "(ooh yeah)" as "ooh yeah".
internal fun withoutBrackets(text: String): String {
    val trimmed = text.trim()
    if (trimmed.length >= 2 && trimmed.first() in Opening && trimmed.last() in Closing) {
        return trimmed.substring(1, trimmed.length - 1).trim()
    }
    return trimmed
}

// The words with an opening bracket off the first and a closing one off
// the last. A word left empty goes.
private fun wordsWithoutBrackets(words: List<SyncWord>): List<SyncWord> {
    if (words.isEmpty()) return words
    val opens = words.first().text.firstOrNull()?.let { it in Opening } == true
    val closes = words.last().text.lastOrNull()?.let { it in Closing } == true
    if (!opens || !closes) return words
    return words.mapIndexed { index, word ->
        var text = word.text
        if (index == 0) text = text.drop(1)
        if (index == words.lastIndex) text = text.dropLast(1)
        word.copy(text = text.trim())
    }.filter { it.text.isNotEmpty() }.let { kept ->
        kept.mapIndexed { index, word -> if (index == kept.lastIndex) word.copy(trailingSpace = true) else word }
    }
}

// "A", "A & B", "A, B & C".
private fun names(writers: List<String>): String =
    if (writers.size == 1) writers.first() else writers.dropLast(1).joinToString(", ") + " & " + writers.last()

private fun seconds(ms: Long): Double = ms / 1000.0

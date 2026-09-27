package app.winters.octo.markdown

// A small reader for the little markdown Octo writes itself, like its release
// notes: headings one to three deep, paragraphs, bullet lists one level
// nested, bold, italic, inline code, links and horizontal rules. Anything
// else is kept as plain text, so a song title such as "$UICIDE" or "F*ck It"
// reads exactly as written.

// A run of text and how it looks. `link` is where a tap on it goes.
data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

// One bullet, and the bullets under it.
data class Bullet(val text: List<Span>, val children: List<Bullet> = emptyList())

// A piece of the page, top to bottom.
sealed interface Block {
    // Level 1 to 3.
    data class Heading(val level: Int, val text: List<Span>) : Block
    data class Paragraph(val text: List<Span>) : Block
    data class Bullets(val items: List<Bullet>) : Block
    data object Rule : Block
}

private val HeadingLine = Regex("^(#{1,6})\\s+(.*?)(?:\\s+#+)?\\s*$")
private val BulletLine = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val RuleLine = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

// How far in a bullet must sit to be under the one above it.
private const val NEST_SPACES = 2

// Reads the page into blocks.
fun parseMarkdown(source: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val paragraph = mutableListOf<String>()
    // The list being read: top-level bullets, each with its children so far.
    val bullets = mutableListOf<Pair<StringBuilder, MutableList<StringBuilder>>>()
    // Where the last line went, so an indented line after a bullet carries it on.
    var lastBullet: StringBuilder? = null

    fun endParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += Block.Paragraph(parseInline(paragraph.joinToString(" ")))
            paragraph.clear()
        }
    }

    fun endList() {
        if (bullets.isNotEmpty()) {
            blocks += Block.Bullets(
                bullets.map { (top, children) ->
                    Bullet(parseInline(top.toString()), children.map { Bullet(parseInline(it.toString())) })
                },
            )
            bullets.clear()
        }
        lastBullet = null
    }

    for (raw in source.replace("\r\n", "\n").split('\n')) {
        val line = raw.trimEnd()
        if (line.isBlank()) {
            endParagraph()
            endList()
            continue
        }
        if (RuleLine.matches(line)) {
            endParagraph()
            endList()
            blocks += Block.Rule
            continue
        }
        val heading = HeadingLine.find(line)
        if (heading != null) {
            endParagraph()
            endList()
            val level = heading.groupValues[1].length.coerceAtMost(3)
            blocks += Block.Heading(level, parseInline(heading.groupValues[2]))
            continue
        }
        val bullet = BulletLine.find(line)
        if (bullet != null) {
            endParagraph()
            val indent = bullet.groupValues[1].replace("\t", "    ").length
            val text = StringBuilder(bullet.groupValues[2].trim())
            if (indent >= NEST_SPACES && bullets.isNotEmpty()) {
                // Deeper than one level still sits one level in.
                bullets.last().second += text
            } else {
                bullets += text to mutableListOf()
            }
            lastBullet = text
            continue
        }
        val carried = lastBullet
        if (carried != null && raw.startsWith(" ")) {
            // An indented line after a bullet continues it.
            carried.append(' ').append(line.trim())
            continue
        }
        endList()
        paragraph += line.trim()
    }
    endParagraph()
    endList()
    return blocks
}

// Reads one line's inline marks into runs of text.
fun parseInline(text: String): List<Span> = merge(InlineReader(text).read(Look()))

// How the text being read looks so far, from the marks around it.
private data class Look(val bold: Boolean = false, val italic: Boolean = false, val link: String? = null)

private class InlineReader(private val text: String) {
    private var at = 0

    // Reads until `until` (a closing mark) or the end.
    fun read(look: Look, until: String? = null): List<Span> {
        val out = mutableListOf<Span>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                out += Span(plain.toString(), bold = look.bold, italic = look.italic, link = look.link)
                plain.clear()
            }
        }
        while (at < text.length) {
            if (until != null && closes(until)) return out.also { flush() }
            val c = text[at]
            when {
                c == '\\' && at + 1 < text.length && text[at + 1] in Escapable -> {
                    plain.append(text[at + 1])
                    at += 2
                }
                c == '`' -> {
                    val end = text.indexOf('`', at + 1)
                    if (end > at + 1) {
                        flush()
                        out += Span(text.substring(at + 1, end), bold = look.bold, italic = look.italic, code = true, link = look.link)
                        at = end + 1
                    } else {
                        plain.append(c)
                        at++
                    }
                }
                (c == '*' || c == '_') && at + 1 < text.length && text[at + 1] == c && opens(2, c) && hasClosing("$c$c", at + 2) -> {
                    flush()
                    at += 2
                    out += read(look.copy(bold = true), "$c$c")
                    if (text.startsWith("$c$c", at)) at += 2
                }
                (c == '*' || c == '_') && opens(1, c) && hasClosing("$c", at + 1) -> {
                    flush()
                    at += 1
                    out += read(look.copy(italic = true), "$c")
                    if (text.startsWith("$c", at)) at += 1
                }
                c == '[' && look.link == null -> {
                    val link = linkAt(at)
                    if (link == null) {
                        plain.append(c)
                        at++
                    } else {
                        flush()
                        val (label, url, end) = link
                        out += InlineReader(label).read(look.copy(link = url))
                        at = end
                    }
                }
                else -> {
                    plain.append(c)
                    at++
                }
            }
        }
        flush()
        return out
    }

    // A mark of `width` opens here when text follows it straight away, and
    // it does not sit inside a word, so "F*ck" and "snake_case" stay as they are.
    private fun opens(width: Int, mark: Char): Boolean {
        val next = text.getOrNull(at + width) ?: return false
        if (next.isWhitespace() || next == mark) return false
        val before = text.getOrNull(at - 1)
        return before == null || !before.isLetterOrDigit()
    }

    // Whether `mark` closes somewhere after `from`, right after text and not
    // inside a word.
    private fun hasClosing(mark: String, from: Int): Boolean {
        var i = text.indexOf(mark, from)
        while (i >= 0) {
            if (closesAt(i, mark)) return true
            i = text.indexOf(mark, i + 1)
        }
        return false
    }

    private fun closes(mark: String): Boolean = text.startsWith(mark, at) && closesAt(at, mark)

    private fun closesAt(i: Int, mark: String): Boolean {
        val before = text.getOrNull(i - 1) ?: return false
        if (before.isWhitespace()) return false
        // A single mark is not half of a double one.
        if (mark.length == 1 && (text.getOrNull(i + 1) == mark[0] || before == mark[0])) return false
        val after = text.getOrNull(i + mark.length)
        return after == null || !after.isLetterOrDigit()
    }

    // A link here: its words, where it goes, and where it ends.
    private fun linkAt(start: Int): Triple<String, String, Int>? {
        val close = text.indexOf(']', start + 1)
        if (close < 0 || text.getOrNull(close + 1) != '(') return null
        val end = text.indexOf(')', close + 2)
        if (end < 0) return null
        val label = text.substring(start + 1, close)
        val url = text.substring(close + 2, end).trim()
        if (label.isBlank() || url.isEmpty() || url.any { it.isWhitespace() }) return null
        return Triple(label, url, end + 1)
    }

    companion object {
        private const val Escapable = "\\`*_[]()#+-.!$"
    }
}

// Joins neighbouring runs that look the same.
private fun merge(spans: List<Span>): List<Span> {
    val out = mutableListOf<Span>()
    for (span in spans) {
        if (span.text.isEmpty()) continue
        val last = out.lastOrNull()
        if (last != null && last.copy(text = "") == span.copy(text = "")) {
            out[out.lastIndex] = last.copy(text = last.text + span.text)
        } else {
            out += span
        }
    }
    return out
}

// The words alone, with the marks gone, for reading aloud or testing.
fun List<Span>.plainText(): String = joinToString("") { it.text }

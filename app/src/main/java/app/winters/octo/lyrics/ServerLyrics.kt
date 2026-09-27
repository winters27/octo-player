package app.winters.octo.lyrics

import app.winters.octo.subsonic.Cue
import app.winters.octo.subsonic.CueLine
import app.winters.octo.subsonic.StructuredLyrics
import java.util.Locale
import java.util.MissingResourceException
import kotlin.math.roundToLong

// The server's lyrics as one set: the main lyrics, synced ones first and
// word-timed ones before those, with a translation into `language` beside
// each line when the server has one and the song is in another language.
// Null when the server has nothing to show.
fun serverLyrics(all: List<StructuredLyrics>, language: String?): Lyrics? {
    val mains = all.filter { (it.kind == null || it.kind == "main") && it.line.any { line -> line.value.isNotBlank() } }
    val main = mains.maxByOrNull { (if (it.synced) 2 else 0) + (if (it.cueLine.isNotEmpty()) 1 else 0) } ?: return null
    if (!main.synced) {
        return Lyrics(synced = false, lines = main.line.map { LyricLine(text = it.value.trim()) }, source = LyricsSource.Server)
    }

    val roles = main.agents.associate { it.id to it }
    val cues = main.cueLine.groupBy { it.index }
    val lines = main.line.mapIndexed { index, line ->
        val start = line.start ?: 0
        val layers = cues[index].orEmpty()
        if (layers.isEmpty()) return@mapIndexed LyricLine(startMs = start, text = line.value.trim())
        val (backing, leads) = layers.partition { roles[it.agentId]?.role == "bg" }
        val lead = joinLayers(leads)
        val back = joinLayers(backing)
        val first = leads.firstOrNull()?.let { roles[it.agentId] }
        val ends = layers.mapNotNull { it.end ?: it.cue.mapNotNull(Cue::end).maxOrNull() }
        if (lead == null) {
            // Only the backing voices sing this line.
            LyricLine(
                startMs = start,
                endMs = ends.maxOrNull(),
                text = back?.first.orEmpty(),
                words = back?.second.orEmpty(),
                background = true,
            )
        } else {
            LyricLine(
                startMs = start,
                endMs = ends.maxOrNull(),
                text = lead.first,
                words = lead.second,
                backingText = back?.first.orEmpty(),
                backing = back?.second.orEmpty(),
                agent = first?.name,
                duet = first?.role == "voice",
            )
        }
    }

    val translated = companionFor(all, main, language, "translation")
    val sounded = companionFor(all, main, language, "pronunciation", matchLanguage = false)
    val offset = main.offset.roundToLong()
    return Lyrics(
        synced = true,
        lines = lines.mapIndexed { index, line ->
            line.copy(translation = translated?.get(index), romanization = sounded?.get(index)).shifted(-offset)
        },
        source = LyricsSource.Server,
    )
}

// Several cue lines for one lyric line (two voices at once) read as one
// text with a space between; each one's words keep their place in it.
private fun joinLayers(layers: List<CueLine>): Pair<String, List<LyricWord>>? {
    if (layers.isEmpty()) return null
    val text = StringBuilder()
    val words = mutableListOf<LyricWord>()
    layers.forEach { layer ->
        if (text.isNotEmpty()) text.append(' ')
        // Spaces a cue line starts with are left out, since the lyrics view
        // trims its lines and the words must keep their places.
        val cut = layer.value.length - layer.value.trimStart().length
        val shift = text.length - cut
        text.append(layer.value, cut, layer.value.length)
        words += cueWords(layer).filter { it.to > cut }.map { it.copy(from = maxOf(it.from, cut) + shift, to = it.to + shift) }
    }
    return text.toString() to words
}

// A cue line's timed words, placed in its text by character. The server
// gives each cue's place in UTF-8 bytes, both ends included; a cue whose
// bytes do not fit the text is left out.
fun cueWords(line: CueLine): List<LyricWord> {
    val chars = Utf8Positions(line.value)
    return line.cue.mapIndexedNotNull { index, cue ->
        val range = chars.charRange(cue.byteStart, cue.byteEnd) ?: return@mapIndexedNotNull null
        LyricWord(
            startMs = cue.start,
            endMs = cue.end ?: line.cue.getOrNull(index + 1)?.start ?: line.end,
            text = line.value.substring(range.first, range.last + 1),
            from = range.first,
            to = range.last + 1,
        )
    }
}

// Turns UTF-8 byte positions in a text into character positions. Letters
// outside the basic set take two to four bytes; one written with a pair of
// Java chars (an emoji, say) takes four bytes and two chars.
class Utf8Positions(private val text: String) {
    // For each byte, the char its letter starts at.
    private val charAt: IntArray

    init {
        val starts = ArrayList<Int>(text.length * 2)
        var index = 0
        while (index < text.length) {
            val point = text.codePointAt(index)
            val bytes = when {
                point < 0x80 -> 1
                point < 0x800 -> 2
                point < 0x10000 -> 3
                else -> 4
            }
            repeat(bytes) { starts += index }
            index += Character.charCount(point)
        }
        charAt = starts.toIntArray()
    }

    // The chars from the letter holding `byteStart` through the letter
    // holding `byteEnd`. Null when the bytes are outside the text or
    // backwards.
    fun charRange(byteStart: Int, byteEnd: Int): IntRange? {
        if (byteStart < 0 || byteEnd < byteStart || byteEnd >= charAt.size) return null
        val from = charAt[byteStart]
        val lastLetter = charAt[byteEnd]
        val to = lastLetter + Character.charCount(text.codePointAt(lastLetter))
        return from until to
    }
}

// A translation's or pronunciation's text for each line of the main lyrics,
// by line number when both have the same lines, or else by start time. Only
// when the phone reads another language than the song is sung in. A
// translation must be in the phone's language; a pronunciation is in
// whatever letters the server wrote it in.
private fun companionFor(
    all: List<StructuredLyrics>,
    main: StructuredLyrics,
    language: String?,
    kind: String,
    matchLanguage: Boolean = true,
): Map<Int, String>? {
    if (language.isNullOrBlank() || sameLanguage(main.lang, language)) return null
    val translation = all.firstOrNull { it.kind == kind && (!matchLanguage || sameLanguage(it.lang, language)) } ?: return null
    return if (translation.line.size == main.line.size) {
        translation.line.withIndex().filter { it.value.value.isNotBlank() }.associate { it.index to it.value.value.trim() }
    } else {
        val byStart = translation.line.filter { it.start != null && it.value.isNotBlank() }.associate { it.start to it.value.trim() }
        main.line.withIndex().mapNotNull { (index, line) -> byStart[line.start]?.let { index to it } }.toMap()
    }
}

// Whether two language tags name the same language, whether written with
// two letters ("en") or three ("eng").
internal fun sameLanguage(a: String, b: String): Boolean {
    fun key(tag: String): String {
        val locale = Locale.forLanguageTag(tag.replace('_', '-'))
        val short = locale.language.ifEmpty { tag.lowercase() }
        return try {
            locale.isO3Language.ifEmpty { short }
        } catch (_: MissingResourceException) {
            short
        }
    }
    return key(a) == key(b)
}

package app.winters.octo.lyrics

import kotlinx.serialization.Serializable

// Where a song's lyrics came from, in the words the lyrics menu uses.
@Serializable
enum class LyricsSource(val label: String) {
    Server("From your server"),
    SongFile("From the song file"),
    LyricsFile("From a .lrc file beside the song"),
    Online("From LRCLIB"),
}

// A song's lyrics, however they were found. Synced lyrics have a start time
// on every line; plain ones are only text, in order.
@Serializable
data class Lyrics(
    val synced: Boolean,
    val lines: List<LyricLine>,
    val source: LyricsSource,
    // Known to have no words at all.
    val instrumental: Boolean = false,
    // The online library's number for this copy, so a pick of it can be
    // fetched again. Null for other sources, and for answers saved before
    // it was kept.
    val onlineId: Long? = null,
    // The copy the server has pinned for the song ("kugou:123"), when this
    // phone knows it, so the menu can say where they are from. Null for the
    // server's own automatic answer and for other sources.
    val serverPick: String? = null,
) {
    val isEmpty: Boolean get() = !instrumental && lines.none { it.text.isNotBlank() }
}

@Serializable
data class LyricLine(
    val startMs: Long = 0,
    // When the line is known to end, or null to run until the next one.
    val endMs: Long? = null,
    // Empty for a gap where nobody sings.
    val text: String,
    // Timed words or syllables within the text, when the source has them.
    val words: List<LyricWord> = emptyList(),
    // Backing vocals sung over this line, with their own timings.
    val backingText: String = "",
    val backing: List<LyricWord> = emptyList(),
    // Who sings it, when the source says.
    val agent: String? = null,
    // Sung only by the backing voices.
    val background: Boolean = false,
    // Sung by a second lead voice, as in a duet.
    val duet: Boolean = false,
    // The line in the phone's language, when the source has it.
    val translation: String? = null,
    // How the line sounds, written in Latin letters, when the source has it.
    val romanization: String? = null,
) {
    val isGap: Boolean get() = text.isBlank() && backingText.isBlank()
}

// A timed piece of a line: `from` and `to` are where it sits in the line's
// text, in characters, `to` not included.
@Serializable
data class LyricWord(
    val startMs: Long,
    val endMs: Long? = null,
    val text: String,
    val from: Int,
    val to: Int,
)

// Which line is being sung at this point in the song: the last one that has
// started, or -1 before the first.
fun List<LyricLine>.lineAt(positionMs: Long): Int {
    var low = 0
    var high = lastIndex
    var found = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (this[mid].startMs <= positionMs) {
            found = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return found
}

// A gap longer than this, before the first line or after a line that is
// known to end, gets its own quiet line so the dots can show there.
private const val LONG_GAP_MS = 5_000L

// The lines as they are shown: a long wait before the first line, or after
// a line that is known to end, becomes an empty line where the dots show.
fun Lyrics.shownLines(): List<LyricLine> {
    if (!synced || lines.isEmpty()) return lines
    val shown = ArrayList<LyricLine>(lines.size + 4)
    if (lines.first().startMs >= LONG_GAP_MS && !lines.first().isGap) shown += LyricLine(startMs = 0, text = "")
    lines.forEachIndexed { index, line ->
        shown += line
        val end = line.endMs ?: return@forEachIndexed
        val next = lines.getOrNull(index + 1)?.startMs ?: return@forEachIndexed
        if (!line.isGap && next - end >= LONG_GAP_MS && !lines[index + 1].isGap) {
            shown += LyricLine(startMs = end, text = "")
        }
    }
    return shown
}

// When a line ends: its own end, or when the next one starts.
fun List<LyricLine>.endOf(index: Int): Long? =
    this[index].endMs ?: getOrNull(index + 1)?.startMs

package app.winters.octo.desktop.library

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.winters.octo.subsonic.Song

// A row of a song table: the song, where it sits in the list the table was
// given (a playlist's own order, say), and a key of its own. The key is the
// song's id, with a count added when the same song is in the list again, so
// picks and focus follow songs through a new sort instead of places.
data class TableRow(val key: String, val song: Song, val position: Int)

fun rowsOf(songs: List<Song>): List<TableRow> {
    val seen = HashMap<String, Int>()
    return songs.mapIndexed { i, song ->
        val n = seen.merge(song.id, 1, Int::plus)!!
        TableRow(if (n == 1) song.id else "${song.id}#$n", song, i)
    }
}

// The rows picked in a table, the way file managers pick them: a click picks
// one row, Ctrl (Cmd on a Mac) adds or takes away one, Shift picks the run
// from the last row clicked, and the arrow keys move a focus that Shift
// stretches the pick with. Rows are known by their keys, so a pick lasts
// through a new sort; rows that leave the list leave the pick.
@Stable
class TableSelection {
    var picked by mutableStateOf<Set<String>>(emptySet())
        private set

    // The row the keyboard is on.
    var focus by mutableStateOf<String?>(null)
        private set

    // The row a Shift-click or Shift-arrow runs from.
    private var anchor: String? = null

    fun click(key: String, toggle: Boolean, range: Boolean, order: List<String>) {
        val from = anchor
        picked = when {
            range && from != null && from in order -> {
                val run = run(order, from, key)
                if (toggle) picked + run else run
            }
            toggle -> if (key in picked) picked - key else picked + key
            else -> setOf(key)
        }
        if (!range || from == null) anchor = key
        focus = key
    }

    // A right click on a row outside the pick picks just that row, so the
    // menu acts on what was clicked; inside it, the pick stays.
    fun pickForMenu(key: String) {
        if (key !in picked) {
            picked = setOf(key)
            anchor = key
        }
        focus = key
    }

    // Moves the focus by `step` rows (negative is up), picking the row it
    // lands on, or with `stretch` the run from the anchor to it. Answers
    // the row it landed on.
    fun move(step: Int, stretch: Boolean, order: List<String>): String? {
        if (order.isEmpty()) return null
        val at = focus?.let(order::indexOf)?.takeIf { it >= 0 }
        val next = when {
            at == null -> if (step >= 0) 0 else order.lastIndex
            else -> (at + step).coerceIn(0, order.lastIndex)
        }
        val key = order[next]
        if (stretch) {
            val from = anchor?.takeIf { it in order } ?: focus ?: key
            anchor = from
            picked = run(order, from, key)
        } else {
            picked = setOf(key)
            anchor = key
        }
        focus = key
        return key
    }

    // Puts the focus on a row, picking it alone, as type-to-find does.
    fun jumpTo(key: String) {
        picked = setOf(key)
        anchor = key
        focus = key
    }

    fun selectAll(order: List<String>) {
        picked = order.toSet()
    }

    fun clear() {
        picked = emptySet()
        anchor = null
    }

    // Forgets rows no longer in the list.
    fun keepOnly(keys: Set<String>) {
        if (picked.any { it !in keys }) picked = picked.filterTo(HashSet()) { it in keys }
        if (focus != null && focus !in keys) focus = null
        if (anchor != null && anchor !in keys) anchor = null
    }

    // The picked rows, in the table's order.
    fun of(rows: List<TableRow>): List<TableRow> = rows.filter { it.key in picked }

    private fun run(order: List<String>, from: String, to: String): Set<String> {
        val a = order.indexOf(from)
        val b = order.indexOf(to)
        if (a < 0 || b < 0) return setOf(to)
        return order.subList(minOf(a, b), maxOf(a, b) + 1).toSet()
    }
}

// Finds the row whose title starts with what was typed, looking from the
// row after the focus onward and wrapping round, as file lists do. Case and
// accents are ignored.
fun typeAhead(rows: List<TableRow>, typed: String, from: String?): String? {
    if (typed.isBlank() || rows.isEmpty()) return null
    val want = fold(typed)
    val start = from?.let { key -> rows.indexOfFirst { it.key == key } }?.takeIf { it >= 0 } ?: -1
    // A single letter typed again moves on to the next match; a longer
    // prefix keeps the row it already matches.
    val first = if (typed.length == 1) start + 1 else maxOf(start, 0)
    for (i in rows.indices) {
        val row = rows[(first + i).mod(rows.size)]
        if (fold(row.song.title).startsWith(want)) return row.key
    }
    return null
}

private fun fold(text: String): String =
    java.text.Normalizer.normalize(text.trim().lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

package app.winters.octo.desktop.library

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// The rows picked in a table, the way file managers pick them: a click picks
// one row, Ctrl (Cmd on a Mac) adds or takes away one, Shift picks the run
// from the last row clicked. Rows are counted by their place in the table as
// shown, so the table clears this whenever its order changes.
@Stable
class TableSelection {
    var picked by mutableStateOf<Set<Int>>(emptySet())
        private set

    // The row a Shift-click runs from.
    private var anchor: Int? = null

    fun click(row: Int, toggle: Boolean, range: Boolean) {
        val from = anchor
        picked = when {
            range && from != null -> {
                val run = if (from <= row) from..row else row..from
                if (toggle) picked + run else run.toSet()
            }
            toggle -> if (row in picked) picked - row else picked + row
            else -> setOf(row)
        }
        if (!range || from == null) anchor = row
    }

    // A right click on a row outside the selection picks just that row, so
    // the menu acts on what was clicked; inside it, the selection stays.
    fun pickForMenu(row: Int) {
        if (row !in picked) {
            picked = setOf(row)
            anchor = row
        }
    }

    fun selectAll(size: Int) {
        picked = (0 until size).toSet()
    }

    fun clear() {
        picked = emptySet()
        anchor = null
    }

    fun <T> of(rows: List<T>): List<T> = picked.sorted().mapNotNull { rows.getOrNull(it) }
}

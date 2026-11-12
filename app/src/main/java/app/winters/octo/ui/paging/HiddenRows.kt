package app.winters.octo.ui.paging

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

// Rows the listener took off a list (a swipe), each shown gone at once and
// until a generation of the list begun after its change committed shows. A
// generation begun before the commit still holds the row, so letting it go
// then would bring the row back for a frame.
class HiddenRows {
    private val rows = MutableStateFlow<Map<String, Long>>(emptyMap())

    // A row taken off, before its change commits.
    fun hide(id: String) = rows.update { it + (id to Long.MAX_VALUE) }

    // Its change committed while `generationNow` was the newest generation
    // made: that one and every older one began before it.
    fun committed(id: String, generationNow: Long) = rows.update { if (id in it) it + (id to generationNow) else it }

    // Put back (an Undo).
    fun show(id: String) = rows.update { it - id }

    // The rows hidden while the generation `shown` describes is on screen.
    fun among(shown: Flow<ListMeta?>): Flow<Set<String>> = combine(rows, shown) { hidden, meta ->
        val generation = meta?.generation ?: 0
        hidden.filterValues { it >= generation }.keys
    }
}

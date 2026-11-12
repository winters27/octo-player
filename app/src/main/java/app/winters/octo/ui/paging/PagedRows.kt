package app.winters.octo.ui.paging

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.ItemSnapshotList
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import app.winters.octo.ui.common.LetterRun
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

// How tall a song row is, for a row not read yet.
val SongRowHeight = 64.dp

// The rows of a handle's list for a screen. The screen's first visible row
// goes back to the handle (a refresh reads around it), and after a refresh
// the list is kept on the row it showed: when the new generation shows, a
// row that moved (a merge adding rows above it) is scrolled back into the
// same place. `above` counts the rows before the list's own; `runs` are
// the list's letter runs when it shows a heading before each.
@Composable
fun <T : Any> rememberListRows(
    handle: ListHandle<*, T>,
    state: LazyListState,
    above: Int = 0,
    runs: List<LetterRun>? = null,
    visible: (RowKey?) -> Unit = handle::visible,
): LazyPagingItems<ListItem<T>> {
    val items = handle.pages.collectAsLazyPagingItems()
    val headed by rememberUpdatedState(runs)
    val tell by rememberUpdatedState(visible)
    LaunchedEffect(items, state) {
        snapshotFlow { state.firstVisibleItemIndex }.distinctUntilChanged().collect { index ->
            val at = positionOf(index - above, headed)
            tell(if (at in 0 until items.itemCount) items.peek(at)?.key else null)
        }
    }
    LaunchedEffect(handle, state) {
        handle.reanchors.collectLatest { reanchor ->
            if (!reanchor.follow) return@collectLatest
            snapshotFlow { items.itemSnapshotList.items.firstOrNull()?.generation }.first { it == reanchor.generation }
            val index = above + indexOf(reanchor.index, headed)
            if (state.firstVisibleItemIndex != index && state.firstVisibleItemIndex >= above) {
                state.requestScrollToItem(index, state.firstVisibleItemScrollOffset)
            }
        }
    }
    return items
}

// The same for a list shown with lines of its own between its rows (a
// disc's heading): `rowOf` is a line's row, null for a line between rows.
// A line counts as the row after it, and the screen is kept on the row it
// showed by that row's lazy index among the lines read.
@Composable
fun <T : Any, L : Any> rememberLinedRows(
    handle: ListHandle<*, T>,
    pages: Flow<PagingData<L>>,
    rowOf: (L) -> ListItem<T>?,
    state: LazyListState,
    above: Int = 0,
    visible: (RowKey?) -> Unit = handle::visible,
): LazyPagingItems<L> {
    val items = pages.collectAsLazyPagingItems()
    val tell by rememberUpdatedState(visible)
    val row by rememberUpdatedState(rowOf)
    LaunchedEffect(items, state) {
        snapshotFlow { state.firstVisibleItemIndex }.distinctUntilChanged().collect { index ->
            var at = index - above
            var key: RowKey? = null
            while (at in 0 until items.itemCount) {
                val line = items.peek(at) ?: break
                key = row(line)?.key
                if (key != null) break
                at++
            }
            tell(key)
        }
    }
    LaunchedEffect(handle, state) {
        handle.reanchors.collectLatest { reanchor ->
            if (!reanchor.follow) return@collectLatest
            snapshotFlow { items.itemSnapshotList.items.firstNotNullOfOrNull(row)?.generation }.first { it == reanchor.generation }
            val index = above + lineIndexOf(items.itemSnapshotList, reanchor.index) { row(it) != null }
            if (state.firstVisibleItemIndex != index && state.firstVisibleItemIndex >= above) {
                state.requestScrollToItem(index, state.firstVisibleItemScrollOffset)
            }
        }
    }
    return items
}

// The lazy index of the list's row `position` among presented lines: rows
// not read yet stand for themselves, and each line read between rows
// before it moves it on by one.
fun <L : Any> lineIndexOf(lines: ItemSnapshotList<L>, position: Int, isRow: (L) -> Boolean): Int {
    val before = lines.placeholdersBefore
    if (position < before) return position
    var rows = before
    var index = before
    for (line in lines.items) {
        if (isRow(line)) {
            if (rows == position) return index
            rows++
        }
        index++
    }
    return index + (position - rows)
}

// The lazy list's index of the list's row `position`, with a heading
// before each run.
fun indexOf(position: Int, runs: List<LetterRun>?): Int {
    if (runs == null) return position
    val before = runs.indexOfLast { it.start <= position }.coerceAtLeast(0)
    return position + before + 1
}

// The list's row at a lazy list index, with a heading before each run; a
// heading counts as the row after it.
fun positionOf(index: Int, runs: List<LetterRun>?): Int {
    if (runs == null) return index
    var headings = 0
    for (run in runs) {
        val headingAt = run.start + headings
        if (index <= headingAt) return run.start
        headings++
        if (index < headingAt + 1 + run.count) return index - headings
    }
    return index - headings
}

// The list's rows, each `row` (null for a row not read yet), under a sticky
// heading per letter run when `runs` is given; keys are the rows' keys, so
// two rows never share one even while a row moves.
fun <T : Any> LazyListScope.listRows(
    items: LazyPagingItems<ListItem<T>>,
    runs: List<LetterRun>?,
    heading: @Composable LazyItemScope.(Char) -> Unit,
    row: @Composable LazyItemScope.(ListItem<T>?) -> Unit,
) {
    val count = items.itemCount
    fun key(at: Int): Any = items.peek(at)?.key?.token ?: "row:$at"
    if (runs == null) {
        items(count, key = ::key) { row(items[it]) }
        return
    }
    runs.forEachIndexed { r, run ->
        if (run.start >= count) return@forEachIndexed
        stickyHeader(key = "letter:$r:${run.letter}") { heading(run.letter) }
        items(minOf(run.count, count - run.start), key = { key(run.start + it) }) { row(items[run.start + it]) }
    }
}

// The space a song row not read yet keeps.
@Composable
fun RowPlaceholder() {
    Box(Modifier.fillMaxWidth().height(SongRowHeight))
}

// Lets a paged list's rows be picked. A tapped row is found among the rows
// read (`pickOf`); picks are kept to the rows the list still holds, asked
// of the list (by `idOf`, 900 at a time) at each new generation; and when
// an action runs they are put in the list's order by keys read fresh
// (`freshKey`). `base` is the list they play as part of.
@Composable
fun <T : Any> PagedPicks(
    handle: ListHandle<*, T>,
    items: LazyPagingItems<ListItem<T>>,
    pickOf: (key: String, item: ListItem<T>) -> app.winters.octo.ui.common.Picked?,
    base: app.winters.octo.playback.ListSpec?,
    idOf: (app.winters.octo.ui.common.Picked) -> String = { it.trackId },
    freshKey: suspend (ListLoader<T>, app.winters.octo.ui.common.Picked) -> RowKey? = { loader, picked -> loader.keyOf(picked.trackId) },
    onRemove: ((List<app.winters.octo.ui.common.Picked>) -> Unit)? = null,
    content: @Composable () -> Unit,
) = PagedPicks(handle, { items.itemSnapshotList.items }, pickOf, base, idOf, freshKey, onRemove, content)

// The same for rows read among lines of their own (`loaded`, the rows read).
@Composable
fun <T : Any> PagedPicks(
    handle: ListHandle<*, T>,
    loaded: () -> List<ListItem<T>>,
    pickOf: (key: String, item: ListItem<T>) -> app.winters.octo.ui.common.Picked?,
    base: app.winters.octo.playback.ListSpec?,
    idOf: (app.winters.octo.ui.common.Picked) -> String = { it.trackId },
    freshKey: suspend (ListLoader<T>, app.winters.octo.ui.common.Picked) -> RowKey? = { loader, picked -> loader.keyOf(picked.trackId) },
    onRemove: ((List<app.winters.octo.ui.common.Picked>) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val generation by handle.shown.collectAsStateWithLifecycle()
    app.winters.octo.ui.common.SelectableRows(
        resolve = { key -> loaded().firstNotNullOfOrNull { pickOf(key, it) } },
        order = { picks ->
            val loader = handle.loader
            picks.mapNotNull { p -> freshKey(loader, p)?.let { p.copy(rowKey = it) } }.sortedWith { a, b -> loader.order.compare(a.rowKey!!, b.rowKey!!) }
        },
        onRemove = onRemove,
        base = base,
        upkeep = { selection ->
            LaunchedEffect(generation?.generation) {
                if (!selection.active) return@LaunchedEffect
                val picks = selection.picks.rows.values.toList()
                val present = picks.map(idOf).distinct().chunked(900).flatMap { handle.loader.present(it) }.toSet()
                selection.keepOnly(picks.filter { idOf(it) in present }.mapTo(HashSet()) { it.key })
            }
        },
        content = content,
    )
}

package app.winters.octo.ui.paging

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import app.winters.octo.design.OctoColors
import app.winters.octo.ui.common.ListRowSide
import app.winters.octo.ui.common.SongRowCover
import app.winters.octo.ui.common.SongRowCoverCorner
import app.winters.octo.ui.common.SongRowGap
import app.winters.octo.ui.common.SongRowMinHeight
import app.winters.octo.ui.common.SongRowNumberWidth
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
val SongRowHeight = SongRowMinHeight

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

// The list's rows, each `row` (null for a row not read yet), under a
// heading per letter run when `runs` is given, each heading one item of the
// list; keys are the rows' keys, so two rows never share one even while a
// row moves.
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
        item(key = "letter:$r:${run.letter}") { heading(run.letter) }
        items(minOf(run.count, count - run.start), key = { key(run.start + it) }) { row(items[run.start + it]) }
    }
}

// A song row not read yet: its faint shape at the real row's place and
// size, still, so it never reads as content. A dim cover square, or the
// number's room with `numbered`, then a title bar and a shorter subtitle bar.
@Composable
fun RowPlaceholder(numbered: Boolean = false) {
    val fill = OctoColors.BackgroundTertiary
    Row(
        Modifier
            .fillMaxWidth()
            .height(SongRowHeight)
            .padding(horizontal = ListRowSide, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SongRowGap),
    ) {
        if (numbered) {
            Spacer(Modifier.width(SongRowNumberWidth))
        } else {
            Box(Modifier.size(SongRowCover).background(fill, RoundedCornerShape(SongRowCoverCorner)))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PlaceholderBarGap)) {
            Box(Modifier.fillMaxWidth(PlaceholderTitleWidth).height(PlaceholderTitleBar).background(fill, PlaceholderBarShape))
            Box(Modifier.fillMaxWidth(PlaceholderSubtitleWidth).height(PlaceholderSubtitleBar).background(fill, PlaceholderBarShape))
        }
    }
}

// The placeholder's bars sit where a row's title and subtitle lines do.
val PlaceholderTitleBar = 12.dp
val PlaceholderSubtitleBar = 10.dp
val PlaceholderBarGap = 4.dp
const val PlaceholderTitleWidth = 0.6f
const val PlaceholderSubtitleWidth = 0.38f
private val PlaceholderBarShape = RoundedCornerShape(50)

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

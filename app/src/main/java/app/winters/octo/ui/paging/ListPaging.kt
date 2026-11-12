package app.winters.octo.ui.paging

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingSource.LoadResult.Page.Companion.COUNT_UNDEFINED
import androidx.paging.PagingState
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.HoldKind
import app.winters.octo.catalog.stableRead
import app.winters.octo.listening.PlayTotals
import app.winters.octo.listening.TotalsFailed
import app.winters.octo.ui.common.LetterRun
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

// How a screen pages a list: 100 rows a page, 300 at a refresh, at most
// 1,000 held, and a jump past 500 rows reads around where it lands.
val LIST_PAGING = PagingConfig(
    pageSize = 100,
    prefetchDistance = 100,
    initialLoadSize = 300,
    enablePlaceholders = true,
    maxSize = 1_000,
    jumpThreshold = 500,
)

// Where a page of a list starts: the top, around a row (with its place when
// that is known), around a place the list jumped to, or just after or
// before a row already shown.
sealed interface PageKey {
    data object Top : PageKey

    data class At(val key: RowKey, val known: Int? = null) : PageKey

    data class Near(val position: Int) : PageKey

    data class After(val key: RowKey) : PageKey

    data class Before(val key: RowKey) : PageKey
}

// A row as a screen holds it, with the generation of the list it was read in.
data class ListItem<T>(val key: RowKey, val row: T, val generation: Long)

// What one generation of a list knows of the whole: how many rows it has
// (null while a new filter's first rows show, before its count is known),
// its letter runs, and for a list filtered in Kotlin what its count's pass
// kept to start a play from. `shown` is how many rows its first page read.
data class ListMeta(
    val generation: Long,
    val count: Int?,
    val runs: List<LetterRun>?,
    val start: SurveyStart? = null,
    val shown: Int = 0,
)

// Once a generation shows, the screen keeps `key` where it is: at `index`.
// `follow` is false for a read near a position the screen jumped to (a
// letter on the rail, a fling far past the rows read): its key is only the
// row Paging touched last, often the one just above the screen, and the
// screen is already where it belongs.
data class Reanchor(val generation: Long, val key: RowKey, val index: Int, val follow: Boolean = true)

// How a list's refresh reads one state of the library: every try first
// runs `prepare` (the waits, the count and the anchor), then `block` (the
// two page reads); a try that a swap crossed reads again, and the third
// reads inside the merge lock. A list that reads the stored play totals
// also waits for them, reads again when they moved, and holds them still
// around its last try.
interface ListReads {
    suspend fun <T> read(totals: Boolean, prepare: suspend () -> Unit, block: suspend () -> T): T

    // Reads as they come, for lists read outside the library (tests).
    object Plain : ListReads {
        override suspend fun <T> read(totals: Boolean, prepare: suspend () -> Unit, block: suspend () -> T): T {
            prepare()
            return block()
        }
    }
}

@Singleton
class LibraryReads @Inject constructor(private val merge: CatalogMerge, private val totals: PlayTotals) : ListReads {
    override suspend fun <T> read(totals: Boolean, prepare: suspend () -> Unit, block: suspend () -> T): T =
        if (totals) this.totals.read(HoldKind.Read, prepare, block) else stableRead(merge, HoldKind.Read, prepare, block)
}

// What a refresh, an append or a prepend read for the screen: whether the
// list's first and last rows are among them.
class PageRead<T>(val generation: Long, val rows: List<Keyed<T>>, val top: Boolean, val end: Boolean)

// One generation of a list in pages, read by row keys rather than places,
// so rows that move under a merge or a play never shift the rows around
// them. A refresh reads around its anchor (a row key, never a place),
// publishes the count and the letter runs with its rows, and asks the
// screen to keep the anchor where it was. Appends and prepends read on
// from the edge rows' keys.
class KeysetPagingSource<T : Any>(
    private val loader: ListLoader<T>,
    val generation: Long,
    private val reads: ListReads,
    private val publish: (ListMeta) -> Unit,
    private val hint: () -> RowKey?,
    private val reanchor: (Reanchor) -> Unit,
    // A new question (an order or a filter) starts at the top.
    private val fromTop: Boolean = false,
    // A new filter's first rows: read from the top with no count yet.
    private val firstRows: Boolean = false,
    private val loaded: (PageRead<T>) -> Unit = {},
) : PagingSource<PageKey, ListItem<T>>() {
    override val jumpingSupported: Boolean get() = true

    override val keyReuseSupported: Boolean get() = true

    private val totals = PLAY_TOTAL in loader.tables

    override suspend fun load(params: LoadParams<PageKey>): LoadResult<PageKey, ListItem<T>> = try {
        when (params) {
            is LoadParams.Refresh -> refresh(params.key ?: PageKey.Top, params.loadSize)
            is LoadParams.Append -> append(params.key, params.loadSize)
            is LoadParams.Prepend -> prepend(params.key, params.loadSize)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: TotalsFailed) {
        LoadResult.Error(e)
    } catch (e: Exception) {
        LoadResult.Error(e)
    }

    private fun item(row: Keyed<T>) = ListItem(row.key, row.row, generation)

    // The anchor of a refresh, as the list stands: its row key and how many
    // rows come before it, and the survey that counted them.
    private class Anchor(val key: RowKey?, val at: Int, val survey: Survey?)

    private suspend fun resolve(key: PageKey): Anchor {
        if (firstRows) return Anchor(null, 0, null)
        return when (key) {
            PageKey.Top, is PageKey.After, is PageKey.Before -> Anchor(null, 0, loader.survey(null))
            is PageKey.At -> {
                val mapped = loader.rekey(key.key) ?: key.known?.let { loader.keyAt(it) } ?: key.key
                val survey = loader.survey(mapped)
                Anchor(mapped, survey.anchorAt ?: key.known ?: 0, survey)
            }
            is PageKey.Near -> {
                val survey = loader.survey(null)
                val at = key.position.coerceIn(0, (survey.count - 1).coerceAtLeast(0))
                val found = if (survey.count == 0) null else loader.keyAt(at)
                Anchor(found, if (found == null) 0 else at, survey)
            }
        }
    }

    private suspend fun refresh(key: PageKey, size: Int): LoadResult<PageKey, ListItem<T>> {
        val half = size / 2
        var tries = 0
        while (true) {
            var anchor = Anchor(null, 0, null)
            var before: List<Keyed<T>> = emptyList()
            var after: List<Keyed<T>> = emptyList()
            var wanted = size
            reads.read(totals, prepare = { anchor = resolve(key) }) {
                val at = anchor.key
                before = if (at == null) emptyList() else loader.before(at, half)
                wanted = size - before.size
                // Rows at or after the anchor: after the row just before it.
                after = loader.after(before.firstOrNull()?.key, wanted)
            }
            val rows = before.asReversed() + after
            val survey = anchor.survey
            val reachedTop = anchor.key == null || before.size < half
            val reachedEnd = after.size < wanted
            var count = survey?.count
            var runs = survey?.runs
            var itemsBefore = if (reachedTop) 0 else (anchor.at - before.size).coerceAtLeast(0)
            if (count != null) {
                val whole = if (reachedEnd) itemsBefore + rows.size else null
                val torn = itemsBefore + rows.size > count || (reachedTop && anchor.at != before.size) || (whole != null && whole != count)
                if (torn) {
                    // A write landed between the count and the rows: count
                    // again, twice at most; then show the rows as read, with
                    // no letters, and the write's own change reads again.
                    if (++tries <= 2) continue
                    runs = null
                    count = whole ?: maxOf(count, itemsBefore + rows.size)
                    if (reachedTop) itemsBefore = 0
                }
            }
            val itemsAfter = if (count == null) COUNT_UNDEFINED else (count - itemsBefore - rows.size).coerceAtLeast(0)
            publish(ListMeta(generation, count, runs, survey?.start, rows.size))
            anchor.key?.let { reanchor(Reanchor(generation, it, anchor.at, follow = key is PageKey.At)) }
            loaded(PageRead(generation, rows, reachedTop, reachedEnd))
            return LoadResult.Page(
                data = rows.map(::item),
                prevKey = if (reachedTop || rows.isEmpty()) null else PageKey.Before(rows.first().key),
                nextKey = if (reachedEnd || rows.isEmpty()) null else PageKey.After(rows.last().key),
                itemsBefore = itemsBefore,
                itemsAfter = itemsAfter,
            )
        }
    }

    private suspend fun append(key: PageKey, size: Int): LoadResult<PageKey, ListItem<T>> {
        val from = (key as? PageKey.After)?.key ?: return LoadResult.Page(emptyList(), null, null)
        val rows = loader.after(from, size)
        val end = rows.size < size
        loaded(PageRead(generation, rows, top = false, end = end))
        return LoadResult.Page(rows.map(::item), prevKey = null, nextKey = if (end) null else PageKey.After(rows.last().key))
    }

    private suspend fun prepend(key: PageKey, size: Int): LoadResult<PageKey, ListItem<T>> {
        val from = (key as? PageKey.Before)?.key ?: return LoadResult.Page(emptyList(), null, null)
        val rows = loader.before(from, size).asReversed()
        val top = rows.size < size
        loaded(PageRead(generation, rows, top = top, end = false))
        return LoadResult.Page(rows.map(::item), prevKey = if (top) null else PageKey.Before(rows.first().key), nextKey = null)
    }

    // Where a refresh after this generation starts: the top for a new
    // question; around the place a jump landed when it is far from the rows
    // read; around the screen's first visible row when it is among them;
    // otherwise around the row nearest the screen's place.
    override fun getRefreshKey(state: PagingState<PageKey, ListItem<T>>): PageKey? {
        if (fromTop) return PageKey.Top
        val pages = state.pages
        val start = loadedStart(pages)
        val loadedCount = pages.sumOf { it.data.size }
        val anchor = state.anchorPosition
        if (anchor != null && start != null) {
            val jump = state.config.jumpThreshold
            if (jump > 0 && (anchor < start - jump || anchor >= start + loadedCount + jump)) return PageKey.Near(anchor)
        }
        hint()?.let { key ->
            if (pages.any { page -> page.data.any { it.key == key } }) return PageKey.At(key, positionOf(pages, start) { it.key == key })
        }
        if (anchor == null) return state.firstItemOrNull()?.let { PageKey.At(it.key, start) }
        val item = state.closestItemToPosition(anchor) ?: return PageKey.Near(anchor)
        return PageKey.At(item.key, positionOf(pages, start) { it === item })
    }

    // The place of the first row read, from the refresh page's count of the
    // rows before it.
    private fun loadedStart(pages: List<LoadResult.Page<PageKey, ListItem<T>>>): Int? {
        var before = 0
        for (page in pages) {
            if (page.itemsBefore != COUNT_UNDEFINED) return page.itemsBefore - before
            before += page.data.size
        }
        return null
    }

    private fun positionOf(pages: List<LoadResult.Page<PageKey, ListItem<T>>>, start: Int?, match: (ListItem<T>) -> Boolean): Int? {
        start ?: return null
        var at = start
        for (page in pages) {
            val i = page.data.indexOfFirst(match)
            if (i >= 0) return at + i
            at += page.data.size
        }
        return null
    }
}

// The table of stored play totals: a list that names it waits for them.
const val PLAY_TOTAL = "play_total"

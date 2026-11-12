package app.winters.octo.ui.paging

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import androidx.room.RoomDatabase
import app.winters.octo.playback.QUEUE_WINDOW
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// Where a loader that ends with its generation lets go of what it held,
// beyond the screen's own life.
private val RETIRE_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// Writes to the tables a list reads, as a flow that says only that one came.
fun RoomDatabase.tableChanges(tables: Array<String>): Flow<Unit> =
    if (tables.isEmpty()) emptyFlow() else invalidationTracker.createFlow(*tables, emitInitialState = false).map { }

// One list on a screen, kept by its view model and free of the screen's
// life: its pages, the question they answer (an order, a filter), the
// counts of the last two generations and the screen's first visible row.
//
// A write to what the list reads makes a new generation only once the
// current one has shown its first page: one that comes while a refresh
// reads is held and applied right after it shows, so a storm of writes
// never keeps a refresh from showing. A new question starts again at once,
// from the top.
@OptIn(ExperimentalCoroutinesApi::class)
class ListHandle<Q, T : Any>(
    private val scope: CoroutineScope,
    first: Q,
    private val loaderOf: (Q) -> ListLoader<T>,
    private val reads: ListReads,
    // What says the list may have changed: by default writes to its tables.
    private val changes: (Q, ListLoader<T>) -> Flow<*>,
    initialKey: RowKey? = null,
    config: PagingConfig = LIST_PAGING,
    // Whether a question's list is filtered in Kotlin, so its first rows
    // show before its count is known.
    private val filtered: (Q) -> Boolean = { false },
) {
    private val lock = Any()
    private var question: Q = first
    private var loaderNow: ListLoader<T> = loaderOf(first)
    private val questions = MutableStateFlow(first)
    private var generation = 0L
    private var current: KeysetPagingSource<T>? = null
    private var currentGen = 0L
    private var presentedGen = 0L
    private var pending = false
    private var top = false
    private var firstRows = filtered(first)
    @Volatile private var hintKey: RowKey? = initialKey
    private val metas = ArrayDeque<ListMeta>()
    private var reach: Reach<T>? = null
    private var reachShown: Reach<T>? = null

    private val _shown = MutableStateFlow<ListMeta?>(null)

    // The count and letters of the generation on screen.
    val shown: StateFlow<ListMeta?> = _shown.asStateFlow()

    private val _reanchors = MutableSharedFlow<Reanchor>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val reanchors: SharedFlow<Reanchor> = _reanchors.asSharedFlow()

    // The question now asked.
    val asked: StateFlow<Q> = questions.asStateFlow()

    // The loader of the question now asked.
    val loader: ListLoader<T> get() = synchronized(lock) { loaderNow }

    val pages: Flow<PagingData<ListItem<T>>> =
        Pager(config, initialKey?.let { PageKey.At(it) }) { newSource() }.flow
            .map { data -> data.map { item -> seen(item.generation); item } }
            .cachedIn(scope)

    init {
        scope.launch {
            questions.flatMapLatest { q -> changes(q, synchronized(lock) { loaderNow }) }.collect { changed() }
        }
    }

    // A new order or filter: the list reads again from the top.
    fun ask(next: Q) {
        val source = synchronized(lock) {
            if (next == question) return
            question = next
            loaderNow = loaderOf(next)
            top = true
            firstRows = filtered(next)
            current
        }
        questions.value = next
        source?.invalidate()
    }

    // The screen's first visible row, or null while that is a placeholder.
    fun visible(key: RowKey?) {
        hintKey = key
    }

    // The count and letters a generation published, while it is one of the
    // last two.
    fun metaFor(gen: Long?): ListMeta? = synchronized(lock) { metas.lastOrNull { it.generation == gen } }

    // The generation on screen.
    val presented: Long get() = synchronized(lock) { presentedGen }

    // The newest generation made so far: any made after now began its
    // refresh after now.
    val generationNow: Long get() = synchronized(lock) { generation }

    // The whole list as the generation on screen read it, in list order,
    // when its pages reached both ends with at most QUEUE_WINDOW rows.
    fun whole(): List<Keyed<T>>? = synchronized(lock) {
        val shown = reachShown ?: return null
        if (!shown.top || !shown.end || shown.over) return null
        shown.rows.values.sortedWith { a, b -> loaderNow.order.compare(a.key, b.key) }
    }

    // Reads the list again, as a write would.
    fun refresh() = changed()

    // Each generation reads through a loader of its own, so one that holds
    // something while it reads (a playlist's pinned set) lets it go when
    // its generation ends.
    private fun newSource(): KeysetPagingSource<T> = synchronized(lock) {
        val gen = ++generation
        val loader = if (gen == 1L) loaderNow else loaderOf(question).also { loaderNow = it }
        val source = KeysetPagingSource(loader, gen, reads, ::published, { hintKey }, ::reanchored, fromTop = top, firstRows = firstRows, loaded = ::loaded)
        source.registerInvalidatedCallback { retire(loader) }
        top = false
        firstRows = false
        current = source
        currentGen = gen
        pending = false
        reach = Reach(gen)
        source
    }

    private fun retire(loader: ListLoader<T>) {
        if (loader is Retiring) RETIRE_SCOPE.launch { loader.retire() }
    }

    // The view model is done with the list: the loader in use lets go.
    fun close() {
        synchronized(lock) { loaderNow }.let(::retire)
    }

    private fun published(meta: ListMeta) {
        val shownNow = synchronized(lock) {
            metas.removeAll { it.generation == meta.generation }
            metas.addLast(meta)
            while (metas.size > 2) metas.removeFirst()
            // A new filter's first rows, shown, are counted next.
            if (meta.count == null && meta.generation == currentGen) pending = true
            meta.generation == presentedGen
        }
        if (shownNow) _shown.value = meta
        // An empty page shows nothing to wait for.
        if (meta.shown == 0) seen(meta.generation)
    }

    private fun reanchored(reanchor: Reanchor) {
        _reanchors.tryEmit(reanchor)
    }

    private fun loaded(read: PageRead<T>) = synchronized(lock) {
        val r = reach?.takeIf { it.generation == read.generation } ?: return@synchronized
        if (read.top) r.top = true
        if (read.end) r.end = true
        if (r.over) return@synchronized
        read.rows.forEach { r.rows[it.key.token] = it }
        if (r.rows.size > QUEUE_WINDOW) {
            r.over = true
            r.rows.clear()
        }
    }

    // The screen has a generation's rows: a write held back for it reads now.
    private fun seen(gen: Long) {
        var meta: ListMeta? = null
        val again = synchronized(lock) {
            if (gen <= presentedGen) return
            presentedGen = gen
            meta = metas.lastOrNull { it.generation == gen }
            if (reach?.generation == gen) reachShown = reach
            (pending && gen == currentGen).also { if (it) pending = false }
        }
        meta?.let { _shown.value = it }
        if (again) scope.launch { synchronized(lock) { current }?.invalidate() }
    }

    private fun changed() {
        val source = synchronized(lock) {
            if (presentedGen >= currentGen) {
                current
            } else {
                pending = true
                null
            }
        }
        source?.invalidate()
    }

    private class Reach<T>(val generation: Long) {
        var top = false
        var end = false
        var over = false
        val rows = LinkedHashMap<String, Keyed<T>>()
    }
}

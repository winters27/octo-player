package app.winters.octo.ui.folders

import app.winters.octo.folders.LevelRow
import app.winters.octo.folders.ServerFolders
import app.winters.octo.folders.ServerLevel
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.ListStart
import app.winters.octo.sort.SortOrder
import app.winters.octo.ui.common.LoadState
import app.winters.octo.ui.online.loadOnline
import app.winters.octo.ui.paging.ListHandle
import app.winters.octo.ui.paging.ListReads
import app.winters.octo.ui.paging.RowKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// The server levels a folders page keeps: each level on the way to the
// folder shown, by folder id ("" for the server's top), read under an
// owner of its own, so two levels on the way never share rows and going
// back up shows one at once, and paged from its rows. A level read again
// keeps showing its last read until the new one is whole, then turns to it
// around the row in view and lets the old one go; one that fails then keeps
// the last read. A new order keys every level's songs anew in place, a page
// at a time, and each then shows from its top. A level no longer on the way
// is let go, rows and all, and so is every level when the page is done.
internal class ServerLevelPages(
    private val scope: CoroutineScope,
    private val server: ServerFolders,
    private val newOwner: () -> String,
    private val reads: ListReads,
    // What says a level's rows may have changed: writes to these tables.
    private val changes: (Array<String>) -> Flow<*>,
    // The folder ids of the levels on the way now.
    private val onTheWay: () -> Set<String>,
    // Where rows are let go, beyond the page's own life.
    private val releasing: CoroutineScope,
    // How a level's songs are ordered, the same for every level.
    private val order: StateFlow<SortOrder>,
) {
    private val _levels = MutableStateFlow<Map<String, LoadState<ServerLevelPage>>>(emptyMap())
    val levels: StateFlow<Map<String, LoadState<ServerLevelPage>>> = _levels.asStateFlow()

    // Each level read whole so far and its page's loader, by folder id, and
    // each read under way. A level's songs are keyed for a new order, and
    // the page turned to a new read, one at a time (`keying`).
    private class Shown(val view: LevelInView, val handle: ListHandle<SortOrder, LevelRow>) {
        val keying = Mutex()
    }

    private val lock = Any()
    private val shown = HashMap<String, Shown>()
    private val reading = HashMap<String, Job>()

    init {
        // A new order keys each level kept anew; a newer one stops a keying
        // under way, which the next keys again whole.
        scope.launch { order.collectLatest { now -> synchronized(lock) { shown.values.toList() }.forEach { keyTo(it, now) } } }
    }

    // Reads a level the first time it is shown, or again with `again` (a
    // retry, a pull). Answers the read under way, or null when none started.
    fun load(id: String?, again: Boolean = false): Job? {
        val key = id.orEmpty()
        val known = _levels.value[key]
        if (!again && (known is LoadState.Ready || known == LoadState.Loading)) return null
        val before = synchronized(lock) {
            if (reading[key]?.isActive == true) return null
            shown[key]
        }
        if (before == null) _levels.update { it + (key to LoadState.Loading) }
        val owner = newOwner()
        val job = scope.launch {
            val readIn = order.value
            val loaded = loadOnline { server.read(id, owner, readIn) }
            val level = (loaded as? LoadState.Ready)?.data
            if (level == null) {
                val failed = loaded as? LoadState.Failed ?: return@launch
                if (before == null) _levels.update { if (key in it) it + (key to failed) else it }
                return@launch
            }
            if (key !in onTheWay()) {
                releasing.launch { server.release(owner) }
                return@launch
            }
            val old = synchronized(lock) { shown[key] }
            if (old != null) {
                val was = old.keying.withLock {
                    val was = old.view.now.owner
                    // An order chosen while it was read.
                    val now = order.value
                    if (now != readIn) server.rekey(level, now)
                    old.view.turn(server.loader(owner), level, now)
                    old.handle.show(now)
                    was
                }
                _levels.update { it + (key to LoadState.Ready(ServerLevelPage(id, level, old.handle))) }
                releasing.launch { server.release(was) }
            } else {
                val view = LevelInView(server.loader(owner), level, readIn)
                val handle = ListHandle(scope, readIn, { view }, reads, { _, loader -> changes(loader.tables) })
                val made = Shown(view, handle)
                synchronized(lock) { shown[key] = made }
                _levels.update { it + (key to LoadState.Ready(ServerLevelPage(id, level, handle))) }
                // An order chosen while it was read.
                keyTo(made, order.value)
            }
        }
        synchronized(lock) { reading[key] = job }
        job.invokeOnCompletion { synchronized(lock) { if (reading[key] === job) reading.remove(key) } }
        return job
    }

    // Keys a level's songs for `order` when they are keyed for another, then
    // shows it from its top.
    private suspend fun keyTo(level: Shown, order: SortOrder) = level.keying.withLock {
        val view = level.view
        if (view.keyedIn == order) return@withLock
        server.rekey(view.level, order)
        view.keyedIn = order
        level.handle.show(order)
    }

    // Shows a level as it is keyed now: from its top in a new order, or
    // around the row in view in the same one.
    private fun ListHandle<SortOrder, LevelRow>.show(order: SortOrder) {
        if (asked.value == order) refresh() else ask(order)
    }

    // What a tap on `song` of level `id` plays, in the order the level
    // shows: its songs named one by one when it shows at most QUEUE_WINDOW,
    // else the level itself in that order, from the row tapped (`key`).
    // Read while no new order is being keyed in. Null for a level not kept.
    suspend fun tapped(id: String?, song: String, key: RowKey): Pair<ListSpec, ListStart>? {
        val level = synchronized(lock) { shown[id.orEmpty()] } ?: return null
        return level.keying.withLock {
            val view = level.view
            server.songs(view.now.owner).small()?.let { rows -> ListSpec.Ids(rows.map { it.row.id }) to ListStart.Row(song, null) }
                ?: (ListSpec.ServerFolder(id, recursive = false, order = view.keyedIn) to ListStart.Row(song, key))
        }
    }

    // Every level kept is read again.
    fun reloadAll(): List<Job> = _levels.value.keys.mapNotNull { load(it.ifEmpty { null }, again = true) }

    // Lets go of each level no longer on the way: its read stops, and its
    // rows go.
    fun keepOnTheWay() {
        val keep = onTheWay()
        val gone = synchronized(lock) {
            reading.keys.filter { it !in keep }.forEach { reading.remove(it)?.cancel() }
            shown.keys.filter { it !in keep }.mapNotNull { shown.remove(it) }
        }
        _levels.update { levels -> levels.filterKeys { it in keep } }
        letGo(gone)
    }

    // The page is done: every level goes.
    fun close() {
        val gone = synchronized(lock) {
            reading.values.forEach { it.cancel() }
            reading.clear()
            shown.values.toList().also { shown.clear() }
        }
        letGo(gone)
    }

    private fun letGo(gone: List<Shown>) {
        gone.forEach { it.handle.close() }
        if (gone.isNotEmpty()) releasing.launch { gone.forEach { server.release(it.view.now.owner) } }
    }
}

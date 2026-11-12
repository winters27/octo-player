package app.winters.octo.ui.online

import app.winters.octo.catalog.ServerHeadEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.discovery.ServerFacts
import app.winters.octo.discovery.ServerListKind
import app.winters.octo.discovery.ServerReads
import app.winters.octo.data.userMessage
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.ui.common.LoadState
import app.winters.octo.ui.paging.Keyed
import app.winters.octo.ui.paging.ListHandle
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.ListReads
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.StaticLoader
import app.winters.octo.ui.paging.Survey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

// Where a page's read of a server list is let go of once the page is gone.
private val RELEASE = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// What a server list's page shows above its songs: the read's heading, the
// facts of its rows, and whose rows they are.
data class ServerShown(val head: ServerHeadEntity?, val facts: ServerFacts, val owner: String?)

// One page's read of a server list (an album, an Octo list, a station),
// kept in its own rows and shown a page at a time. A read again (a pull, a
// song from it arriving in the library) goes into new rows, and the page
// turns to them once they are whole, keeping the last read meanwhile.
class ServerPage(
    private val scope: CoroutineScope,
    private val lists: ServerReads,
    private val owners: WorkOwners,
    reads: ListReads,
    private val kind: ServerListKind,
    private val id: String,
    // For a list the server made anew since it was shown: what to do, and
    // the words to show in its place.
    private val gone: (() -> String)? = null,
) {
    private val shownNow = SwitchedLoader(StaticLoader(emptyList()))
    @Volatile private var owner: String? = null

    // The rows in pages, in the read's order.
    val rows: ListHandle<Unit, TrackEntity> = ListHandle(scope, Unit, { shownNow }, reads, { _, _ -> emptyFlow<Unit>() })

    private val _state = MutableStateFlow<LoadState<ServerShown>>(LoadState.Loading)
    val state: StateFlow<LoadState<ServerShown>> = _state.asStateFlow()

    // The rows shown now, for a walk of them.
    val loader: ListLoader<TrackEntity> get() = shownNow

    // Reads the list from the start, showing loading meanwhile.
    fun reload() {
        _state.value = LoadState.Loading
        scope.launch { read(keep = false) }
    }

    // Reads the list again; the page keeps what it shows unless the read
    // is whole.
    suspend fun refresh() = read(keep = true)

    // With no server signed in the page shows no songs, as before.
    private suspend fun read(keep: Boolean) {
        val next = owners.vm()
        val got: LoadState<ServerShown> = try {
            val head = lists.read(kind, id, next)
            LoadState.Ready(if (head == null) ServerShown(null, ServerFacts(0, null, 0), null) else ServerShown(head, lists.facts(next), next))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SubsonicException.NotFound) {
            LoadState.Failed(gone?.invoke() ?: e.userMessage())
        } catch (e: Exception) {
            LoadState.Failed(e.userMessage())
        }
        val shown = (got as? LoadState.Ready)?.data
        if (shown != null) {
            val old = owner
            owner = shown.owner
            shownNow.now = shown.owner?.let(lists::loader) ?: StaticLoader(emptyList())
            rows.refresh()
            _state.value = got
            old?.let { lists.clear(it) }
        } else {
            lists.clear(next)
            if (!keep || _state.value !is LoadState.Ready) _state.value = got
        }
    }

    // Whether any find shown passes `test`, a page at a time.
    suspend fun anyFind(test: (String) -> Boolean): Boolean = owner?.let { lists.anyFind(it, test) } ?: false

    // The finds shown, a page of songs at a time.
    suspend fun findPages(each: suspend (List<TrackEntity>) -> Unit) {
        owner?.let { lists.findPages(it, each) }
    }

    fun close() {
        rows.close()
        owner?.let { RELEASE.launch { lists.clear(it) } }
    }
}

// A page's loader that turns to a new read once it is whole.
class SwitchedLoader(@Volatile var now: ListLoader<TrackEntity>) : ListLoader<TrackEntity> {
    override val tables: Array<String> get() = now.tables
    override val order: Comparator<RowKey> get() = now.order
    override val songsOnce: Boolean get() = now.songsOnce
    override suspend fun count(): Int = now.count()
    override suspend fun survey(anchor: RowKey?): Survey = now.survey(anchor)
    override suspend fun after(key: RowKey?, limit: Int): List<Keyed<TrackEntity>> = now.after(key, limit)
    override suspend fun before(key: RowKey, limit: Int): List<Keyed<TrackEntity>> = now.before(key, limit)
    override suspend fun keyAt(position: Int): RowKey? = now.keyAt(position)
    override suspend fun keyOf(trackId: String): RowKey? = now.keyOf(trackId)
    override suspend fun rekey(key: RowKey): RowKey? = now.rekey(key)
    override suspend fun present(ids: List<String>): Set<String> = now.present(ids)
    override suspend fun small(): List<Keyed<TrackEntity>>? = now.small()
    override fun trackIdOf(row: TrackEntity): String? = row.id
}

package app.winters.octo.ui.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.data.userMessage
import app.winters.octo.folders.LEVEL_TABLE
import app.winters.octo.folders.LevelRow
import app.winters.octo.folders.SERVER_SONG_LIMIT
import app.winters.octo.folders.ServerFolders
import app.winters.octo.folders.ServerLevel
import app.winters.octo.folders.ServerLevelLoader
import app.winters.octo.folders.ServerName
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.ListStart
import app.winters.octo.playback.NOTHING_TO_PLAY
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.QUEUE_WINDOW
import app.winters.octo.playback.Shown
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.SortSettings
import app.winters.octo.ui.common.LoadState
import app.winters.octo.ui.online.loadOnline
import app.winters.octo.ui.paging.Keyed
import app.winters.octo.ui.paging.LibraryReads
import app.winters.octo.ui.paging.ListHandle
import app.winters.octo.ui.paging.ListItem
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.ListLoaders
import app.winters.octo.ui.paging.PhoneFolder
import app.winters.octo.ui.paging.PhoneTree
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.Survey
import app.winters.octo.ui.paging.tableChanges
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.NumberFormat
import javax.inject.Inject
import kotlin.random.Random

// A place in the folders: the page listing both sources, a folder on the
// phone by its path below the phone's top, or a folder on the server (the
// server's top when the id is null).
sealed interface FolderStop {
    data object Top : FolderStop
    data class Phone(val path: List<String>) : FolderStop
    data class Server(val id: String?, val name: String) : FolderStop
}

// Where the folders come from, once both are known. `phone` is null when
// the phone has no music in the library; `server` when none is signed in.
class FolderSources(val phone: PhoneTree?, val server: String?) {
    // Where the page starts: both sources side by side, or straight into
    // the only one.
    val top: FolderStop? = when {
        phone != null && server != null -> FolderStop.Top
        phone != null -> FolderStop.Phone(emptyList())
        server != null -> FolderStop.Server(null, server)
        else -> null
    }

    // What to call the phone's top: the folder it starts in, when the music
    // is all inside one.
    val phoneName: String get() = phone?.name?.ifEmpty { null } ?: "This phone"
}

// The phone's folders, or where that stands.
private sealed interface PhoneState {
    data object Waiting : PhoneState
    data object Empty : PhoneState
    class Ready(val tree: PhoneTree) : PhoneState
}

// A phone folder's own songs as the page shows them: read whole when it
// has at most QUEUE_WINDOW of them, else in pages of its ordered copy.
sealed interface FolderSongs {
    data object None : FolderSongs
    data class Held(val songs: List<TrackEntity>) : FolderSongs
    class Paged(val handle: ListHandle<FolderQuestion, TrackEntity>) : FolderSongs

    // The copy could not be written: the words to show.
    data class Problem(val message: String) : FolderSongs
}

// What a big folder's paged songs answer: which folder, in which order.
data class FolderQuestion(val path: List<String>, val order: SortOrder)

// A server folder's level as the page shows it: the folder (null for the
// server's top), the read it pages from, its folders in the server's order
// and then its songs in the order chosen, under an owner of its own.
class ServerLevelPage(val id: String?, val level: ServerLevel, val handle: ListHandle<SortOrder, LevelRow>)

// Where a phone folder's copy is let go of once the page is gone.
private val RELEASE = CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FoldersViewModel @Inject constructor(
    private val db: CatalogDatabase,
    merge: CatalogMerge,
    private val server: ServerFolders,
    private val playback: PlaybackConnection,
    private val sorting: SortSettings,
    private val loaders: ListLoaders,
    private val reads: LibraryReads,
    private val owners: WorkOwners,
) : ViewModel() {
    // How a folder's own songs are ordered, the same for every folder.
    val order: StateFlow<SortOrder> =
        sorting.order(SortList.FolderSongs).stateIn(viewModelScope, SharingStarted.Eagerly, SortList.FolderSongs.default)

    fun setOrder(order: SortOrder) {
        viewModelScope.launch { sorting.set(SortList.FolderSongs, order) }
    }

    // The server levels on the way to the folder shown, by folder id (""
    // for the top), each read under an owner of its own and paged from its
    // rows, its songs in the folders' order, and let go once off the way.
    private val serverLevels = ServerLevelPages(
        viewModelScope, server, owners::vm, reads, { tables -> db.tableChanges(tables) }, { onTheWay() }, RELEASE, order,
    )
    val levels: StateFlow<Map<String, LoadState<ServerLevelPage>>> = serverLevels.levels

    // What changes the phone's folders: the phone's songs, and a swap that
    // has finished.
    private val phoneChanges = combine(
        db.phoneScanDao().phoneStamp().distinctUntilChanged(),
        merge.swapStamp.filter { it % 2 == 0L }.distinctUntilChanged(),
    ) { phone, swap -> phone to swap }

    // The tree holds counts per folder and each folder's first song, read
    // from the library's stored places: never the songs.
    private val phone: StateFlow<PhoneState> = phoneChanges.conflate()
        .map { loaders.folders.tree()?.let(PhoneState::Ready) ?: PhoneState.Empty }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PhoneState.Waiting)

    // Null until both sources are known, so the page does not flip between
    // layouts as they arrive.
    val sources: StateFlow<FolderSources?> = combine(phone, server.name) { phone, name ->
        if (phone == PhoneState.Waiting || name == ServerName.Unknown) {
            null
        } else {
            FolderSources((phone as? PhoneState.Ready)?.tree, (name as? ServerName.Known)?.name).also(::startOver)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // The folders gone into below the start, the deepest last.
    private val _trail = MutableStateFlow<List<FolderStop>>(emptyList())
    val trail: StateFlow<List<FolderStop>> = _trail.asStateFlow()

    // Where the page last started. When that changes, say the server was
    // signed out, the folders gone into belong to the old start.
    private var lastTop: FolderStop? = null

    private fun startOver(sources: FolderSources) {
        if (lastTop != null && sources.top != lastTop) _trail.value = emptyList()
        lastTop = sources.top
        letGoOffTheWay()
    }

    // The phone folder shown now, by its path, or null elsewhere.
    private val phonePath: StateFlow<List<String>?> = combine(sources, _trail) { known, trail ->
        val top = known?.top ?: return@combine null
        (trail.lastOrNull() ?: top as? FolderStop.Phone)?.let { (it as? FolderStop.Phone)?.path }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // A big folder's songs are written in order to one of two copies, the
    // page reading one while the next is written to the other; the page's
    // loader turns to the new copy once it is whole.
    private val copies = listOf(owners.vm(), owners.vm())
    private var copyNow = 0
    private val shownCopy = FolderCopyLoader(loaders.folders.loader(copies[0]))
    private var handle: ListHandle<FolderQuestion, TrackEntity>? = null

    private val _songs = MutableStateFlow<FolderSongs>(FolderSongs.None)
    val songs: StateFlow<FolderSongs> = _songs.asStateFlow()

    init {
        // The level's songs are read again on a new folder, a new order and
        // a change to the phone's songs, the latest only.
        viewModelScope.launch {
            combine(phonePath, order, phone) { path, order, state -> Triple(path, order, state) }
                .conflate()
                .collectLatest { (path, order, state) -> showLevel(path, order, (state as? PhoneState.Ready)?.tree) }
        }
    }

    private suspend fun showLevel(path: List<String>?, order: SortOrder, tree: PhoneTree?) {
        val node = path?.let { tree?.root?.at(it) }
        if (node == null || node.own == 0) {
            _songs.value = FolderSongs.None
            return
        }
        loaders.folders.songs(node, recursive = false, order = order, limit = QUEUE_WINDOW)?.let {
            _songs.value = FolderSongs.Held(it)
            return
        }
        val next = 1 - copyNow
        try {
            loaders.folders.copy(copies[next], listOf(node), order, ranked = false)
        } catch (e: NotEnoughSpace) {
            _songs.value = FolderSongs.Problem(e.userMessage())
            return
        }
        copyNow = next
        shownCopy.now = loaders.folders.loader(copies[next])
        // The same folder in the same order reads again around the row in
        // view; another starts at its top.
        val question = FolderQuestion(path.orEmpty(), order)
        val paged = handle
        if (paged == null) {
            handle = ListHandle(viewModelScope, question, { shownCopy }, reads, { _, _ -> emptyFlow<Unit>() })
        } else if (paged.asked.value == question) {
            paged.refresh()
        } else {
            paged.ask(question)
        }
        _songs.value = FolderSongs.Paged(handle!!)
    }

    override fun onCleared() {
        handle?.close()
        serverLevels.close()
        RELEASE.launch { copies.forEach { loaders.release(it) } }
    }

    // The folder ids of the server levels on the way now.
    private fun onTheWay(): Set<String> =
        (listOfNotNull(lastTop) + _trail.value).filterIsInstance<FolderStop.Server>().mapTo(HashSet()) { it.id.orEmpty() }

    private fun letGoOffTheWay() = serverLevels.keepOnTheWay()

    // Whether a Play or Shuffle is gathering songs from the server.
    private val _gathering = MutableStateFlow(false)
    val gathering: StateFlow<Boolean> = _gathering.asStateFlow()

    // A quiet line under the buttons: the song limit was reached, or the
    // server could not be asked.
    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note.asStateFlow()

    fun open(stop: FolderStop) {
        _note.value = null
        _trail.update { it + stop }
    }

    // Goes back to one of the folders on the way, or the start for -1.
    fun goTo(depth: Int) {
        _note.value = null
        _trail.update { it.take(depth + 1) }
        letGoOffTheWay()
    }

    // Goes up one folder. False at the start, where back leaves the page.
    fun up(): Boolean {
        if (_trail.value.isEmpty()) return false
        _note.value = null
        _trail.update { it.dropLast(1) }
        letGoOffTheWay()
        return true
    }

    // Reads a server level the first time it is shown, or again on retry.
    fun load(id: String?, again: Boolean = false) {
        serverLevels.load(id, again)
    }

    // A pull on the page: every server level on the way is read again.
    fun reloadServer() {
        serverLevels.reloadAll()
    }

    // Plays a server folder's own songs from the one tapped, in the order
    // shown: named one by one when it shows at most QUEUE_WINDOW, else the
    // whole folder, which the play reads again into its own rows.
    fun playFrom(level: ServerLevelPage, item: ListItem<LevelRow>) {
        val song = (item.row as? LevelRow.Song)?.track ?: return
        viewModelScope.launch {
            val (spec, start) = serverLevels.tapped(level.id, song.id, item.key) ?: return@launch
            playback.playList(spec, start, shownOverWindow = spec !is ListSpec.Ids)
        }
    }

    // Plays every song under a phone folder: each folder inside it in turn,
    // then its own songs. Play starts at the first song the tree knows;
    // Shuffle at a song picked at random over all of them, by one probe.
    fun playPhone(path: List<String>, node: PhoneFolder, shuffle: Boolean) {
        val spec = ListSpec.Folder(path.joinToString("/"), recursive = true)
        if (!shuffle) {
            val first = node.inPlayOrder().firstOrNull { it.own > 0 }?.first
            playback.playList(spec, first?.let { ListStart.First(it.id, null) })
            return
        }
        viewModelScope.launch {
            val picked = node.songAt(Random.nextInt(node.total.coerceAtLeast(1)))?.let { (folder, k) -> loaders.folders.fileAt(folder, k) }
            playback.playList(spec, picked?.let { ListStart.First(it, null) }, shuffle = true)
        }
    }

    // Plays a folder held whole from the song tapped.
    fun playFrom(songs: List<TrackEntity>, track: TrackEntity) =
        playback.playList(ListSpec.Ids(songs.map { it.id }), ListStart.Row(track.id, null))

    // Plays a big folder's own songs from the row tapped.
    fun playFrom(path: List<String>, item: ListItem<TrackEntity>) =
        playback.playList(
            ListSpec.Folder(path.joinToString("/"), recursive = false, order = order.value),
            ListStart.Row(item.row.id, item.key),
            shownOverWindow = true,
        )

    // Gathers every song under a server folder, up to the limit, and plays
    // them: the play reads them into its own copy and answers once it has,
    // with whether they stopped at the limit or why it could not play.
    fun playServer(id: String?, name: String, shuffle: Boolean) {
        if (_gathering.value) return
        _gathering.value = true
        _note.value = null
        playback.playList(ListSpec.ServerFolder(id), null, shuffle, handlesRefusal = true, shown = Shown(name)) { answer ->
            _gathering.value = false
            _note.value = when {
                answer.refusal == NOTHING_TO_PLAY -> "No songs in this folder."
                answer.refusal != null -> answer.refusal
                answer.cut -> "Playing the first ${NumberFormat.getIntegerInstance().format(SERVER_SONG_LIMIT)} songs in this folder."
                else -> null
            }
        }
    }
}

// A server level's loader as its page reads it: the read last made whole,
// what that read holds, and the order its songs are keyed in. Its rows
// change only when the page turns it to a new read or keys its songs anew,
// which the page then says, or when a song it shows changes.
internal class LevelInView(
    @Volatile var now: ServerLevelLoader,
    @Volatile var level: ServerLevel,
    @Volatile var keyedIn: SortOrder,
) : ListLoader<LevelRow> {
    fun turn(loader: ServerLevelLoader, read: ServerLevel, order: SortOrder) {
        now = loader
        level = read
        keyedIn = order
    }

    override val tables: Array<String> get() = now.tables.filter { it != LEVEL_TABLE }.toTypedArray()
    override val order: Comparator<RowKey> get() = now.order
    override val songsOnce: Boolean get() = now.songsOnce
    override suspend fun count(): Int = now.count()
    override suspend fun survey(anchor: RowKey?): Survey = now.survey(anchor)
    override suspend fun after(key: RowKey?, limit: Int): List<Keyed<LevelRow>> = now.after(key, limit)
    override suspend fun before(key: RowKey, limit: Int): List<Keyed<LevelRow>> = now.before(key, limit)
    override suspend fun keyAt(position: Int): RowKey? = now.keyAt(position)
    override suspend fun keyOf(trackId: String): RowKey? = now.keyOf(trackId)
    override suspend fun rekey(key: RowKey): RowKey? = now.rekey(key)
    override suspend fun present(ids: List<String>): Set<String> = now.present(ids)
    override suspend fun small(): List<Keyed<LevelRow>>? = now.small()
    override fun trackIdOf(row: LevelRow): String? = now.trackIdOf(row)
}

// The page's loader of a big folder's songs: the copy last written whole.
// Its rows change only when the page turns it to a new copy, which the
// page then says.
private class FolderCopyLoader(@Volatile var now: ListLoader<TrackEntity>) : ListLoader<TrackEntity> {
    override val tables: Array<String> get() = emptyArray()
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
    override fun trackIdOf(row: TrackEntity): String? = row.id
}

package app.winters.octo.ui.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.data.userMessage
import app.winters.octo.device.Access
import app.winters.octo.device.DEVICE
import app.winters.octo.device.DeviceLibrary
import app.winters.octo.folders.Collapsed
import app.winters.octo.folders.FolderNode
import app.winters.octo.folders.SERVER_SONG_LIMIT
import app.winters.octo.folders.ServerFolders
import app.winters.octo.folders.ServerLevel
import app.winters.octo.folders.ServerName
import app.winters.octo.folders.buildFolderTree
import app.winters.octo.folders.collapsed
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.SortSettings
import app.winters.octo.ui.common.LoadState
import app.winters.octo.ui.online.loadOnline
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.NumberFormat
import javax.inject.Inject

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
class FolderSources(val phone: Collapsed<TrackEntity>?, val server: String?) {
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

// The phone's songs by folder, or where that stands.
private sealed interface PhoneTree {
    data object Waiting : PhoneTree
    data object Empty : PhoneTree
    class Ready(val tree: Collapsed<TrackEntity>) : PhoneTree
}

// Songs in a phone folder: album by album, each in its own order.
private val phoneSongOrder = compareBy<TrackEntity>({ it.album.lowercase() }, { it.albumId }, { it.albumOrder }, { it.sortKey })

@HiltViewModel
class FoldersViewModel @Inject constructor(
    catalog: CatalogDao,
    library: DeviceLibrary,
    private val server: ServerFolders,
    private val playback: PlaybackConnection,
    private val sorting: SortSettings,
) : ViewModel() {
    // How a folder's own songs are ordered, the same for every folder.
    val order: StateFlow<SortOrder> =
        sorting.order(SortList.FolderSongs).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SortList.FolderSongs.default)

    fun setOrder(order: SortOrder) {
        viewModelScope.launch { sorting.set(SortList.FolderSongs, order) }
    }

    private val phone = combine(catalog.tracks(), library.paths, library.access) { tracks, paths, access ->
        val onPhone = tracks.filter { it.id.startsWith("$DEVICE:") }
        when {
            onPhone.isEmpty() -> PhoneTree.Empty
            // The first scan since the app started has not finished.
            paths == null -> if (access == Access.Granted) PhoneTree.Waiting else PhoneTree.Empty
            else -> {
                val placed = onPhone.mapNotNull { track -> paths[mediaId(track)]?.let { track to it } }
                val where = placed.associate { (track, path) -> track.id to path }
                PhoneTree.Ready(buildFolderTree(placed.map { it.first }, { where.getValue(it.id) }, phoneSongOrder).collapsed())
            }
        }
    }.flowOn(Dispatchers.Default)

    // Null until both sources are known, so the page does not flip between
    // layouts as they arrive.
    val sources: StateFlow<FolderSources?> = combine(phone, server.name) { phone, name ->
        if (phone == PhoneTree.Waiting || name == ServerName.Unknown) {
            null
        } else {
            FolderSources((phone as? PhoneTree.Ready)?.tree, (name as? ServerName.Known)?.name).also(::startOver)
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
    }

    // Server levels loaded so far, by folder id ("" for the top), so going
    // back up shows them at once.
    private val _levels = MutableStateFlow<Map<String, LoadState<ServerLevel>>>(emptyMap())
    val levels: StateFlow<Map<String, LoadState<ServerLevel>>> = _levels.asStateFlow()

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
    }

    // Goes up one folder. False at the start, where back leaves the page.
    fun up(): Boolean {
        if (_trail.value.isEmpty()) return false
        _note.value = null
        _trail.update { it.dropLast(1) }
        return true
    }

    // Loads a server level the first time it is shown, or again on retry.
    fun load(id: String?, again: Boolean = false) {
        val key = id.orEmpty()
        val known = _levels.value[key]
        if (!again && (known is LoadState.Ready || known == LoadState.Loading)) return
        _levels.update { it + (key to LoadState.Loading) }
        viewModelScope.launch {
            val loaded = loadOnline { server.level(id) }
            _levels.update { it + (key to loaded) }
        }
    }

    // Plays every song under a phone folder.
    fun playPhone(node: FolderNode<TrackEntity>, shuffle: Boolean) =
        playback.playTracks(node.allSongs().map { it.id }, 0, shuffle)

    // Plays a list of songs from the one tapped.
    fun playFrom(songs: List<TrackEntity>, track: TrackEntity) =
        playback.playTracks(songs.map { it.id }, songs.indexOf(track).coerceAtLeast(0))

    // Gathers every song under a server folder, up to the limit, and plays them.
    fun playServer(id: String?, shuffle: Boolean) {
        if (_gathering.value) return
        _gathering.value = true
        _note.value = null
        viewModelScope.launch {
            try {
                val gathered = server.songsUnder(id)
                when {
                    gathered == null -> _note.value = "Sign in to your server to play this."
                    gathered.songs.isEmpty() -> _note.value = "No songs in this folder."
                    else -> {
                        playback.playTracks(gathered.songs.map { it.id }, 0, shuffle)
                        if (gathered.limitReached) {
                            val limit = NumberFormat.getIntegerInstance().format(SERVER_SONG_LIMIT)
                            _note.value = "Playing the first $limit songs in this folder."
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _note.value = e.userMessage()
            } finally {
                _gathering.value = false
            }
        }
    }

    private fun mediaId(track: TrackEntity): Long? = track.id.removePrefix("$DEVICE:").toLongOrNull()
}

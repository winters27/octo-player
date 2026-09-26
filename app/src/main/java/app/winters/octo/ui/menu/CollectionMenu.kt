package app.winters.octo.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.UserDao
import app.winters.octo.catalog.byPlayCount
import app.winters.octo.design.GlassSheet
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.discovery.Discovery
import app.winters.octo.listening.PlayHistory
import app.winters.octo.offline.DownloadEntity
import app.winters.octo.offline.OfflineDownloads
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.PlaylistStore
import app.winters.octo.ui.common.Artwork
import app.winters.octo.ui.common.Feedback
import app.winters.octo.ui.common.albums
import app.winters.octo.ui.common.songs
import app.winters.octo.ui.nav.ArtistRoute
import app.winters.octo.ui.playlist.LocalPlaylistSheets
import app.winters.octo.ui.playlist.PlaylistCover
import app.winters.octo.ui.playlist.PlaylistSheet
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// What a collection menu is open for: an album, an artist or a playlist.
sealed interface CollectionTarget {
    data class Album(val id: String) : CollectionTarget
    data class Artist(val id: String) : CollectionTarget
    data class Playlist(val id: String) : CollectionTarget
}

// Which album, artist or playlist the menu is open for, if any. A long
// press on its card or row opens it.
class CollectionMenuState {
    var target by mutableStateOf<CollectionTarget?>(null)
        private set

    // The last one opened, kept after closing so the sheet can still show
    // it while it slides away.
    var last by mutableStateOf<CollectionTarget?>(null)
        private set

    fun open(target: CollectionTarget) {
        this.target = target
        last = target
    }

    fun close() {
        target = null
    }
}

// The choices in a collection's menu, in the order shown.
enum class CollectionAction { Play, Shuffle, PlayNext, AddToQueue, AddToPlaylist, Download, StartRadio, GoToArtist, Rename, Delete }

private val Playing = listOf(CollectionAction.Play, CollectionAction.Shuffle, CollectionAction.PlayNext, CollectionAction.AddToQueue)

// An album can be downloaded when some of its songs are only on a server
// and not downloaded yet.
fun albumActions(canDownload: Boolean): List<CollectionAction> = buildList {
    addAll(Playing)
    add(CollectionAction.AddToPlaylist)
    if (canDownload) add(CollectionAction.Download)
    add(CollectionAction.GoToArtist)
}

// A radio needs a server signed in.
fun artistActions(radio: Boolean): List<CollectionAction> = buildList {
    addAll(Playing)
    if (radio) add(CollectionAction.StartRadio)
}

// An empty playlist has nothing to play, only itself to rename or delete.
fun playlistActions(empty: Boolean): List<CollectionAction> = buildList {
    if (!empty) addAll(Playing)
    add(CollectionAction.Rename)
    add(CollectionAction.Delete)
}

@HiltViewModel
class CollectionMenuViewModel @Inject constructor(
    private val catalog: CatalogDao,
    private val userDao: UserDao,
    private val store: PlaylistStore,
    private val playback: PlaybackConnection,
    private val discovery: Discovery,
    private val offline: OfflineDownloads,
    private val history: PlayHistory,
    private val feedback: Feedback,
) : ViewModel() {
    // Whether an artist can start a radio: only with a server signed in.
    val radio: StateFlow<Boolean> = discovery.available
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Library songs downloaded to the phone, or on their way.
    val kept: StateFlow<Map<String, DownloadEntity>> = offline.byTrack

    fun album(id: String): Flow<AlbumEntity?> = catalog.album(id)
    fun albumTracks(id: String): Flow<List<TrackEntity>> = catalog.albumTracks(id)
    fun artist(id: String): Flow<ArtistEntity?> = catalog.artist(id)
    fun playlist(id: String): Flow<PlaylistSummary?> = store.playlists.map { list -> list.firstOrNull { it.id == id } }

    // The songs, in the order they play: an album's in its order, an
    // artist's album by album, newest first, as their page lists them, and a
    // playlist's in its order.
    suspend fun songIds(target: CollectionTarget): List<String> = when (target) {
        is CollectionTarget.Album -> catalog.albumTrackIds(target.id)
        is CollectionTarget.Artist -> catalog.artistAlbums(target.id).first().flatMap { catalog.albumTrackIds(it.id) }
        is CollectionTarget.Playlist -> userDao.playlistTracks(target.id).first().map { it.track.id }
    }

    fun play(target: CollectionTarget, shuffle: Boolean) {
        viewModelScope.launch { playback.playTracks(songIds(target), 0, shuffle) }
    }

    fun playNext(target: CollectionTarget) {
        viewModelScope.launch { songIds(target).takeIf { it.isNotEmpty() }?.let(playback::playNext) }
    }

    fun addToQueue(target: CollectionTarget) {
        viewModelScope.launch { songIds(target).takeIf { it.isNotEmpty() }?.let(playback::playLast) }
    }

    // Hands the songs on once read, when there are any. Runs here rather
    // than on the menu, which is gone by then.
    fun withSongs(target: CollectionTarget, use: (List<String>) -> Unit) {
        viewModelScope.launch { songIds(target).takeIf { it.isNotEmpty() }?.let(use) }
    }

    fun download(trackIds: List<String>) = offline.download(trackIds)

    // A radio from the artist's most played song, or their first song when
    // none has been played yet.
    fun startRadio(artistId: String) {
        viewModelScope.launch {
            val played = history.tracks.first().filter { it.track.artistId == artistId }
            val seed = byPlayCount(played, 1).firstOrNull()
                ?: songIds(CollectionTarget.Artist(artistId)).firstOrNull()?.let { catalog.track(it) }
            if (seed == null) {
                feedback.show("No songs to start a radio from")
                return@launch
            }
            playRadio(seed, discovery, playback, feedback, "this artist")
        }
    }
}

// The menu for an album, an artist or a playlist, drawn like the song menu.
// `onOpen` goes to a page.
@Composable
fun CollectionMenuHost(state: CollectionMenuState, onOpen: (NavKey) -> Unit, vm: CollectionMenuViewModel = hiltViewModel()) {
    GlassSheet(visible = state.target != null, onDismiss = state::close) {
        when (val target = state.last ?: return@GlassSheet) {
            is CollectionTarget.Album -> AlbumMenu(target, state, vm, onOpen)
            is CollectionTarget.Artist -> ArtistMenu(target, state, vm)
            is CollectionTarget.Playlist -> PlaylistMenu(target, state, vm)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AlbumMenu(target: CollectionTarget.Album, state: CollectionMenuState, vm: CollectionMenuViewModel, onOpen: (NavKey) -> Unit) {
    val album by remember(target) { vm.album(target.id) }.collectAsStateWithLifecycle(null)
    val tracks by remember(target) { vm.albumTracks(target.id) }.collectAsStateWithLifecycle(emptyList())
    val kept by vm.kept.collectAsStateWithLifecycle()
    val shown = album ?: return
    val waiting = tracks.filter { !it.onPhone && it.id !in kept }.map { it.id }
    CollectionHeader(shown.title, shown.artist) { Artwork(shown.artwork, 48.dp, shape = RoundedCornerShape(6.dp)) }
    Spacer(Modifier.height(8.dp))
    CollectionRows(target, albumActions(canDownload = waiting.isNotEmpty()), state, vm) { action ->
        when (action) {
            CollectionAction.Download -> vm.download(waiting)
            CollectionAction.GoToArtist -> onOpen(ArtistRoute(shown.artistId))
            else -> Unit
        }
    }
}

@Composable
private fun ArtistMenu(target: CollectionTarget.Artist, state: CollectionMenuState, vm: CollectionMenuViewModel) {
    val artist by remember(target) { vm.artist(target.id) }.collectAsStateWithLifecycle(null)
    val radio by vm.radio.collectAsStateWithLifecycle()
    val shown = artist ?: return
    CollectionHeader(shown.name, albums(shown.albumCount)) { Artwork(shown.artwork, 48.dp, shape = CircleShape) }
    Spacer(Modifier.height(8.dp))
    CollectionRows(target, artistActions(radio), state, vm) { action ->
        if (action == CollectionAction.StartRadio) vm.startRadio(target.id)
    }
}

@Composable
private fun PlaylistMenu(target: CollectionTarget.Playlist, state: CollectionMenuState, vm: CollectionMenuViewModel) {
    val playlist by remember(target) { vm.playlist(target.id) }.collectAsStateWithLifecycle(null)
    val sheets = LocalPlaylistSheets.current
    val shown = playlist ?: return
    CollectionHeader(shown.name, songs(shown.songCount)) { PlaylistCover(shown.covers, 48.dp, shape = RoundedCornerShape(6.dp)) }
    Spacer(Modifier.height(8.dp))
    CollectionRows(target, playlistActions(empty = shown.songCount == 0), state, vm) { action ->
        when (action) {
            CollectionAction.Rename -> sheets.show(PlaylistSheet.Rename(shown.id, shown.name))
            CollectionAction.Delete -> sheets.show(PlaylistSheet.Delete(shown.id, shown.name, shown.onServer))
            else -> Unit
        }
    }
}

// The rows for the choices. Playing and queueing work the same for every
// kind; `other` does the rest. Every choice closes the menu.
@Composable
private fun CollectionRows(
    target: CollectionTarget,
    actions: List<CollectionAction>,
    state: CollectionMenuState,
    vm: CollectionMenuViewModel,
    other: (CollectionAction) -> Unit,
) {
    val sheets = LocalPlaylistSheets.current
    for (action in actions) {
        val (icon, label) = when (action) {
            CollectionAction.Play -> OctoIcons.Play to "Play"
            CollectionAction.Shuffle -> OctoIcons.Shuffle to "Shuffle"
            CollectionAction.PlayNext -> OctoIcons.PlayNext to "Play next"
            CollectionAction.AddToQueue -> OctoIcons.AddToQueue to "Add to queue"
            CollectionAction.AddToPlaylist -> OctoIcons.AddToPlaylist to "Add to playlist"
            CollectionAction.Download -> OctoIcons.Download to "Download"
            CollectionAction.StartRadio -> OctoIcons.Radio to "Start radio"
            CollectionAction.GoToArtist -> OctoIcons.Artist to "Go to artist"
            CollectionAction.Rename -> OctoIcons.Rename to "Rename"
            CollectionAction.Delete -> OctoIcons.Delete to "Delete"
        }
        MenuRow(icon, label) {
            state.close()
            when (action) {
                CollectionAction.Play -> vm.play(target, shuffle = false)
                CollectionAction.Shuffle -> vm.play(target, shuffle = true)
                CollectionAction.PlayNext -> vm.playNext(target)
                CollectionAction.AddToQueue -> vm.addToQueue(target)
                CollectionAction.AddToPlaylist -> vm.withSongs(target) { ids -> sheets.show(PlaylistSheet.Pick(ids)) }
                else -> other(action)
            }
        }
    }
}

@Composable
private fun CollectionHeader(title: String, subtitle: String, picture: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        picture()
        Column(Modifier.weight(1f)) {
            Text(title, style = OctoType.body, color = OctoColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

package app.winters.octo.ui.menu

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.PIN_LIMIT
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.UserDao
import app.winters.octo.catalog.byPlayCount
import app.winters.octo.design.PopupPages
import app.winters.octo.discovery.Discovery
import app.winters.octo.favourites.FavouriteStore
import app.winters.octo.favourites.PinKey
import app.winters.octo.favourites.PinStore
import app.winters.octo.listening.FavouriteKind
import app.winters.octo.listening.PlayHistory
import app.winters.octo.offline.DownloadEntity
import app.winters.octo.offline.OfflineDownloads
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.PlaylistStore
import app.winters.octo.ui.common.Feedback
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

// The pages of a collection's menu: its actions first, then what some of
// them open in its place.
enum class CollectionPage { Actions, AddToPlaylist, Rename, Delete }

// Which album, artist or playlist the menu is open for, if any. A long
// press on its card or row opens it.
class CollectionMenuState {
    var target by mutableStateOf<CollectionTarget?>(null)
        private set

    // The last one opened, kept after closing so the menu can still show
    // it while it fades away.
    var last by mutableStateOf<CollectionTarget?>(null)
        private set

    // Which page the menu shows. Each opening starts at the actions.
    val pages = PopupPages(CollectionPage.Actions)

    fun open(target: CollectionTarget) {
        pages.reset(CollectionPage.Actions)
        this.target = target
        last = target
    }

    fun close() {
        target = null
    }
}

// The choices in a collection's menu, in the order shown.
enum class CollectionAction {
    Play, Shuffle, PlayNext, AddToQueue, AddToPlaylist, Download, StartRadio,
    AddToFavourites, RemoveFromFavourites, PinToHome, Unpin, MoveToFront,
    GoToArtist, Rename, Delete,
}

// Where a collection stands on Home: not pinned, pinned, or pinned first.
enum class PinSpot { None, Pinned, First }

private val Playing = listOf(CollectionAction.Play, CollectionAction.Shuffle, CollectionAction.PlayNext, CollectionAction.AddToQueue)

// Adding to or removing from favourites.
private fun MutableList<CollectionAction>.favourite(favourite: Boolean) =
    add(if (favourite) CollectionAction.RemoveFromFavourites else CollectionAction.AddToFavourites)

// Pinning to Home, or for a pin, taking it off or moving it to the front.
private fun MutableList<CollectionAction>.pin(spot: PinSpot) {
    if (spot == PinSpot.None) {
        add(CollectionAction.PinToHome)
        return
    }
    add(CollectionAction.Unpin)
    if (spot == PinSpot.Pinned) add(CollectionAction.MoveToFront)
}

// An album can be downloaded when some of its songs are only on a server
// and not downloaded yet.
// A radio needs a server signed in; the desktop offers one from an album
// too, started from its most played song.
fun albumActions(canDownload: Boolean, favourite: Boolean = false, pin: PinSpot = PinSpot.None, radio: Boolean = false): List<CollectionAction> = buildList {
    addAll(Playing)
    if (radio) add(CollectionAction.StartRadio)
    add(CollectionAction.AddToPlaylist)
    if (canDownload) add(CollectionAction.Download)
    favourite(favourite)
    pin(pin)
    add(CollectionAction.GoToArtist)
}

// A radio needs a server signed in.
fun artistActions(radio: Boolean, favourite: Boolean = false, pin: PinSpot = PinSpot.None): List<CollectionAction> = buildList {
    addAll(Playing)
    if (radio) add(CollectionAction.StartRadio)
    favourite(favourite)
    pin(pin)
}

// An empty playlist has nothing to play, only itself to pin, rename or delete.
fun playlistActions(empty: Boolean, pin: PinSpot = PinSpot.None): List<CollectionAction> = buildList {
    if (!empty) addAll(Playing)
    pin(pin)
    add(CollectionAction.Rename)
    add(CollectionAction.Delete)
}

// How a collection's menu groups its actions: playing it, keeping it,
// Home, going to its artist, and last, renaming and deleting.
private val CollectionMenuOrder = listOf(
    listOf(CollectionAction.Play, CollectionAction.Shuffle, CollectionAction.PlayNext, CollectionAction.AddToQueue, CollectionAction.StartRadio),
    listOf(CollectionAction.AddToPlaylist, CollectionAction.AddToFavourites, CollectionAction.RemoveFromFavourites, CollectionAction.Download),
    listOf(CollectionAction.PinToHome, CollectionAction.Unpin, CollectionAction.MoveToFront),
    listOf(CollectionAction.GoToArtist),
    listOf(CollectionAction.Rename, CollectionAction.Delete),
)

// The actions offered, in their groups, leaving out empty groups.
fun collectionMenuGroups(actions: List<CollectionAction>): List<List<CollectionAction>> =
    CollectionMenuOrder.map { group -> group.filter { it in actions } }.filter { it.isNotEmpty() }

// Where a pin is in the row Home shows.
fun pinSpot(shown: List<PinKey>, key: PinKey): PinSpot = when (shown.indexOf(key)) {
    -1 -> PinSpot.None
    0 -> PinSpot.First
    else -> PinSpot.Pinned
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
    private val favourites: FavouriteStore,
    private val pins: PinStore,
) : ViewModel() {
    val favouriteAlbums: StateFlow<Set<String>> = favourites.albums
    val favouriteArtists: StateFlow<Set<String>> = favourites.artists

    // The pins Home shows, in row order.
    val pinned: StateFlow<List<PinKey>> = pins.shown

    fun setFavourite(kind: FavouriteKind, id: String, favourite: Boolean) = favourites.set(kind, id, favourite)

    // Pins to the end of the row on Home. A full row is said, since the pin was asked for.
    fun pin(key: PinKey) {
        viewModelScope.launch {
            if (!pins.pin(key)) feedback.show("Home holds up to $PIN_LIMIT pins. Unpin one first.")
        }
    }

    fun unpin(key: PinKey) {
        viewModelScope.launch { pins.unpin(key) }
    }

    fun moveToFront(key: PinKey) {
        viewModelScope.launch { pins.moveToFront(key) }
    }

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
    // A radio from an album: its most played song here, else its first.
    fun startAlbumRadio(albumId: String) {
        viewModelScope.launch {
            val played = history.tracks.first().filter { it.track.albumId == albumId }
            val seed = byPlayCount(played, 1).firstOrNull() ?: catalog.albumTracks(albumId).first().firstOrNull()
            if (seed == null) {
                feedback.show("No songs to start a radio from")
                return@launch
            }
            playRadio(seed, discovery, playback, feedback, "this album")
        }
    }

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


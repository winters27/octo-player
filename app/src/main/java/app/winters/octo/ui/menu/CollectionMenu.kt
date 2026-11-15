package app.winters.octo.ui.menu

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.PIN_LIMIT
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.catalog.UserDao
import app.winters.octo.catalog.rawQuery
import app.winters.octo.data.AlbumUpgrade
import app.winters.octo.data.Upgrades
import app.winters.octo.design.PopupPages
import app.winters.octo.favourites.FavouriteStore
import app.winters.octo.favourites.PinKey
import app.winters.octo.favourites.PinStore
import app.winters.octo.listening.FavouriteKind
import app.winters.octo.listening.PlayedIn
import app.winters.octo.offline.OfflineDownloads
import app.winters.octo.playback.ArtistOrder
import app.winters.octo.playback.LibraryRadio
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.PlaylistStore
import app.winters.octo.playlists.PlaylistFolderStore
import app.winters.octo.playback.RadioAround
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import app.winters.octo.playback.RadioSeeds
import app.winters.octo.playback.Shown
import app.winters.octo.playback.queueAddWords
import app.winters.octo.ui.common.Feedback
import app.winters.octo.ui.paging.SqlValue
import app.winters.octo.ui.paging.tableChanges
import app.winters.octo.ui.playlist.copiedMessage
import app.winters.octo.ui.playlist.playlistCopyName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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
enum class CollectionPage { Actions, AddToPlaylist, Rename, Delete, FindFlac, MoveToFolder }

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
    Play, Shuffle, PlayNext, AddToQueue, AddToPlaylist, Download, FindFlac, StartRadio,
    AddToFavourites, RemoveFromFavourites, PinToHome, Unpin, MoveToFront,
    GoToArtist, Rename, Duplicate, Delete, MoveToFolder,
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
// too, started from its most played song. `lossy` is how many of its songs
// an Octo server could look for a FLAC of; none leaves the choice out.
fun albumActions(
    canDownload: Boolean,
    favourite: Boolean = false,
    pin: PinSpot = PinSpot.None,
    radio: Boolean = false,
    lossy: Int = 0,
): List<CollectionAction> = buildList {
    addAll(Playing)
    if (radio) add(CollectionAction.StartRadio)
    add(CollectionAction.AddToPlaylist)
    if (canDownload) add(CollectionAction.Download)
    if (lossy > 0) add(CollectionAction.FindFlac)
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

// An empty playlist has nothing to play, only itself to pin, rename, copy
// or delete. A family playlist is renamed and deleted only by a family
// manager (`renames`, `deletes`).
// One that can be filed while folders are offered `moves` to a folder.
fun playlistActions(
    empty: Boolean,
    pin: PinSpot = PinSpot.None,
    renames: Boolean = true,
    deletes: Boolean = true,
    moves: Boolean = false,
): List<CollectionAction> = buildList {
    if (!empty) addAll(Playing)
    pin(pin)
    if (renames) add(CollectionAction.Rename)
    if (moves) add(CollectionAction.MoveToFolder)
    add(CollectionAction.Duplicate)
    if (deletes) add(CollectionAction.Delete)
}

// How a collection's menu groups its actions: playing it, keeping it,
// Home, going to its artist, and last, renaming, copying and deleting.
private val CollectionMenuOrder = listOf(
    listOf(CollectionAction.Play, CollectionAction.Shuffle, CollectionAction.PlayNext, CollectionAction.AddToQueue, CollectionAction.StartRadio),
    listOf(CollectionAction.AddToPlaylist, CollectionAction.AddToFavourites, CollectionAction.RemoveFromFavourites, CollectionAction.Download, CollectionAction.FindFlac),
    listOf(CollectionAction.PinToHome, CollectionAction.Unpin, CollectionAction.MoveToFront),
    listOf(CollectionAction.GoToArtist),
    listOf(CollectionAction.Rename, CollectionAction.MoveToFolder, CollectionAction.Duplicate, CollectionAction.Delete),
)

// The actions offered, in their groups, leaving out empty groups.
fun collectionMenuGroups(actions: List<CollectionAction>): List<List<CollectionAction>> =
    CollectionMenuOrder.map { group -> group.filter { it in actions } }.filter { it.isNotEmpty() }

// An album's songs as Add to playlist takes them: with its songs found
// online while the server keeps them in playlists (`finds`).
fun albumAddSpec(albumId: String, finds: Boolean): ListSpec = ListSpec.Album(albumId, withFinds = finds)

// Where a pin is in the row Home shows.
fun pinSpot(shown: List<PinKey>, key: PinKey): PinSpot = when (shown.indexOf(key)) {
    -1 -> PinSpot.None
    0 -> PinSpot.First
    else -> PinSpot.Pinned
}

// Where the pin of `key` is in the row, as that changes, looked for by the
// id it names now: a menu opened on an album's or an artist's old id while
// a save is part way finds its pin under the new id.
fun pinSpotOf(pins: PinStore, key: PinKey): Flow<PinSpot> = combine(pins.shown, pins.current(key)) { shown, now -> pinSpot(shown, now) }

@HiltViewModel
class CollectionMenuViewModel @Inject constructor(
    private val catalog: CatalogDao,
    private val userDao: UserDao,
    private val store: PlaylistStore,
    private val playback: PlaybackConnection,
    private val libraryRadio: LibraryRadio,
    private val offline: OfflineDownloads,
    private val seeds: RadioSeeds,
    private val feedback: Feedback,
    private val favourites: FavouriteStore,
    private val pins: PinStore,
    private val upgrades: Upgrades,
    private val db: CatalogDatabase,
    folders: PlaylistFolderStore,
) : ViewModel() {
    val favouriteAlbums: StateFlow<Set<String>> = favourites.albums

    // Whether playlist folders are offered.
    val foldersOffered: StateFlow<Boolean> = folders.offered.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), folders.offeredNow())

    // The songs Add to playlist takes: an album's songs found online too
    // while the server keeps them in playlists; otherwise what plays.
    fun addSpecOf(target: CollectionTarget): ListSpec = when (target) {
        is CollectionTarget.Album -> albumAddSpec(target.id, store.findsOpen)
        else -> specOf(target)
    }
    val favouriteArtists: StateFlow<Set<String>> = favourites.artists

    // The pins Home shows, in row order.
    val pinned: StateFlow<List<PinKey>> = pins.shown

    // Where one pin is in that row (pinSpotOf).
    fun spotOf(key: PinKey): Flow<PinSpot> = pinSpotOf(pins, key)

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

    // Whether an artist can start a radio: always, since the library alone can make one.
    val radio: StateFlow<Boolean> = MutableStateFlow(true)

    // Whether an album has a song only on a server with no download yet,
    // by a probe that stops at the first: read again on every write of a
    // download but a progress tick, and when its songs change.
    fun canDownload(albumId: String): Flow<Boolean> =
        combine(offline.version, db.tableChanges(arrayOf("track")).onStart { emit(Unit) }) { _, _ -> }.conflate().map {
            db.listsDao().count(
                rawQuery(
                    "SELECT EXISTS(SELECT 1 FROM track t WHERE t.albumId = ? AND t.onPhone = 0 " +
                        "AND NOT EXISTS (SELECT 1 FROM download d WHERE d.trackId = t.id))",
                    listOf(SqlValue.Text(albumId)),
                ),
            ) == 1
        }

    // When an album's songs change, as after a FLAC lands.
    val trackChanges: Flow<Unit> = db.tableChanges(arrayOf("track")).onStart { emit(Unit) }

    // Whether the signed-in Octo server can look for FLACs, and where.
    val canUpgrade: StateFlow<Boolean> = upgrades.canUpgrade
    val upgradeSource: StateFlow<String?> = upgrades.source

    // The album's songs a FLAC could replace, by their copies on the server:
    // how many, and the first batch.
    suspend fun upgradableInAlbum(albumId: String): AlbumUpgrade = upgrades.upgradableInAlbum(albumId)

    fun findFlac(asks: AlbumUpgrade) = upgrades.request(asks.first, beyond = asks.count - asks.first.size)

    fun album(id: String): Flow<AlbumEntity?> = catalog.album(id)
    fun artist(id: String): Flow<ArtistEntity?> = catalog.artist(id)
    fun playlist(id: String): Flow<PlaylistSummary?> = store.playlists.map { list -> list.firstOrNull { it.id == id } }

    // The songs, in the order they play, named by what they are: an
    // album's in its order, an artist's album by album, newest first, and a
    // playlist's in its order.
    fun specOf(target: CollectionTarget): ListSpec = when (target) {
        is CollectionTarget.Album -> ListSpec.Album(target.id, withFinds = false)
        is CollectionTarget.Artist -> ListSpec.Artist(target.id, ArtistOrder.Albums)
        is CollectionTarget.Playlist -> ListSpec.Playlist(target.id)
    }

    // A new playlist named "<name> (copy)" with the same songs in the same
    // order, as the desktop's Duplicate makes: the playlist as it stood when
    // the copy began, a page at a time.
    fun duplicatePlaylist(id: String) {
        viewModelScope.launch {
            val playlist = userDao.playlistRow(id) ?: return@launch
            val name = playlistCopyName(playlist.name)
            store.duplicate(id, name) ?: return@launch
            feedback.show(copiedMessage(name))
        }
    }

    fun play(target: CollectionTarget, shuffle: Boolean) = playback.playList(specOf(target), null, shuffle)

    fun playNext(target: CollectionTarget) =
        playback.addList(specOf(target), next = true, inserted = { count, undo -> feedback.undoable(queueAddWords(count, next = true), undo) })

    fun addToQueue(target: CollectionTarget) =
        playback.addList(specOf(target), next = false, inserted = { count, undo -> feedback.undoable(queueAddWords(count, next = false), undo) })

    // Downloads an album's songs only on a server, a page at a time.
    fun downloadAlbum(albumId: String) = offline.downloadAlbum(albumId)

    // A radio from an album: its most played song here as the stored play
    // counts have it, else its first, playing at once; then songs like the
    // album, leaving the rest of the album out.
    fun startAlbumRadio(albumId: String) {
        viewModelScope.launch {
            val seed = seeds.of(PlayedIn.Album(albumId))
            if (seed == null) {
                feedback.show("No songs to start a radio from")
                return@launch
            }
            val album = catalog.album(albumId).first()
            playRadio(seed, playback, RadioAround.Album(albumId), Shown(album?.title ?: seed.album, album?.artist, album?.artwork))
        }
    }

    // A radio from the artist's most played song, or their first song when
    // none has been played yet, playing at once; then songs like theirs,
    // theirs among them.
    fun startRadio(artistId: String) {
        viewModelScope.launch {
            val seed = seeds.of(PlayedIn.Artist(artistId))
            if (seed == null) {
                feedback.show("No songs to start a radio from")
                return@launch
            }
            val artist = catalog.artist(artistId).first()
            playRadio(seed, playback, RadioAround.Artist(artistId), Shown(artist?.name ?: seed.artist, null, artist?.artwork))
        }
    }
}


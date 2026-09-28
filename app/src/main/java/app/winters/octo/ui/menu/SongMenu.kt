package app.winters.octo.ui.menu

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.UserDao
import app.winters.octo.catalog.isFind
import app.winters.octo.design.PopupPages
import app.winters.octo.discovery.Discovery
import app.winters.octo.discovery.DownloadState
import app.winters.octo.discovery.Downloads
import app.winters.octo.discovery.asTrack
import app.winters.octo.offline.DownloadEntity
import app.winters.octo.offline.DownloadStatus
import app.winters.octo.offline.OfflineDownloads
import app.winters.octo.playback.LikeStore
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.PlaylistStore
import app.winters.octo.playback.RatingStore
import app.winters.octo.server.ServerControls
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.ui.common.Feedback
import app.winters.octo.ui.common.SelectionBarState
import app.winters.octo.ui.common.SongSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

// Where a song's menu was opened from, so it can offer what fits there and
// leave out what would only lead back to the same page: the album page it
// is on, the artist page, or its row on a playlist page. `selection` is the
// list's, when the list can pick songs, and `selectKey` the row's key in it.
data class SongMenuContext(
    val albumId: String? = null,
    val artistId: String? = null,
    val playlistId: String? = null,
    val playlistItemId: Long? = null,
    val selection: SongSelection? = null,
    val selectKey: String? = null,
)

// What a song row can do without opening the menu: a swipe, or a screen
// reader's actions. The menu's host fills it in, since it has the player.
// Play next from here says so, with an Undo, since nothing else shows it.
interface QuickSongActions {
    fun playNext(trackId: String)
    fun addToQueue(trackId: String)
}

// The pages of a song's menu: its actions first, and what some of them open
// in its place, each with a way back.
enum class SongPage { Actions, AddToPlaylist, AddToLastPlaylist, Rate, Share, Info }

// Which song the menu is open for, if any. Any song on any screen can
// open it: a long press on a row, or the more button in the player. It also
// carries what shares the menu's layer: the menus for albums, artists and
// playlists, and the bar for picked songs.
class SongMenuState {
    var trackId by mutableStateOf<String?>(null)
        private set

    // The last song opened, kept after closing so the menu can still show
    // it while it fades away.
    var lastTrackId by mutableStateOf<String?>(null)
        private set

    // Where the last song was opened from.
    var lastContext by mutableStateOf(SongMenuContext())
        private set

    // Which page the menu shows. Each opening starts at the actions.
    val pages = PopupPages(SongPage.Actions)

    fun open(trackId: String, context: SongMenuContext = SongMenuContext()) {
        pages.reset(SongPage.Actions)
        this.trackId = trackId
        lastTrackId = trackId
        lastContext = context
    }

    // The menus for albums, artists and playlists.
    val collections = CollectionMenuState()

    // The bar that acts on picked songs.
    val selectionBar = SelectionBarState()

    // Set by the host while it is shown.
    var quick: QuickSongActions? = null
        internal set

    fun close() {
        trackId = null
    }
}

val LocalSongMenu = staticCompositionLocalOf<SongMenuState> { error("No song menu") }

// The choices in a song's menu, in the order shown.
enum class SongAction {
    PlayNext, AddToQueue, StartRadio, Download, AddToLastPlaylist, AddToPlaylist, RemoveFromPlaylist, Select, KeepOffline, ShareFile, Share, Like,
    Rate, GoToAlbum, GoToArtist, SetAsSound, DeleteFromPhone, Info,
}

// Where the menu was opened, as the choices care about it.
data class MenuPlace(
    // On the page of the song's own album, or its own artist.
    val onAlbumPage: Boolean = false,
    val onArtistPage: Boolean = false,
    // On a row of a playlist page, which can be taken out.
    val inPlaylist: Boolean = false,
    // In a list that can pick songs, and not picking already.
    val selectable: Boolean = false,
)

// What the context means for one song: "Go to album" only leads somewhere
// from a page other than that album's.
fun menuPlace(context: SongMenuContext, albumId: String, artistId: String): MenuPlace = MenuPlace(
    onAlbumPage = context.albumId != null && context.albumId == albumId,
    onArtistPage = context.artistId != null && context.artistId == artistId,
    inPlaylist = context.playlistId != null && context.playlistItemId != null,
    selectable = context.selection != null && context.selectKey != null && !context.selection.active,
)

// What a song's menu offers. A song found online has no album or artist in
// the library and cannot be liked, rated or put in a playlist yet, so it
// offers a download instead. Radio needs a server signed in; sharing needs
// a copy of the song on a server that shares. A library song only on a
// server (`offline`) can be downloaded to the phone. One with a file on the
// phone (`phone`) can send that file, ring with it, or delete it.
// `lastPlaylist` is whether a playlist was added to lately, offered first
// among the ways to keep the song.
fun songActions(
    find: Boolean,
    radio: Boolean,
    share: Boolean = false,
    offline: Boolean = false,
    place: MenuPlace = MenuPlace(),
    phone: Boolean = false,
    lastPlaylist: Boolean = false,
): List<SongAction> = buildList {
    add(SongAction.PlayNext)
    add(SongAction.AddToQueue)
    if (radio) add(SongAction.StartRadio)
    if (find) {
        add(SongAction.Download)
        if (place.inPlaylist) add(SongAction.RemoveFromPlaylist)
        if (place.selectable) add(SongAction.Select)
    } else {
        if (lastPlaylist) add(SongAction.AddToLastPlaylist)
        add(SongAction.AddToPlaylist)
        if (place.inPlaylist) add(SongAction.RemoveFromPlaylist)
        if (place.selectable) add(SongAction.Select)
        if (offline) add(SongAction.KeepOffline)
        if (phone) add(SongAction.ShareFile)
        if (share) add(SongAction.Share)
        add(SongAction.Like)
        add(SongAction.Rate)
        if (!place.onAlbumPage) add(SongAction.GoToAlbum)
        if (!place.onArtistPage) add(SongAction.GoToArtist)
        if (phone) {
            add(SongAction.SetAsSound)
            add(SongAction.DeleteFromPhone)
        }
    }
    add(SongAction.Info)
}

// How a song's menu groups its actions, top to bottom: playing it, keeping
// it, going to its album or artist, sharing and looking into it, and last,
// what takes it away. A hairline parts the groups.
private val SongMenuOrder = listOf(
    listOf(SongAction.PlayNext, SongAction.AddToQueue, SongAction.StartRadio),
    listOf(SongAction.AddToLastPlaylist, SongAction.AddToPlaylist, SongAction.Like, SongAction.Rate, SongAction.Download, SongAction.KeepOffline),
    listOf(SongAction.GoToAlbum, SongAction.GoToArtist),
    listOf(SongAction.Share, SongAction.ShareFile, SongAction.SetAsSound, SongAction.Info, SongAction.Select),
    listOf(SongAction.RemoveFromPlaylist, SongAction.DeleteFromPhone),
)

// The actions offered, in their groups, leaving out empty groups.
fun songMenuGroups(actions: List<SongAction>): List<List<SongAction>> =
    SongMenuOrder.map { group -> group.filter { it in actions } }.filter { it.isNotEmpty() }

// The add row's words for a song not in the library, and how its adding
// is going. "Download" is kept for saving a library song to the phone.
fun downloadLabel(state: DownloadState): String = when (state) {
    DownloadState.None -> "Add to your library"
    DownloadState.Requested -> "Adding to your library"
    DownloadState.Done -> "In your library"
}

// The row's words for a library song downloaded to the phone, or not yet.
// `byHand` is a download asked for from a menu, which can be removed here;
// one a rule keeps (Liked songs, a playlist) goes with its rule.
fun keepOfflineLabel(state: DownloadStatus?, byHand: Boolean): String = when (state) {
    null -> "Download"
    DownloadStatus.Queued -> "Waiting to download"
    DownloadStatus.Downloading -> "Downloading"
    DownloadStatus.Done -> if (byHand) "Remove download" else "Kept downloaded"
    DownloadStatus.Failed -> "Download failed, try again"
}

// Whether the row can be tapped.
fun keepOfflineEnabled(state: DownloadStatus?, byHand: Boolean): Boolean =
    state == null || state == DownloadStatus.Failed || (state == DownloadStatus.Done && byHand)

@HiltViewModel
class SongMenuViewModel @Inject constructor(
    private val catalog: CatalogDao,
    private val online: OnlineDao,
    private val likes: LikeStore,
    private val playback: PlaybackConnection,
    private val discovery: Discovery,
    private val downloads: Downloads,
    private val ratings: RatingStore,
    private val controls: ServerControls,
    private val offline: OfflineDownloads,
    private val playlists: PlaylistStore,
    private val userDao: UserDao,
    private val feedback: Feedback,
) : ViewModel() {
    val liked: StateFlow<Set<String>> = likes.liked
    val downloadStates: StateFlow<Map<String, DownloadState>> = downloads.states

    // Library songs downloaded to the phone, or on their way.
    val kept: StateFlow<Map<String, DownloadEntity>> = offline.byTrack

    fun keepOffline(id: String) = offline.download(listOf(id))
    fun removeOffline(id: String) = offline.remove(id) { restore -> feedback.undoable("Download removed", restore) }
    fun retryOffline(id: String) = offline.retry(id)

    // Whether songs can start a radio: only with a server signed in.
    val radio: StateFlow<Boolean> = discovery.available
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Whether the signed-in server can make shared links.
    val sharing: StateFlow<Boolean> = controls.sharing

    // The server's id for a song, when it has a copy there to share.
    suspend fun shareId(trackId: String): String? = if (isFind(trackId)) null else controls.serverSongId(trackId)

    // A library song, or a song found online shaped like one.
    fun track(id: String): Flow<TrackEntity?> =
        if (isFind(id)) online.songFlow(id).map { it?.asTrack() } else catalog.trackFlow(id)

    fun playNext(id: String) = playback.playNext(listOf(id))
    fun playLast(id: String) = playback.playLast(listOf(id))
    fun toggleLike(id: String) = likes.toggle(id)
    fun rate(id: String, rating: Int) = ratings.rate(id, rating)

    // Play next from a swipe, which nothing else on screen confirms, so it
    // says so and offers to take that one song back out of the queue.
    fun playNextUndoable(id: String) = playback.playNextUndoable(listOf(id)) { undo -> feedback.undoable("Playing next", undo) }

    // Takes the song's row out of a playlist, with an Undo that puts it back.
    fun removeFromPlaylist(playlistId: String, itemId: Long) {
        viewModelScope.launch {
            val name = userDao.playlistRow(playlistId)?.name ?: "the playlist"
            playlists.remove(playlistId, itemId) { row ->
                feedback.undoable("Removed from $name") { playlists.restore(playlistId, row) }
            }
        }
    }

    fun download(track: TrackEntity) {
        viewModelScope.launch { downloads.request(track) }
    }

    // Plays the song and then songs like it, in place of the queue. When the
    // server cannot answer, nothing changes.
    fun startRadio(track: TrackEntity) {
        viewModelScope.launch { playRadio(track, discovery, playback, feedback, "this song") }
    }
}

// Plays a song and then songs like it, in place of the queue. When the
// server cannot answer, nothing changes. `what` names what the radio was
// asked for, for the message when it cannot start.
internal suspend fun playRadio(seed: TrackEntity, discovery: Discovery, playback: PlaybackConnection, feedback: Feedback, what: String) {
    val songs = try {
        withContext(Dispatchers.IO) { discovery.radio(seed) }
    } catch (e: SubsonicException) {
        Log.w("Octo", "radio failed: ${e.javaClass.simpleName}")
        feedback.show("Could not start a radio for $what")
        return
    }
    // Only the song itself back means the server found nothing like it.
    if (songs.size > 1) {
        playback.playTracks(songs.map { it.id }, 0)
    } else {
        feedback.show("No similar songs found")
    }
}


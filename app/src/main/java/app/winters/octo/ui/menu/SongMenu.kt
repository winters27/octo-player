package app.winters.octo.ui.menu

import android.util.Log
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.UserDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.isFind
import app.winters.octo.design.GlassSheet
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.discovery.Discovery
import app.winters.octo.discovery.DownloadState
import app.winters.octo.discovery.Downloads
import app.winters.octo.discovery.asTrack
import app.winters.octo.offline.DownloadEntity
import app.winters.octo.offline.DownloadStatus
import app.winters.octo.offline.OfflineDownloads
import app.winters.octo.offline.Reasons
import app.winters.octo.offline.reasons
import app.winters.octo.playback.LikeStore
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.PlaylistStore
import app.winters.octo.playback.RatingStore
import app.winters.octo.ui.common.RatingStars
import app.winters.octo.ui.common.Artwork
import app.winters.octo.ui.common.Feedback
import app.winters.octo.ui.common.SelectionBarHost
import app.winters.octo.ui.common.SelectionBarState
import app.winters.octo.ui.common.SongSelection
import app.winters.octo.ui.nav.AlbumRoute
import app.winters.octo.ui.nav.ArtistRoute
import app.winters.octo.ui.playlist.LocalPlaylistSheets
import app.winters.octo.ui.playlist.PlaylistSheet
import app.winters.octo.server.ServerControls
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.ui.server.LocalShareSheet
import app.winters.octo.ui.server.ShareRequest
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

// Which song the menu is open for, if any. Any song on any screen can
// open it: a long press on a row, or the more button in the player. It also
// carries what shares the menu's layer: the menus for albums, artists and
// playlists, the song info sheet, and the bar for picked songs.
class SongMenuState {
    var trackId by mutableStateOf<String?>(null)
        private set

    // The last song opened, kept after closing so the sheet can still show
    // it while it slides away.
    var lastTrackId by mutableStateOf<String?>(null)
        private set

    // Where the last song was opened from.
    var lastContext by mutableStateOf(SongMenuContext())
        private set

    fun open(trackId: String, context: SongMenuContext = SongMenuContext()) {
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

    // The song the info sheet is open for, if any, and the last one, kept
    // while the sheet slides away.
    var infoTrackId by mutableStateOf<String?>(null)
        private set
    var lastInfoTrackId by mutableStateOf<String?>(null)
        private set

    // Swaps the menu for the info sheet.
    fun openInfo(trackId: String) {
        this.trackId = null
        infoTrackId = trackId
        lastInfoTrackId = trackId
    }

    fun closeInfo() {
        infoTrackId = null
    }

    fun close() {
        trackId = null
    }

    // The song the rating sheet is open for, if any, and the last one, kept
    // while the sheet slides away.
    var ratingTrackId by mutableStateOf<String?>(null)
        private set
    var lastRatingTrackId by mutableStateOf<String?>(null)
        private set

    // Swaps the menu for the rating sheet.
    fun openRating(trackId: String) {
        this.trackId = null
        ratingTrackId = trackId
        lastRatingTrackId = trackId
    }

    fun closeRating() {
        ratingTrackId = null
    }
}

val LocalSongMenu = staticCompositionLocalOf<SongMenuState> { error("No song menu") }

// The choices in a song's menu, in the order shown.
enum class SongAction {
    PlayNext, AddToQueue, StartRadio, Download, AddToPlaylist, RemoveFromPlaylist, Select, KeepOffline, ShareFile, Share, Like,
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
fun songActions(
    find: Boolean,
    radio: Boolean,
    share: Boolean = false,
    offline: Boolean = false,
    place: MenuPlace = MenuPlace(),
    phone: Boolean = false,
): List<SongAction> = buildList {
    add(SongAction.PlayNext)
    add(SongAction.AddToQueue)
    if (radio) add(SongAction.StartRadio)
    if (find) {
        add(SongAction.Download)
        if (place.inPlaylist) add(SongAction.RemoveFromPlaylist)
        if (place.selectable) add(SongAction.Select)
    } else {
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

// The download row's words for where a find's download is.
fun downloadLabel(state: DownloadState): String = when (state) {
    DownloadState.None -> "Download"
    DownloadState.Requested -> "Downloading"
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

// The menu itself, drawn over everything, the player included. `onOpen`
// goes to a page, and is expected to close the player if it is open.
@Composable
fun SongMenuHost(state: SongMenuState, onOpen: (NavKey) -> Unit, vm: SongMenuViewModel = hiltViewModel()) {
    // Rows swipe and speak through these while the host is shown.
    DisposableEffect(state, vm) {
        state.quick = object : QuickSongActions {
            override fun playNext(trackId: String) = vm.playNextUndoable(trackId)
            override fun addToQueue(trackId: String) = vm.playLast(trackId)
        }
        onDispose { state.quick = null }
    }
    val phoneFiles = rememberPhoneFiles()
    // Over the bottom bar and under every sheet, as the bar it stands in for.
    SelectionBarHost(state.selectionBar, phoneFiles)
    GlassSheet(visible = state.trackId != null, onDismiss = state::close) {
        val trackId = state.lastTrackId ?: return@GlassSheet
        val context = state.lastContext
        val track by remember(trackId) { vm.track(trackId) }.collectAsStateWithLifecycle(null)
        val liked by vm.liked.collectAsStateWithLifecycle()
        val downloads by vm.downloadStates.collectAsStateWithLifecycle()
        val kept by vm.kept.collectAsStateWithLifecycle()
        val radio by vm.radio.collectAsStateWithLifecycle()
        val sharing by vm.sharing.collectAsStateWithLifecycle()
        val shareId by produceState<String?>(null, trackId, sharing) { value = if (sharing) vm.shareId(trackId) else null }
        val shareSheet = LocalShareSheet.current
        val song = track ?: return@GlassSheet
        val isLiked = trackId in liked
        val playlistSheets = LocalPlaylistSheets.current

        val keptRow = kept[trackId]
        val byHand = keptRow?.reasons?.contains(Reasons.MANUAL) == true

        SongHeader(song)
        Spacer(Modifier.height(8.dp))
        val place = menuPlace(context, song.albumId, song.artistId)
        val phone = song.onPhone && !isFind(trackId)
        val actions = songActions(isFind(trackId), radio, share = shareId != null, offline = !song.onPhone || keptRow != null, place = place, phone = phone)
        for (action in actions) {
            when (action) {
                SongAction.PlayNext -> MenuRow(OctoIcons.PlayNext, "Play next") {
                    vm.playNext(trackId)
                    state.close()
                }
                SongAction.AddToQueue -> MenuRow(OctoIcons.AddToQueue, "Add to queue") {
                    vm.playLast(trackId)
                    state.close()
                }
                SongAction.StartRadio -> MenuRow(OctoIcons.Radio, "Start radio") {
                    state.close()
                    vm.startRadio(song)
                }
                SongAction.Download -> {
                    val download = downloads[trackId] ?: DownloadState.None
                    val icon = when (download) {
                        DownloadState.None -> OctoIcons.Download
                        DownloadState.Requested -> OctoIcons.Downloading
                        DownloadState.Done -> OctoIcons.Downloaded
                    }
                    MenuRow(icon, downloadLabel(download), enabled = download == DownloadState.None) {
                        vm.download(song)
                    }
                }
                SongAction.AddToPlaylist -> MenuRow(OctoIcons.AddToPlaylist, "Add to playlist") {
                    state.close()
                    playlistSheets.show(PlaylistSheet.Pick(trackId))
                }
                SongAction.RemoveFromPlaylist -> MenuRow(OctoIcons.RemoveFromPlaylist, "Remove from this playlist") {
                    state.close()
                    val playlistId = context.playlistId
                    val itemId = context.playlistItemId
                    if (playlistId != null && itemId != null) vm.removeFromPlaylist(playlistId, itemId)
                }
                SongAction.Select -> MenuRow(OctoIcons.Select, "Select") {
                    state.close()
                    context.selectKey?.let { key -> context.selection?.start(key) }
                }
                SongAction.KeepOffline -> {
                    val state = keptRow?.state
                    val icon = when (state) {
                        DownloadStatus.Queued, DownloadStatus.Downloading -> OctoIcons.Downloading
                        DownloadStatus.Done -> OctoIcons.Downloaded
                        null, DownloadStatus.Failed -> OctoIcons.Download
                    }
                    MenuRow(icon, keepOfflineLabel(state, byHand), enabled = keepOfflineEnabled(state, byHand)) {
                        when (state) {
                            null -> vm.keepOffline(trackId)
                            DownloadStatus.Failed -> vm.retryOffline(trackId)
                            else -> vm.removeOffline(trackId)
                        }
                    }
                }
                SongAction.ShareFile -> MenuRow(OctoIcons.ShareFile, "Share file") {
                    state.close()
                    phoneFiles.share(listOf(trackId))
                }
                SongAction.Share -> MenuRow(OctoIcons.Share, "Share link") {
                    state.close()
                    shareId?.let { shareSheet.show(ShareRequest(listOf(it), song.title)) }
                }
                SongAction.Like -> MenuRow(
                    if (isLiked) OctoIcons.Liked else OctoIcons.Like,
                    if (isLiked) "Remove from Liked songs" else "Add to Liked songs",
                ) {
                    vm.toggleLike(trackId)
                }
                SongAction.Rate -> MenuRow(if (song.rating > 0) OctoIcons.StarFilled else OctoIcons.Star, "Rate") {
                    state.openRating(trackId)
                }
                SongAction.GoToAlbum -> MenuRow(OctoIcons.Album, "Go to album") {
                    state.close()
                    onOpen(AlbumRoute(song.albumId))
                }
                SongAction.GoToArtist -> MenuRow(OctoIcons.Artist, "Go to artist") {
                    state.close()
                    onOpen(ArtistRoute(song.artistId))
                }
                SongAction.SetAsSound -> MenuRow(OctoIcons.Ringtone, "Set as ringtone") {
                    state.close()
                    phoneFiles.setSound(trackId)
                }
                SongAction.DeleteFromPhone -> MenuRow(OctoIcons.Delete, "Delete from phone") {
                    state.close()
                    phoneFiles.delete(listOf(trackId))
                }
                SongAction.Info -> MenuRow(OctoIcons.Info, "Song info") {
                    state.openInfo(trackId)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    RatingSheet(state, vm)
    SongInfoSheet(state)
    CollectionMenuHost(state.collections, onOpen)
}

@Composable
internal fun SongHeader(song: TrackEntity) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(song.artwork, 48.dp, shape = RoundedCornerShape(6.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = OctoType.body, color = OctoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (song.rating > 0) RatingStars(song.rating, Modifier.padding(top = 4.dp))
        }
    }
}

// One choice in the menu: an icon and what it does. One that cannot be
// chosen right now is dimmed.
@Composable
fun MenuRow(@DrawableRes icon: Int, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            // Grows with the text at large font sizes.
            .heightIn(min = 52.dp)
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = if (enabled) OctoColors.TextSecondary else OctoColors.TextMuted,
            modifier = Modifier.size(22.dp),
        )
        Text(
            label,
            style = OctoType.bodySmall,
            color = if (enabled) OctoColors.TextPrimary else OctoColors.TextMuted,
            modifier = Modifier.weight(1f),
        )
    }
}

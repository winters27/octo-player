package app.winters.octo.ui.menu

import app.winters.octo.family.FamilyHub
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
import app.winters.octo.data.LibraryFiles
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.data.Upgrades
import app.winters.octo.design.PopupPages
import app.winters.octo.discovery.DownloadState
import app.winters.octo.discovery.Downloads
import app.winters.octo.discovery.asTrack
import app.winters.octo.listening.TotalsFailed
import app.winters.octo.offline.DownloadEntity
import app.winters.octo.offline.DownloadStatus
import app.winters.octo.offline.OfflineDownloads
import app.winters.octo.output.OutputChoice
import app.winters.octo.playback.FindMap
import app.winters.octo.playback.LibraryRadio
import app.winters.octo.playback.LikeStore
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.PlaylistStore
import app.winters.octo.playback.RadioAround
import app.winters.octo.playback.Shown
import app.winters.octo.playback.RatingStore
import app.winters.octo.playback.queueAddWords
import app.winters.octo.player.PlayerSettings
import app.winters.octo.player.SwipeAction
import app.winters.octo.server.ServerControls
import app.winters.octo.sound.SoundEngine
import app.winters.octo.ui.common.Feedback
import app.winters.octo.ui.common.SelectionBarState
import app.winters.octo.ui.common.SongSelection
import app.winters.octo.subsonic.STREAM_VERSIONS_EXTENSION
import app.winters.octo.ui.upgrade.UpgradeAsk
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// Where a song's menu was opened from, so it can offer what fits there and
// leave out what would only lead back to the same page: the album page it
// is on, the artist page, or its row on a playlist page. `selection` is the
// list's, when the list can pick songs, and `selectKey` the row's key in it.
// `fromPlayer` is the full player's More button, for the song playing.
data class SongMenuContext(
    val albumId: String? = null,
    val artistId: String? = null,
    val playlistId: String? = null,
    val playlistItemId: Long? = null,
    val selection: SongSelection? = null,
    val selectKey: String? = null,
    val fromPlayer: Boolean = false,
)

// What a song row can do without opening the menu: a swipe, or a screen
// reader's actions. The menu's host fills it in, since it has the player.
// Both say so, with an Undo, since nothing else on screen shows it.
interface QuickSongActions {
    fun playNext(trackId: String)
    fun addToQueue(trackId: String)
}

// The pages of a song's menu: its actions first, and what some of them open
// in its place, each with a way back. `Cast` is the list of places to play.
enum class SongPage { Actions, AddToPlaylist, AddToLastPlaylist, Rate, Share, Info, DeleteFromDisk, Cast }

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

    // The song Try another version was tapped for, until the versions sheet
    // takes it (see StreamVersionsHost).
    var versionsFor by mutableStateOf<app.winters.octo.versions.VersionsSong?>(null)
        private set

    fun openVersions(song: app.winters.octo.versions.VersionsSong) {
        versionsFor = song
    }

    fun versionsTaken() {
        versionsFor = null
    }

    // Set by the host while it is shown.
    var quick: QuickSongActions? = null
        internal set

    // What a swipe right on a song row does, set by the host while it is shown.
    var swipeAction by mutableStateOf(SwipeAction.PlayNext)
        internal set

    fun close() {
        trackId = null
    }
}

val LocalSongMenu = staticCompositionLocalOf<SongMenuState> { error("No song menu") }

// The choices in a song's menu, in the order shown.
enum class SongAction {
    PlayNext, AddToQueue, StartRadio, Download, RequestCopy, AddToLastPlaylist, AddToPlaylist, RemoveFromPlaylist, Select, KeepOffline, FindFlac, ShareFile, Share, Like,
    Rate, GoToAlbum, GoToArtist, SetAsSound, DeleteFromPhone, RemoveFromMyLibrary, DeleteFromDisk, Info, FindSongs, TryAnotherVersion,
    Cast, Sound,
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
    // In the full player, for the song playing.
    val inPlayer: Boolean = false,
)

// What the context means for one song: "Go to album" only leads somewhere
// from a page other than that album's.
fun menuPlace(context: SongMenuContext, albumId: String, artistId: String): MenuPlace = MenuPlace(
    onAlbumPage = context.albumId != null && context.albumId == albumId,
    onArtistPage = context.artistId != null && context.artistId == artistId,
    inPlaylist = context.playlistId != null && context.playlistItemId != null,
    selectable = context.selection != null && context.selectKey != null && !context.selection.active,
    inPlayer = context.fromPlayer,
)

// What a song's menu offers. A song found online has no album or artist in
// the library and cannot be liked, rated or put in a playlist yet, so it
// offers a download instead. Radio needs a server signed in; sharing needs
// a copy of the song on a server that shares. A library song only on a
// server (`offline`) can be downloaded to the phone. One with a file on the
// phone (`phone`) can send that file, ring with it, or delete it.
// `lastPlaylist` is whether a playlist was added to lately, offered first
// among the ways to keep the song. `upgrade` is whether an Octo server can
// look for a FLAC to take the place of its copy there, one that loses detail.
// `findSongs` is whether the server can run the song's search again so a
// copy can be picked (Find songs), for a found song or one it has a copy of.
// `disk` is whether that server lets this user delete its copy from disk.
// In a family, a member who asks for copies gets Request a copy beside the
// add for a song found online (`requestCopy`), and a managed member can take
// a library song out of their own library (`removeFromMine`). In the full
// player the menu ends with where the music plays (Cast) and the Sound page,
// for a found song as much as a library one.
fun songActions(
    find: Boolean,
    radio: Boolean,
    share: Boolean = false,
    offline: Boolean = false,
    place: MenuPlace = MenuPlace(),
    phone: Boolean = false,
    lastPlaylist: Boolean = false,
    upgrade: Boolean = false,
    findSongs: Boolean = false,
    disk: Boolean = false,
    requestCopy: Boolean = false,
    removeFromMine: Boolean = false,
    // A find that is the server's own song, which the phone's copy has not
    // brought in yet (OwnedFinds): nothing to download or ask for, and its
    // heart, as any library song has.
    owned: Boolean = false,
    // The server lets this listener choose the video a song found online
    // streams from (octoStreamVersions), and this find is the song playing,
    // streaming from that server: Try another version, in the player.
    streamVersions: Boolean = false,
    // The server keeps songs found online in playlists (octoPlaylistSongs).
    playlistAdds: Boolean = false,
): List<SongAction> = buildList {
    add(SongAction.PlayNext)
    add(SongAction.AddToQueue)
    if (radio) add(SongAction.StartRadio)
    if (find) {
        if (!owned) {
            add(SongAction.Download)
            if (requestCopy) add(SongAction.RequestCopy)
        }
        if (playlistAdds) {
            if (lastPlaylist) add(SongAction.AddToLastPlaylist)
            add(SongAction.AddToPlaylist)
        }
        if (place.inPlaylist) add(SongAction.RemoveFromPlaylist)
        if (place.selectable) add(SongAction.Select)
        if (owned) add(SongAction.Like)
    } else {
        if (lastPlaylist) add(SongAction.AddToLastPlaylist)
        add(SongAction.AddToPlaylist)
        if (place.inPlaylist) add(SongAction.RemoveFromPlaylist)
        if (place.selectable) add(SongAction.Select)
        if (offline) add(SongAction.KeepOffline)
        if (upgrade) add(SongAction.FindFlac)
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
        if (removeFromMine) add(SongAction.RemoveFromMyLibrary)
        if (disk) add(SongAction.DeleteFromDisk)
    }
    add(SongAction.Info)
    if (findSongs) add(SongAction.FindSongs)
    if (find && !owned && place.inPlayer && streamVersions) add(SongAction.TryAnotherVersion)
    if (place.inPlayer) {
        add(SongAction.Cast)
        add(SongAction.Sound)
    }
}

// How a song's menu groups its actions, top to bottom: playing it, keeping
// it, going to its album or artist, sharing and looking into it, what takes
// it away, and last, in the player, where the music plays and how it
// sounds. A hairline parts the groups.
private val SongMenuOrder = listOf(
    listOf(SongAction.PlayNext, SongAction.AddToQueue, SongAction.StartRadio),
    listOf(SongAction.AddToLastPlaylist, SongAction.AddToPlaylist, SongAction.Like, SongAction.Rate, SongAction.Download, SongAction.RequestCopy, SongAction.KeepOffline, SongAction.FindFlac),
    listOf(SongAction.GoToAlbum, SongAction.GoToArtist),
    listOf(SongAction.Share, SongAction.ShareFile, SongAction.SetAsSound, SongAction.Info, SongAction.FindSongs, SongAction.TryAnotherVersion, SongAction.Select),
    listOf(SongAction.RemoveFromPlaylist, SongAction.DeleteFromPhone, SongAction.RemoveFromMyLibrary, SongAction.DeleteFromDisk),
    listOf(SongAction.Cast, SongAction.Sound),
)

// The actions offered, in their groups, leaving out empty groups.
fun songMenuGroups(actions: List<SongAction>): List<List<SongAction>> =
    SongMenuOrder.map { group -> group.filter { it in actions } }.filter { it.isNotEmpty() }

// Whether a signed-in server lets this listener choose the video a song
// found online streams from: an Octo server listing octoStreamVersions (it
// lists it for everyone but a family's Listener and Kid accounts).
fun offersStreamVersions(isOcto: Boolean, extensions: Set<String>): Boolean =
    isOcto && "$STREAM_VERSIONS_EXTENSION:1" in extensions

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

// The line under the Cast row: the device the music plays on, or is moving
// to. Nothing while it plays on this phone.
fun castDetail(choice: OutputChoice): String? = when (choice) {
    is OutputChoice.Casting -> choice.device.name
    is OutputChoice.Connecting -> "Connecting to ${choice.device.name}"
    OutputChoice.Phone -> null
}

// The line under the Sound row.
fun soundDetail(equalizerOn: Boolean): String = if (equalizerOn) "Equalizer on" else "Equalizer off"

// Whether the row can be tapped.
fun keepOfflineEnabled(state: DownloadStatus?, byHand: Boolean): Boolean =
    state == null || state == DownloadStatus.Failed || (state == DownloadStatus.Done && byHand)

@HiltViewModel
class SongMenuViewModel @Inject constructor(
    private val catalog: CatalogDao,
    private val online: OnlineDao,
    private val likes: LikeStore,
    private val playback: PlaybackConnection,
    private val libraryRadio: LibraryRadio,
    private val downloads: Downloads,
    private val ratings: RatingStore,
    private val controls: ServerControls,
    private val offline: OfflineDownloads,
    private val playlists: PlaylistStore,
    private val userDao: UserDao,
    private val feedback: Feedback,
    private val upgrades: Upgrades,
    private val serverDownloads: app.winters.octo.data.ServerDownloads,
    private val files: LibraryFiles,
    // What adding a song found online does in a family, and its requests.
    val family: FamilyHub,
    sound: SoundEngine,
    playerSettings: PlayerSettings,
    // Finds a start plays before their rows are written.
    private val finds: FindMap,
    sessions: SessionRepository,
) : ViewModel() {
    // Whether the signed-in server lets this listener choose an outside
    // song's version.
    val streamVersions: StateFlow<Boolean> = sessions.state
        .map { state -> (state as? SessionState.SignedIn)?.session?.let { offersStreamVersions(it.isOcto, it.extensions) } == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // The queue entry playing `trackId` now, or null when another song is on.
    fun playingEntry(trackId: String): String? = playback.now.value.takeIf { it.trackId == trackId }?.entryId

    // Whether this one song is liked, as that changes.
    fun liked(trackId: String): Flow<Boolean> = likes.likedFlow(trackId)

    // Whether a song found online can go in a playlist on this server.
    val playlistAdds: Boolean get() = playlists.findsOpen
    val downloadStates: StateFlow<Map<String, DownloadState>> = downloads.states

    // What a swipe right on a song row does, as Settings say.
    val swipeAction: StateFlow<SwipeAction> = playerSettings.prefs.map { it.swipeAction }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SwipeAction.PlayNext)

    // This song's download to the phone, done or on its way, as it changes.
    fun kept(trackId: String): Flow<DownloadEntity?> = offline.keptRow(trackId)

    fun keepOffline(id: String) = offline.download(listOf(id))
    fun removeOffline(id: String) = offline.remove(id) { restore -> feedback.undoable("Download removed", restore) }
    fun retryOffline(id: String) = offline.retry(id)

    // Whether the equalizer is on, for the Sound row's line.
    val equalizerOn: StateFlow<Boolean> = sound.current.map { it.eqEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), sound.current.value.eqEnabled)

    // Whether songs can start a radio: always, since the library alone can make one.
    val radio: StateFlow<Boolean> = MutableStateFlow(true)

    // Whether the signed-in server can make shared links.
    val sharing: StateFlow<Boolean> = controls.sharing

    // Whether the signed-in Octo server can look for FLACs.
    val canUpgrade: StateFlow<Boolean> = upgrades.canUpgrade

    // The song's copy on the server, when a FLAC could replace it; empty
    // for a find, which has no copy of its own yet.
    suspend fun upgradable(trackId: String): List<UpgradeAsk> = if (isFind(trackId)) emptyList() else upgrades.upgradable(listOf(trackId))

    fun findFlac(asks: List<UpgradeAsk>) = upgrades.request(asks)

    // Whether the signed-in server can run a song's search again (Find songs).
    val canFindSongs: StateFlow<Boolean> = serverDownloads.supported

    // Opens Find songs for a song, by its copy on the server or its found id.
    fun findSongs(trackId: String, title: String) {
        viewModelScope.launch {
            if (!serverDownloads.findTrack(trackId, title)) feedback.show("This server has no copy of the song to look for")
        }
    }

    // Whether the signed-in Octo server lets this user delete its files.
    val canRemove: StateFlow<Boolean> = files.actions.map { it?.canRemove == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // How many days the server keeps a deleted file in its trash.
    val keepDays: StateFlow<Int> = files.actions.map { it?.keepDays ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // Whether the song has a copy on that server to delete; never a find.
    suspend fun deletable(trackId: String): Boolean = !isFind(trackId) && files.deletable(listOf(trackId)).isNotEmpty()

    fun deleteFromDisk(track: TrackEntity) = files.deleteFromDisk(listOf(track.id), track.title)

    // Whether the song can be taken out of this family member's own library.
    suspend fun removableFromMine(trackId: String): Boolean =
        family.removesFromMine && !isFind(trackId) && controls.removableId(trackId) != null

    // Takes a library song out of this family member's own library, by its
    // copy on the server.
    fun removeFromMyLibrary(track: TrackEntity) {
        viewModelScope.launch {
            val id = controls.removableId(track.id)
            if (id == null) feedback.show("This song is not in your own library") else family.removeFromMyLibrary(id, track.title)
        }
    }

    // The server's id for a song, when it has a copy there to share.
    suspend fun shareId(trackId: String): String? = if (isFind(trackId)) null else controls.serverSongId(trackId)

    // A library song, or a song found online shaped like one.
    fun track(id: String): Flow<TrackEntity?> =
        if (isFind(id)) online.songFlow(id).map { (it ?: finds[id])?.asTrack() } else catalog.trackFlow(id)

    fun toggleLike(id: String) = likes.toggle(id)
    fun rate(id: String, rating: Int) = ratings.rate(id, rating)

    // Play next, which says so and offers to take that one song back out of the queue.
    fun playNextUndoable(id: String) =
        playback.addList(ListSpec.Ids(listOf(id)), next = true, inserted = { count, undo -> feedback.undoable(queueAddWords(count, next = true), undo) })

    // Add to queue, which says so and offers to take that one song back out of the queue.
    fun playLastUndoable(id: String) =
        playback.addList(ListSpec.Ids(listOf(id)), next = false, inserted = { count, undo -> feedback.undoable(queueAddWords(count, next = false), undo) })

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

    // Plays the song at once and then songs like it, in place of the queue.
    fun startRadio(track: TrackEntity) = playRadio(track, playback)
}

// Plays `seed` at once in place of the queue, then songs like it once they
// are found: from the library and, with a server signed in, its
// suggestions, songs it found online among them. Around an album or an
// artist, their first songs shape the likeness too, and an album's own
// songs are left out. With none found, or while the play counts cannot be
// brought up to date, the seed plays and the words say so.
internal fun playRadio(seed: TrackEntity, playback: PlaybackConnection, around: RadioAround? = null, shown: Shown? = null) =
    playback.playList(
        ListSpec.Radio(seed.id, "${seed.title} radio", around),
        source = "${seed.title} radio",
        shown = shown ?: Shown(seed.title, seed.artist, seed.artwork),
    )


package app.winters.octo.desktop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import app.winters.octo.desktop.audio.SoundTarget
import app.winters.octo.desktop.home.HomeStore
import app.winters.octo.desktop.library.LibraryStore
import app.winters.octo.desktop.listening.PlayReporter
import app.winters.octo.desktop.listening.listeningFolder
import app.winters.octo.desktop.lyrics.LyricsModel
import app.winters.octo.desktop.lyrics.LyricsSources
import app.winters.octo.desktop.sound.SoundController
import app.winters.octo.desktop.nav.Navigator
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.SEEK_STEP_MS
import app.winters.octo.desktop.nav.Shortcut
import app.winters.octo.desktop.nav.VOLUME_STEP
import app.winters.octo.desktop.player.DEFAULT_OUTPUT
import app.winters.octo.desktop.player.DesktopPlayer
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.player.SleepTimer
import app.winters.octo.desktop.player.wash.WashCovers
import app.winters.octo.desktop.queue.QueueKeeper
import app.winters.octo.desktop.queue.ServerQueueSync
import app.winters.octo.desktop.search.Fetches
import app.winters.octo.desktop.search.SearchModel
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.Connection
import app.winters.octo.desktop.pages.SignInForm
import app.winters.octo.desktop.server.userMessage
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.FramePrefs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.design.PopupHost
import app.winters.octo.lyrics.OnlineLyrics
import app.winters.octo.playback.skippedLine
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.subsonic.FORM_POST_EXTENSION
import app.winters.octo.subsonic.Playlist
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

// The panel that can open on the right of the main area.
enum class SidePanel(val key: String) {
    Queue("queue"),
    Lyrics("lyrics"),
    Info("info"),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

// Everything the window shares: the settings, the server, the player,
// where the listener is, and the actions every page offers on songs.
@Stable
class AppState(
    val settings: SettingsStore,
    val accounts: Accounts,
    val http: OkHttpClient,
    val scope: CoroutineScope,
    val os: DesktopOs,
    val player: DesktopPlayer = SilentPlayer(scope = scope, volume = settings.current.playback.volume),
    // The online lyrics library; tests give one at a pretend address.
    lyricsLibrary: OnlineLyrics = OnlineLyrics(http),
    // The saved server, read from the password store before the window
    // opens, since the store can keep the caller waiting.
    restored: Connection? = accounts.restore(),
    // Where each account's plays and queue are kept, or null to keep none
    // (the screenshot tests).
    private val listeningRoot: File? = null,
) {
    // The Sound page's settings, kept on the engine; none for the silent player.
    val sound: SoundController? = (player as? SoundTarget)?.let { SoundController(it, settings, scope) }

    // The sleep timer: a countdown with a fade, or stop after some songs.
    val sleep = SleepTimer(player, scope)

    // The lyrics of the song playing, for the side panel and the full player.
    val lyrics = LyricsModel(player, LyricsSources({ connection }, http, lyricsLibrary, settings), settings, scope)

    val navigator = Navigator()
    val popups = PopupHost()
    val searchFocus = FocusRequester()

    // Bumped by the search shortcut, so a Search page already open brings
    // its field back into view.
    var searchAsks by mutableStateOf(0)
        private set

    var connection by mutableStateOf<Connection?>(null)
        private set

    // The sign-in page's fields, started from the server signed in to last.
    var signInForm by mutableStateOf(SignInForm(accounts.last))
        private set
    var library by mutableStateOf<LibraryStore?>(null)
        private set
    var fetches by mutableStateOf<Fetches?>(null)
        private set
    var search by mutableStateOf<SearchModel?>(null)
        private set
    var home by mutableStateOf<HomeStore?>(null)
        private set

    // The user's playlists, for the sidebar and "Add to playlist".
    var playlists by mutableStateOf<List<Playlist>>(emptyList())
        private set

    var sidePanel by mutableStateOf(SidePanel.of(settings.current.sidePanel))
        private set

    var fullPlayer by mutableStateOf(false)

    // What the full player shows beside the cover: the lyrics, the queue,
    // or nothing.
    var playerPanel by mutableStateOf<SidePanel?>(SidePanel.Lyrics)
        private set

    fun togglePlayerPanel(panel: SidePanel) {
        playerPanel = if (playerPanel == panel) null else panel
    }

    // Covers made ready for the full player's background.
    val washCovers = WashCovers(http)

    // One quiet line at the top of the main area, for something the
    // listener should know once (never a stack of toasts).
    var notice by mutableStateOf<String?>(null)

    // Hearts set or cleared here, shown before the server's lists catch up.
    private val starOverrides = mutableStateMapOf<String, Boolean>()

    // Opens or closes the mini player; the system side sets it.
    var toggleMiniPlayer: (() -> Unit)? = null

    // Songs that could not play this run, by id, with why, for their rows.
    val failedSongs = mutableStateMapOf<String, String>()

    // The engine's own words for the notice's last failure, shown on asking.
    var noticeDetail by mutableStateOf<String?>(null)

    // Plays reach the server and this computer's play log; the queue is
    // kept on disk and on the server.
    val plays = PlayReporter(player, settings, { connection }, listeningRoot, scope)
    val queueKeeper = QueueKeeper(player, scope)
    val queueSync = ServerQueueSync(player, settings, { connection }, scope)

    val mac: Boolean get() = os == DesktopOs.Mac

    init {
        if (listeningRoot != null) {
            plays.start()
            queueKeeper.start()
        }
        queueSync.start()
        watchProblems()
        restored?.let(::signedIn)
    }

    // A song that could not play says so in the one notice line, and its
    // rows are marked, rather than it being skipped in silence.
    private fun watchProblems() {
        scope.launch {
            player.state.map { it.problem }.distinctUntilChanged().collect { problem ->
                if (problem == null) return@collect
                val song = problem.song
                if (song != null) failedSongs[song.id] = problem.words
                notice = if (song != null) skippedLine(song.title, problem.words) else problem.words
                noticeDetail = problem.detail
            }
        }
    }

    // Puts plays and the queue away before Octo quits.
    fun beforeQuit() {
        plays.flush()
        if (listeningRoot != null) queueKeeper.saveNow()
    }

    fun signedIn(connection: Connection, note: String? = null) {
        this.connection = connection
        val store = LibraryStore(connection.client, scope)
        library = store
        fetches = if (connection.acquires) Fetches(connection.client, scope, onArrived = { store.load() }) else null
        search = SearchModel(connection, { store.index }, { playlists }, scope)
        home = HomeStore(connection, scope)
        notice = note
        noticeDetail = null
        starOverrides.clear()
        failedSongs.clear()
        startListening(connection)
        store.load()
        refreshPlaylists()
        // Back and forward start afresh for this account.
        navigator.startOver()
    }

    // This account's queue comes back, its waiting plays are sent, and a
    // queue saved on another device may be offered.
    private fun startListening(connection: Connection) {
        val folder = listeningRoot?.let { listeningFolder(it, connection.client.username, connection.server.address) }
        queueKeeper.folder = folder
        queueSync.folder = folder
        queueSync.reset()
        scope.launch {
            if (folder != null) queueKeeper.restore()?.let(queueSync::restored)
            queueSync.check(force = true)
        }
        plays.signedIn()
    }

    fun signOut() {
        // What was playing counts, and the queue is kept for next time.
        plays.flush()
        if (listeningRoot != null) queueKeeper.saveNow()
        queueKeeper.folder = null
        queueSync.folder = null
        queueSync.reset()
        player.clear()
        // The server is forgotten at once; the password store is left to
        // finish off the window's thread.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { accounts.signOut() }
        signInForm = SignInForm(accounts.last)
        connection = null
        library = null
        fetches = null
        search = null
        home = null
        playlists = emptyList()
        fullPlayer = false
        navigator.startOver()
    }

    fun refreshPlaylists() {
        val client = connection?.client ?: return
        scope.launch {
            try {
                playlists = client.playlists()
            } catch (e: SubsonicException) {
                // The sidebar keeps what it had.
            }
        }
    }

    fun toggleSidePanel(panel: SidePanel) = showSidePanel(if (sidePanel == panel) null else panel)

    // Opens the panel on a tab, or closes it with null.
    fun showSidePanel(panel: SidePanel?) {
        sidePanel = panel
        settings.update { it.copy(sidePanel = panel?.key) }
    }

    // A song whose details the Info tab shows instead of the playing one's,
    // until it is let go.
    var infoSong by mutableStateOf<Song?>(null)

    fun showInfo(song: Song?) {
        infoSong = song
        showSidePanel(SidePanel.Info)
    }

    // The frame's own settings, changed as the listener drags and folds it.
    fun updateFrame(change: (FramePrefs) -> FramePrefs) =
        settings.update { it.copy(frame = change(it.frame)) }

    fun isPinned(playlistId: String) = playlistId in settings.current.frame.pinnedPlaylists

    fun setPinned(playlistId: String, pinned: Boolean) = updateFrame { frame ->
        frame.copy(pinnedPlaylists = if (pinned) (frame.pinnedPlaylists - playlistId) + playlistId else frame.pinnedPlaylists - playlistId)
    }

    // Playing and queueing.

    fun play(songs: List<Song>, start: Int = 0, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        player.play(songs, if (shuffle) (songs.indices).random() else start, shuffle)
    }

    fun playNext(songs: List<Song>) = player.playNext(songs)

    fun addToQueue(songs: List<Song>) = player.addToQueue(songs)

    fun setVolume(volume: Float) {
        player.setVolume(volume)
        settings.update { it.copy(playback = it.playback.copy(volume = volume.coerceIn(0f, 1f))) }
    }

    // Plays to another output, remembered for the next run.
    fun selectOutput(id: String) {
        player.selectOutput(id)
        settings.update { it.copy(playback = it.playback.copy(outputDevice = id.takeUnless { it == DEFAULT_OUTPUT })) }
    }

    // Favourites, as server stars. A heart set or cleared here shows from
    // the overrides until the next library read brings the server's own,
    // so the library is not read or sorted again for it.

    fun isStarred(song: Song): Boolean = starOverrides[song.id] ?: (song.starred != null)

    fun setStarred(songs: List<Song>, starred: Boolean) {
        val client = connection?.client ?: return
        val ids = songs.map { it.id }.distinct()
        ids.forEach { starOverrides[it] = starred }
        scope.launch {
            try {
                if (starred) client.star(ids) else client.unstar(ids)
            } catch (e: SubsonicException) {
                ids.forEach { starOverrides.remove(it) }
                notice = "Couldn't change favourites: ${e.userMessage()}"
            }
        }
    }

    fun isAlbumStarred(id: String, starred: String?): Boolean = starOverrides["album:$id"] ?: (starred != null)

    fun isArtistStarred(id: String, starred: String?): Boolean = starOverrides["artist:$id"] ?: (starred != null)

    // Albums and artists as favourites. Only for what is in the library: on
    // Octo, starring an album found online would download it.
    fun setAlbumStarred(id: String, starred: Boolean) = starWhole("album:$id", starred) { client ->
        if (starred) client.starAlbums(listOf(id)) else client.unstarAlbums(listOf(id))
    }

    fun setArtistStarred(id: String, starred: Boolean) = starWhole("artist:$id", starred) { client ->
        if (starred) client.starArtists(listOf(id)) else client.unstarArtists(listOf(id))
    }

    private fun starWhole(key: String, starred: Boolean, call: suspend (app.winters.octo.subsonic.SubsonicClient) -> Unit) {
        val client = connection?.client ?: return
        starOverrides[key] = starred
        scope.launch {
            try {
                call(client)
            } catch (e: SubsonicException) {
                starOverrides.remove(key)
                notice = "Couldn't change favourites: ${e.userMessage()}"
            }
        }
    }

    // Playlists.

    fun addToPlaylist(playlistId: String, songs: List<Song>) {
        val client = connection?.client ?: return
        val formPost = connection?.server?.extensions?.any { it.startsWith("$FORM_POST_EXTENSION:") } == true
        scope.launch {
            try {
                client.updatePlaylist(playlistId, songIdsToAdd = songs.map { it.id }, formPost = formPost)
                refreshPlaylists()
            } catch (e: SubsonicException) {
                notice = "Couldn't add to the playlist: ${e.userMessage()}"
            }
        }
    }

    fun createPlaylist(name: String, songs: List<Song>, then: (String?) -> Unit = {}) {
        val client = connection?.client ?: return
        val formPost = connection?.server?.extensions?.any { it.startsWith("$FORM_POST_EXTENSION:") } == true
        scope.launch {
            try {
                val made = client.createPlaylist(name.trim(), songs.map { it.id }, formPost)
                refreshPlaylists()
                then(made?.id)
            } catch (e: SubsonicException) {
                notice = "Couldn't make the playlist: ${e.userMessage()}"
                then(null)
            }
        }
    }

    // Songs of an album, for the menus on album cards.
    suspend fun albumSongs(id: String): List<Song> = connection?.client?.album(id)?.song.orEmpty()

    // Sort orders kept between runs.

    var songOrder by mutableStateOf(SortList.Songs.decode(settings.current.songSort))
        private set

    fun sortSongs(order: SortOrder) {
        songOrder = order
        settings.update { it.copy(songSort = SortList.Songs.encode(order)) }
    }

    var albumOrder by mutableStateOf(SortList.Albums.decode(settings.current.albumSort))
        private set

    fun sortAlbums(order: SortOrder) {
        albumOrder = order
        settings.update { it.copy(albumSort = SortList.Albums.encode(order)) }
    }

    // The window's keyboard shortcuts.
    fun perform(shortcut: Shortcut): Boolean {
        when (shortcut) {
            Shortcut.PlayPause -> player.togglePlay()
            Shortcut.SeekBack -> player.seekTo(player.positionMs() - SEEK_STEP_MS)
            Shortcut.SeekForward -> player.seekTo(player.positionMs() + SEEK_STEP_MS)
            Shortcut.VolumeUp -> setVolume(player.state.value.volume + VOLUME_STEP)
            Shortcut.VolumeDown -> setVolume(player.state.value.volume - VOLUME_STEP)
            Shortcut.Search -> {
                if (connection == null) return false
                // The field is in the title bar on every page, so only the
                // keyboard moves there.
                fullPlayer = false
                runCatching { searchFocus.requestFocus() }
            }
            Shortcut.Lyrics -> if (connection != null) toggleSidePanel(SidePanel.Lyrics) else return false
            Shortcut.Queue -> if (connection != null) toggleSidePanel(SidePanel.Queue) else return false
            Shortcut.Info -> if (connection != null) toggleSidePanel(SidePanel.Info) else return false
            Shortcut.Sidebar -> if (connection != null) updateFrame { it.copy(sidebarRail = !it.sidebarRail) } else return false
            Shortcut.MiniPlayer -> toggleMiniPlayer?.invoke() ?: return false
            Shortcut.Settings -> {
                if (connection == null) return false
                fullPlayer = false
                navigator.go(Page.Settings)
            }
            Shortcut.Back -> navigator.back()
            Shortcut.Forward -> navigator.forward()
            Shortcut.CloseLayer -> when {
                popups.open -> popups.close()
                fullPlayer -> fullPlayer = false
                else -> return false
            }
        }
        return true
    }
}

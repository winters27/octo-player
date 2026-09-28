package app.winters.octo.desktop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import app.winters.octo.desktop.audio.SoundTarget
import app.winters.octo.desktop.library.LibraryStore
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
import app.winters.octo.desktop.player.wash.WashCovers
import app.winters.octo.desktop.search.Fetches
import app.winters.octo.desktop.search.SearchModel
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.Connection
import app.winters.octo.desktop.server.userMessage
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.design.PopupHost
import app.winters.octo.lyrics.OnlineLyrics
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.subsonic.FORM_POST_EXTENSION
import app.winters.octo.subsonic.Playlist
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

// The panel that can open on the right of the main area.
enum class SidePanel(val key: String) {
    Queue("queue"),
    Lyrics("lyrics"),
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
) {
    // The Sound page's settings, kept on the engine; none for the silent player.
    val sound: SoundController? = (player as? SoundTarget)?.let { SoundController(it, settings, scope) }

    // The lyrics of the song playing, for the side panel and the full player.
    val lyrics = LyricsModel(player, LyricsSources({ connection }, http, lyricsLibrary, settings), settings, scope)

    val navigator = Navigator()
    val popups = PopupHost()
    val searchFocus = FocusRequester()

    var connection by mutableStateOf<Connection?>(null)
        private set
    var library by mutableStateOf<LibraryStore?>(null)
        private set
    var fetches by mutableStateOf<Fetches?>(null)
        private set
    var search by mutableStateOf<SearchModel?>(null)
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

    val mac: Boolean get() = os == DesktopOs.Mac

    init {
        accounts.restore()?.let(::signedIn)
    }

    fun signedIn(connection: Connection, note: String? = null) {
        this.connection = connection
        val store = LibraryStore(connection.client, scope)
        library = store
        fetches = if (connection.acquires) Fetches(connection.client, scope, onArrived = { store.load() }) else null
        search = SearchModel(connection, { store.index }, { playlists }, scope)
        notice = note
        starOverrides.clear()
        store.load()
        refreshPlaylists()
        // Back and forward start afresh for this account.
        navigator.startOver()
    }

    fun signOut() {
        player.clear()
        accounts.signOut()
        connection = null
        library = null
        fetches = null
        search = null
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

    fun toggleSidePanel(panel: SidePanel) {
        sidePanel = if (sidePanel == panel) null else panel
        settings.update { it.copy(sidePanel = sidePanel?.key) }
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

    // Favourites, as server stars.

    fun isStarred(song: Song): Boolean = starOverrides[song.id] ?: (song.starred != null)

    fun setStarred(songs: List<Song>, starred: Boolean) {
        val client = connection?.client ?: return
        val ids = songs.map { it.id }.distinct()
        ids.forEach { starOverrides[it] = starred }
        scope.launch {
            try {
                if (starred) client.star(ids) else client.unstar(ids)
                library?.markStarred(ids.toSet(), starred)
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
                fullPlayer = false
                navigator.go(Page.Search)
                runCatching { searchFocus.requestFocus() }
            }
            Shortcut.Lyrics -> if (connection != null) toggleSidePanel(SidePanel.Lyrics) else return false
            Shortcut.Queue -> if (connection != null) toggleSidePanel(SidePanel.Queue) else return false
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

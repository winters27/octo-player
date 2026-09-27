package app.winters.octo.desktop.library

import app.winters.octo.catalog.sortKey
import app.winters.octo.desktop.server.userMessage
import app.winters.octo.discovery.TitleIndex
import app.winters.octo.discovery.knownLengthMs
import app.winters.octo.discovery.sameSong
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Artist
import app.winters.octo.subsonic.Library
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.readLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// A genre, with how many songs and albums the library has in it.
data class GenreCount(val name: String, val songs: Int, val albums: Int)

// The whole library as the server lists it, read once after signing in and
// kept in memory, for the pages that show everything (Songs, Albums,
// Artists, Genres, History) and for telling library songs from songs a
// search found online.
class LibraryIndex(val songs: List<Song>, val albums: List<Album>, val artists: List<Artist>) {
    private val songIds = songs.mapTo(HashSet()) { it.id }
    private val albumIds = albums.mapTo(HashSet()) { it.id }
    private val artistIds = artists.mapTo(HashSet()) { it.id }
    private val byTitle = TitleIndex(songs, { it.title }, { it.artist.orEmpty() })

    // Every genre, with its counts, A to Z. A song's genres are its
    // OpenSubsonic list when it has one, else its single genre.
    val genres: List<GenreCount> by lazy {
        val songCounts = HashMap<String, Int>()
        val albumSets = HashMap<String, HashSet<String>>()
        val names = HashMap<String, String>()
        songs.forEach { song ->
            genresOf(song).forEach { genre ->
                val key = genre.lowercase()
                names.putIfAbsent(key, genre)
                songCounts[key] = (songCounts[key] ?: 0) + 1
                song.albumId?.let { albumSets.getOrPut(key) { HashSet() } += it }
            }
        }
        songCounts.keys.map { GenreCount(names.getValue(it), songCounts.getValue(it), albumSets[it]?.size ?: 0) }
            .sortedBy { sortKey(it.name) }
    }

    fun songsInGenre(name: String): List<Song> = songs.filter { song -> genresOf(song).any { it.equals(name, ignoreCase = true) } }

    fun albumsInGenre(name: String): List<Album> {
        val ids = songsInGenre(name).mapNotNullTo(HashSet()) { it.albumId }
        return albums.filter { it.id in ids }
    }

    fun hasAlbum(id: String) = id in albumIds

    fun hasArtist(id: String) = id in artistIds

    // Whether a song a server sent is in the library: the same id, or the
    // same song by the same artist (a find the server has since downloaded
    // under a new id). The same rules the phone app uses.
    fun holds(song: Song): Boolean {
        if (song.id in songIds) return true
        val length = knownLengthMs(song)
        return byTitle.candidates(song.title, song.artist.orEmpty()).any { sameSong(song.title, song.artist.orEmpty(), length, it.title, it.artist.orEmpty(), knownLengthMs(it)) }
    }

    // Songs that have been played, most recently first.
    val history: List<Song> by lazy { recentlyPlayed(songs) }

    companion object {
        fun of(library: Library) = LibraryIndex(library.songs, library.albums, library.artists)

        fun genresOf(song: Song): List<String> =
            song.genres.ifEmpty { listOfNotNull(song.genre?.trim()?.takeIf(String::isNotEmpty)) }
    }
}

sealed interface LibraryState {
    data object Idle : LibraryState
    data object Loading : LibraryState
    class Ready(val index: LibraryIndex) : LibraryState
    class Failed(val message: String) : LibraryState
}

// Reads the library from the server, once, and again on request.
class LibraryStore(private val client: SubsonicClient, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<LibraryState>(LibraryState.Idle)
    val state: StateFlow<LibraryState> = _state
    private var job: Job? = null

    val index: LibraryIndex? get() = (state.value as? LibraryState.Ready)?.index

    fun load() {
        if (job?.isActive == true) return
        val previous = index
        if (previous == null) _state.value = LibraryState.Loading
        job = scope.launch {
            _state.value = try {
                LibraryState.Ready(LibraryIndex.of(client.readLibrary()))
            } catch (e: SubsonicException) {
                // A failed refresh keeps what was read before.
                previous?.let { LibraryState.Ready(it) } ?: LibraryState.Failed(e.userMessage())
            }
        }
    }

    // Songs starred or unstarred here show at once, before the next read.
    fun markStarred(ids: Set<String>, starred: Boolean) {
        val index = index ?: return
        val stamp = if (starred) java.time.Instant.now().toString() else null
        val songs = index.songs.map { if (it.id in ids) it.copy(starred = stamp) else it }
        _state.value = LibraryState.Ready(LibraryIndex(songs, index.albums, index.artists))
    }
}

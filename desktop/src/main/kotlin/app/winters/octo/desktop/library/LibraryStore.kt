package app.winters.octo.desktop.library

import app.winters.octo.catalog.SongIdentity
import app.winters.octo.desktop.server.userMessage
import app.winters.octo.discovery.IsrcIndex
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val byIsrc = IsrcIndex(songs) { it.isrc }

    // Whether the server marks any song as in a family member's own
    // library. One that marks none is one that never marks.
    val marksPersonal: Boolean by lazy { songs.any { it.octoPersonal } }

    // Every genre, with its counts, A to Z. A song's genres are its
    // OpenSubsonic list when it has one, else its single genre. Worked out
    // with the rest of the index, away from the window's thread.
    val genres: List<GenreCount> = run {
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
        sortedByName(songCounts.keys.map { GenreCount(names.getValue(it), songCounts.getValue(it), albumSets[it]?.size ?: 0) }) { it.name }
    }

    fun songsInGenre(name: String): List<Song> = songs.filter { song -> genresOf(song).any { it.equals(name, ignoreCase = true) } }

    fun albumsInGenre(name: String): List<Album> {
        val ids = songsInGenre(name).mapNotNullTo(HashSet()) { it.albumId }
        return albums.filter { it.id in ids }
    }

    fun hasAlbum(id: String) = id in albumIds

    // The library's album of an album found online with its very name and
    // artist (case, accents and punctuation aside), as the server judges
    // it; nothing when there is none. Octo leaves such an album out of a
    // search itself; this is for a server that does not yet.
    fun namesake(album: Album): Album? = byNameAndArtist[nameAndArtistKey(album)]

    private val byNameAndArtist: Map<String, Album> by lazy {
        albums.filter { SongIdentity.key(it.name).isNotEmpty() }.associateBy(::nameAndArtistKey)
    }

    private fun nameAndArtistKey(album: Album) = SongIdentity.key(album.name) + "|" + SongIdentity.key(album.artist)

    fun hasArtist(id: String) = id in artistIds

    // Whether a song a server sent is in the library: the same id, or the
    // same song by the same artist (a find the server has since downloaded
    // under a new id), or a song with the same ISRC under another title. The
    // same rules the phone app uses.
    fun holds(song: Song): Boolean {
        if (song.id in songIds) return true
        val length = knownLengthMs(song)
        val candidates = byTitle.candidates(song.title, song.artist.orEmpty()) + byIsrc.candidates(song.isrc)
        return candidates.any { sameSong(song.title, song.artist.orEmpty(), length, it.title, it.artist.orEmpty(), knownLengthMs(it), song.isrc, it.isrc) }
    }

    // The library's own copy of a song it holds, by the same rules; nothing
    // when it does not hold it.
    fun copyOf(song: Song): Song? {
        if (song.id in songIds) return songs.firstOrNull { it.id == song.id }
        val length = knownLengthMs(song)
        val candidates = byTitle.candidates(song.title, song.artist.orEmpty()) + byIsrc.candidates(song.isrc)
        return candidates.firstOrNull { sameSong(song.title, song.artist.orEmpty(), length, it.title, it.artist.orEmpty(), knownLengthMs(it), song.isrc, it.isrc) }
    }

    // Songs that have been played, most recently first.
    val history: List<Song> = recentlyPlayed(songs)

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

// Reads the library from the server, once, and again on request. The
// reading, the passes that drop repeats and the index are all done away
// from the window's thread (a big library takes over a second); only the
// finished index is handed back to the state the pages read.
class LibraryStore(private val read: suspend () -> Library, private val scope: CoroutineScope) {
    constructor(client: SubsonicClient, scope: CoroutineScope) : this({ client.readLibrary() }, scope)

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
                LibraryState.Ready(withContext(Dispatchers.Default) { LibraryIndex.of(read()) })
            } catch (e: SubsonicException) {
                // A failed refresh keeps what was read before.
                previous?.let { LibraryState.Ready(it) } ?: LibraryState.Failed(e.userMessage())
            }
        }
    }
}

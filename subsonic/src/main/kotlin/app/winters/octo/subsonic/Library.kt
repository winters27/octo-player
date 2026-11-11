package app.winters.octo.subsonic

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// How many songs or albums each request asks for when reading everything.
const val LIBRARY_PAGE = 500

// How many albums are asked for at once when songs are read album by album.
private const val ALBUMS_AT_ONCE = 4

// Everything on a server, as it lists it.
class Library(songs: List<Song>, val albums: List<Album>, val artists: List<Artist>) {
    var songs: List<Song> = songs
        private set

    // Hands the songs over and lets go of them. A caller that turns the songs
    // into other rows can free each one as it goes, instead of holding the
    // whole library and all the rows made from it at once (the peak that ran
    // a phone out of memory on a library of 100,000 songs).
    fun takeSongs(): List<Song> = songs.also { songs = emptyList() }
}

// Reads the whole library in pages. Songs come from an empty search where
// the server allows one, and otherwise album by album. Anything listed
// twice is kept once: when the library changes during the read, the pages
// shift and the same item can come back on the next page. Songs the server
// marks as outside the library (an Octo album lists the tracks it lacks
// too) are left out. Albums, artists and songs are read side by side: a
// server that takes half a second a request answers in half the time.
//
// With `slim`, each song is cut down as its page arrives to what a copy of the
// library keeps, so a library of 100,000 songs takes about half the memory
// (see `slimmed`). Only for a caller that does not use the dropped fields.
suspend fun SubsonicClient.readLibrary(page: Int = LIBRARY_PAGE, slim: Boolean = false): Library = coroutineScope {
    val pool = HashMap<String, String>()
    fun List<Song>.cut() = if (slim) map { it.slimmed(pool) } else this
    val albumsRead = async { pages(page) { offset -> albumList(AlbumListType.ALPHABETICAL, page, offset) }.distinctBy { it.id } }
    val artistsRead = async { artists().flatMap { it.artist }.distinctBy { it.id } }
    val listed = pages(page) { offset -> songPage(page, offset).cut() }
    val albums = albumsRead.await()
    val songs = listed
        .ifEmpty { songsByAlbum(albums).cut() }
        .filterNot { it.isExternal }
        .distinctBy { it.id }
    Library(songs, albums, artistsRead.await())
}

// A song without what only a song's own page uses (where its file lives, the
// folder, its sort name, its ISRCs, which radio suggested it), and with the
// text that repeats down a library (an album's name and id, an artist's name,
// the genre, the file type, the date) held once instead of once per song.
// Servers send each of those as a new string for every song.
fun Song.slimmed(pool: MutableMap<String, String>): Song {
    fun String?.once(): String? = this?.let { pool.getOrPut(it) { it } }
    fun List<ArtistRef>.once() = map { ArtistRef(it.id.once()!!, it.name.once()!!) }
    return copy(
        album = album.once(),
        albumId = albumId.once(),
        artist = artist.once(),
        artistId = artistId.once(),
        displayArtist = displayArtist.once(),
        displayAlbumArtist = displayAlbumArtist.once(),
        suffix = suffix.once(),
        contentType = contentType.once(),
        genre = genre.once(),
        created = created.once(),
        genres = genres.map { pool.getOrPut(it) { it } },
        artists = artists.once(),
        albumArtists = albumArtists.once(),
        sortName = null,
        isrc = emptyList(),
        parent = null,
        path = null,
        octoSuggestedBy = null,
    )
}

// Asks for page after page until one comes back short.
private suspend fun <T> pages(size: Int, fetch: suspend (offset: Int) -> List<T>): List<T> {
    val all = mutableListOf<T>()
    while (true) {
        val page = fetch(all.size)
        all += page
        if (page.size < size) return all
    }
}

private suspend fun SubsonicClient.songsByAlbum(albums: List<Album>): List<Song> = coroutineScope {
    albums.chunked(ALBUMS_AT_ONCE).flatMap { batch ->
        batch.map { listed ->
            // An album removed since the list was read is simply skipped.
            async {
                try {
                    album(listed.id).song
                } catch (e: SubsonicException.NotFound) {
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }
}

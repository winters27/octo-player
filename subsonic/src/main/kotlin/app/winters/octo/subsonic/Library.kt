package app.winters.octo.subsonic

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// How many songs or albums each request asks for when reading everything.
const val LIBRARY_PAGE = 500

// How many albums are asked for at once when songs are read album by album.
private const val ALBUMS_AT_ONCE = 4

// Everything on a server, as it lists it.
class Library(val songs: List<Song>, val albums: List<Album>, val artists: List<Artist>)

// How far a read of the whole library is: songs read so far (a song a
// shifted page repeats counts again until the read is `whole`, when the
// count is of the songs kept), the songs the albums hold once every album
// is listed (null before), and now and then the cover of a song that came.
data class ReadProgress(val songs: Long, val listed: Long?, val cover: String? = null, val whole: Boolean = false)

// How many song pages a read goes between covers it hands on.
private const val PAGES_PER_COVER = 10

// Reads the whole library in pages. Songs come from an empty search where
// the server allows one, and otherwise album by album. Anything listed
// twice is kept once: when the library changes during the read, the pages
// shift and the same item can come back on the next page. Songs the server
// marks as outside the library (an Octo album lists the tracks it lacks
// too) are left out. Albums, artists and songs are read side by side: a
// server that takes half a second a request answers in half the time.
// `onProgress` hears how far it is after each page, from either side.
suspend fun SubsonicClient.readLibrary(page: Int = LIBRARY_PAGE, onProgress: (ReadProgress) -> Unit = {}): Library = coroutineScope {
    val read = AtomicLong(0)
    val listedSongs = AtomicReference<Long?>(null)
    var songPages = 0
    fun said(cover: String? = null, whole: Boolean = false) = onProgress(ReadProgress(read.get(), listedSongs.get(), cover, whole))
    fun pageRead(songs: List<Song>) {
        read.addAndGet(songs.count { !it.isExternal }.toLong())
        songPages++
        said(if (songPages == 1 || songPages % PAGES_PER_COVER == 0) songs.firstNotNullOfOrNull { it.coverArt } else null)
    }
    val albumsRead = async {
        pages(page) { offset -> albumList(AlbumListType.ALPHABETICAL, page, offset) }.distinctBy { it.id }.also { albums ->
            listedSongs.set(albums.sumOf { it.songCount.toLong() })
            said()
        }
    }
    val artistsRead = async { artists().flatMap { it.artist }.distinctBy { it.id } }
    val listed = pages(page) { offset -> songPage(page, offset).also(::pageRead) }
    val albums = albumsRead.await()
    val songs = listed
        .ifEmpty { songsByAlbum(albums, ::pageRead) }
        .filterNot { it.isExternal }
        .distinctBy { it.id }
    read.set(songs.size.toLong())
    said(whole = true)
    Library(songs, albums, artistsRead.await())
}

// Where readLibraryInto puts what it reads, a page at a time as each one
// arrives: albums in list order with where their page starts, every artist
// at once, and songs, without the ones outside the library. A page can
// repeat what an earlier one listed; the first one counts.
interface LibrarySink {
    suspend fun albums(page: List<Album>, offset: Int)

    // Every album is listed: after the last album page, before any song
    // read album by album.
    suspend fun albumsListed() {}

    suspend fun artists(artists: List<Artist>)

    suspend fun songs(page: List<Song>)
}

// Reads the whole library as readLibrary does, handing each page on as it
// comes instead of keeping it, so nothing the size of the library is held:
// albums, artists and songs side by side, and songs album by album when the
// empty search answers nothing.
suspend fun SubsonicClient.readLibraryInto(sink: LibrarySink, page: Int = LIBRARY_PAGE) = coroutineScope {
    val albumIds = async {
        val ids = LinkedHashSet<String>()
        eachPage(page, { offset -> albumList(AlbumListType.ALPHABETICAL, page, offset) }) { offset, albums ->
            sink.albums(albums, offset)
            albums.forEach { ids += it.id }
        }
        sink.albumsListed()
        ids.toList()
    }
    val artistsRead = async { sink.artists(artists().flatMap { it.artist }.distinctBy { it.id }) }
    var listed = 0
    eachPage(page, { offset -> songPage(page, offset) }) { _, songs ->
        listed += songs.size
        if (songs.isNotEmpty()) sink.songs(songs.filterNot { it.isExternal })
    }
    if (listed == 0) songsByAlbumInto(albumIds.await(), sink)
    albumIds.await()
    artistsRead.await()
}

// Asks for page after page until one comes back short, handing each on
// with the offset it was asked at.
private suspend fun <T> eachPage(size: Int, fetch: suspend (offset: Int) -> List<T>, take: suspend (offset: Int, items: List<T>) -> Unit) {
    var offset = 0
    while (true) {
        val items = fetch(offset)
        take(offset, items)
        offset += items.size
        if (items.size < size) return
    }
}

private suspend fun SubsonicClient.songsByAlbumInto(albumIds: List<String>, sink: LibrarySink) = coroutineScope {
    albumIds.chunked(ALBUMS_AT_ONCE).forEach { batch ->
        val songs = batch.map { id ->
            // An album removed since the list was read is simply skipped.
            async {
                try {
                    album(id).song
                } catch (e: SubsonicException.NotFound) {
                    emptyList()
                }
            }
        }.awaitAll().flatten()
        sink.songs(songs.filterNot { it.isExternal })
    }
}

// Library items as text and back, exactly as they were read, for keeping a
// copy's pages on disk until they are built.
object LibraryJson {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        allowSpecialFloatingPointValues = true
    }

    fun encodeSong(song: Song): String = json.encodeToString(Song.serializer(), song)

    fun decodeSong(text: String): Song = json.decodeFromString(Song.serializer(), text)

    fun encodeAlbum(album: Album): String = json.encodeToString(Album.serializer(), album)

    fun decodeAlbum(text: String): Album = json.decodeFromString(Album.serializer(), text)

    fun encodeArtist(artist: Artist): String = json.encodeToString(Artist.serializer(), artist)

    fun decodeArtist(text: String): Artist = json.decodeFromString(Artist.serializer(), text)
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

// `read` hears the songs each batch of albums brought.
private suspend fun SubsonicClient.songsByAlbum(albums: List<Album>, read: (List<Song>) -> Unit = {}): List<Song> = coroutineScope {
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
        }.awaitAll().flatten().also(read)
    }
}

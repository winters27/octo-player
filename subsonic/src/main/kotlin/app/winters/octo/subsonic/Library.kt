package app.winters.octo.subsonic

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// How many songs or albums each request asks for when reading everything.
const val LIBRARY_PAGE = 500

// How many albums are asked for at once when songs are read album by album.
private const val ALBUMS_AT_ONCE = 4

// Everything on a server, as it lists it.
class Library(val songs: List<Song>, val albums: List<Album>, val artists: List<Artist>)

// Reads the whole library in pages. Songs come from an empty search where
// the server allows one, and otherwise album by album.
suspend fun SubsonicClient.readLibrary(page: Int = LIBRARY_PAGE): Library {
    val albums = pages(page) { offset -> albumList(AlbumListType.ALPHABETICAL, page, offset) }
    val artists = artists().flatMap { it.artist }
    val songs = pages(page) { offset -> songPage(page, offset) }
        .ifEmpty { songsByAlbum(albums) }
    return Library(songs, albums, artists)
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

package app.winters.octo.playback

import app.winters.octo.discovery.Discovered
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarSearchRowsTest {
    private fun answer(
        library: LibraryFound,
        outside: Discovered? = null,
        failed: Boolean = false,
    ) = CarSearchAnswer("tok", "words", "a1", library, outside, failed, null)

    @Test
    fun kindsComeInThePhonesOrderTheLibrarysFirst() {
        val rows = carSearchRows(
            answer(
                LibraryFound(
                    artists = listOf(artist("artist:1")),
                    albums = listOf(album("album:1")),
                    songs = listOf(track("device:1")),
                    playlists = listOf(playlist("p1")),
                ),
                Discovered(listOf(track("find:1")), listOf(onlineAlbum("ext-a")), listOf(onlineArtist("ext-r")), partAlbums = listOf(onlineAlbum("ext-p", owned = 2))),
            ),
        )
        assertEquals(listOf("Artists", "Songs", "Albums", "Playlists"), rows.map { it.title })
        assertEquals(
            listOf(SearchEntry.LibraryArtist::class, SearchEntry.OutsideArtist::class),
            rows[0].entries.map { it::class },
        )
        assertEquals(listOf("device:1", "find:1"), rows[1].entries.map { (it as SearchEntry.Song).track.id })
        assertEquals(
            listOf("album:1", "ext-p", "ext-a"),
            rows[2].entries.map {
                when (it) {
                    is SearchEntry.LibraryAlbum -> it.album.id
                    is SearchEntry.OutsideAlbum -> it.album.id
                    else -> error("not an album")
                }
            },
        )
    }

    @Test
    fun songsShowThreeOfTheLibrarysAndFourFoundThenSeeAll() {
        val rows = carSearchRows(
            answer(
                LibraryFound(songs = (1..10).map { track("device:$it") }),
                Discovered((1..10).map { track("find:$it") }, emptyList(), emptyList()),
            ),
        )
        val songs = rows.single { it.title == "Songs" }.entries
        assertEquals(8, songs.size)
        assertEquals(SearchEntry.SeeAll(CarNode.Found("tok", FoundKind.Songs)), songs.last())
        assertTrue(songs.take(7).all { (it as SearchEntry.Song).list == CarNode.Found("tok", FoundKind.Songs) })
    }

    @Test
    fun aFailedLookOutsideOffersToTryAgainUnderTheSongs() {
        val rows = carSearchRows(answer(LibraryFound(songs = listOf(track("device:1"))), failed = true))
        assertEquals(SearchEntry.Retry("tok"), rows.single { it.title == "Songs" }.entries.last())
    }

    @Test
    fun artistsFoundOnlyOutsideComeLast() {
        val rows = carSearchRows(
            answer(LibraryFound(songs = listOf(track("device:1"))), Discovered(emptyList(), emptyList(), listOf(onlineArtist("ext-r")))),
        )
        assertEquals(listOf("Songs", "Artists"), rows.map { it.title })
    }

    @Test
    fun nothingFoundListsNothing() {
        assertTrue(carSearchRows(answer(LibraryFound())).isEmpty())
    }
}

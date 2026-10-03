package app.winters.octo.desktop.queue

import app.winters.octo.desktop.nav.Page
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// What the queue calls a list played from each page.
class QueueNamesTest {
    private val okComputer = (1..3).map { Song("s$it", "Song $it", album = "OK Computer", albumId = "a1") }
    private val mixed = okComputer + Song("k1", "Idioteque", album = "Kid A", albumId = "a2")
    private val playlists = mapOf("p1" to "Late night")

    private fun name(page: Page, songs: List<Song>) = queueNameFor(page, songs) { playlists[it] }

    @Test
    fun aPageOfOneListNamesItself() {
        assertEquals("Late night", name(Page.Playlist("p1"), mixed))
        assertEquals("Radiohead", name(Page.Artist("r1", "Radiohead"), mixed))
        assertEquals("Rock", name(Page.Genre("Rock"), mixed))
        assertEquals("Songs", name(Page.Songs, mixed))
        assertEquals("Favorites", name(Page.Favourites, mixed))
        assertEquals("Recently played", name(Page.History, mixed))
        assertEquals("Recently added", name(Page.RecentlyAdded, mixed))
        assertEquals("OK Computer", name(Page.Album("a1"), okComputer))
    }

    @Test
    fun cardsAndSearchNameTheAlbumWhenEverySongIsFromOne() {
        assertEquals("OK Computer", name(Page.Home, okComputer))
        assertEquals("OK Computer", name(Page.Albums, okComputer))
        assertEquals("OK Computer", name(Page.Search, okComputer))
        assertEquals("Search", name(Page.Search, mixed))
        assertNull(name(Page.Home, mixed))
        assertNull(name(Page.Home, emptyList()))
    }

    @Test
    fun aPlaylistNotKnownYetHasNoName() {
        assertNull(name(Page.Playlist("p9"), mixed))
    }

    @Test
    fun aRadioIsNamedForItsSong() {
        assertEquals("Karma Police radio", radioName("Karma Police"))
    }
}

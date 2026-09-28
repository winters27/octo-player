package app.winters.octo.desktop.home

import app.winters.octo.desktop.FakeServer
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Playlist
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HomeDataTest {
    private val server = FakeServer()

    @After fun stop() = server.close()

    private fun albums(vararg ids: String) = """"albumList2":{"album":[${ids.joinToString(",") { """{"id":"$it","name":"$it"}""" }}]}"""

    @Test
    fun eachShelfComesFromItsOwnList() = runBlocking {
        server.answer("getAlbumList2", albums("a1", "a2"))
        val home = loadHome(server.connection())
        assertEquals(listOf("a1", "a2"), home.recentlyAdded.map { it.id })
        val types = server.calls.map { it.url.queryParameter("type") }.filterNotNull().toSet()
        assertEquals("no random shelf: Home shows only what the library says", setOf("newest", "recent", "frequent"), types)
        assertTrue("favourites come from the starred list", "getStarred2" in server.endpoints())
    }

    @Test
    fun onlyOctoIsAskedForStations() = runBlocking {
        server.answer("getAlbumList2", albums("a1"))
        server.answer("getInternetRadioStations", """"internetRadioStations":{"internetRadioStation":[{"id":"st1","name":"Discover Weekly"}]}""")
        val plain = loadHome(server.connection())
        assertTrue(plain.stations.isEmpty())
        assertFalse("getInternetRadioStations" in server.endpoints())
        val octo = loadHome(server.connection(listOf("octoAcquisitions:1")))
        assertEquals(listOf("Discover Weekly"), octo.stations.map { it.name })
    }

    @Test
    fun oneBrokenShelfDoesNotBreakThePage() = runBlocking {
        server.answer("getAlbumList2", albums("a1"))
        server.fail("getInternetRadioStations", 0, "busy")
        val home = loadHome(server.connection(listOf("octoLyrics:1")))
        assertTrue(home.stations.isEmpty())
        assertFalse(home.isEmpty)
    }

    @Test
    fun whenNothingAnswersThePageSaysSo() = runBlocking {
        server.fail("getAlbumList2", 0, "down")
        try {
            loadHome(server.connection())
            fail("an empty Home was shown for a server that answered nothing")
        } catch (e: SubsonicException) {
            assertTrue(e.message!!.contains("down"))
        }
    }

    @Test
    fun favouritesShowTheNewestFavouriteFirst() {
        val albums = listOf(Album("old", starred = "2024-01-01T00:00:00Z"), Album("new", starred = "2026-09-01T00:00:00Z"), Album("undated", starred = "yes"))
        assertEquals(listOf("new", "old", "undated"), newestFavourites(albums).map { it.id })
    }

    @Test
    fun pinnedPlaylistsComeFirstInTheOrderTheyWerePinned() {
        val playlists = listOf(Playlist("a"), Playlist("b"), Playlist("c"), Playlist("d"))
        assertEquals(listOf("c", "a", "b", "d"), pinnedFirst(playlists, listOf("c", "a", "gone")).map { it.id })
    }
}

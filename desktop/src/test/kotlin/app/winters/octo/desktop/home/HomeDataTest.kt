package app.winters.octo.desktop.home

import app.winters.octo.desktop.FakeServer
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Playlist
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        assertTrue("favorites come from the starred list", "getStarred2" in server.endpoints())
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

    // A station Octo has since made anew is taken off the shelf at once,
    // and the shelves are read again.
    @Test
    fun aStationTheServerNoLongerHasLeavesTheShelf() {
        server.answer("getAlbumList2", albums("a1"))
        server.answer("getInternetRadioStations", """"internetRadioStations":{"internetRadioStation":[{"id":"st1","name":"Discover Weekly"},{"id":"st2","name":"Chill"}]}""")
        val store = HomeStore(server.connection(listOf("octoAcquisitions:1")), CoroutineScope(Dispatchers.Unconfined))
        store.refresh()
        waitFor { store.data?.stations?.size == 2 }
        server.answer("getInternetRadioStations", """"internetRadioStations":{"internetRadioStation":[{"id":"st2","name":"Chill"},{"id":"st3","name":"Morning"}]}""")
        store.stationGone("st1")
        assertFalse(store.data!!.stations.any { it.id == "st1" })
        waitFor { store.data?.stations?.map { it.id } == listOf("st2", "st3") }
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("waited too long", what())
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

    // Made for you has its own shelf, in kind order, and is not shown again
    // among the listener's own playlists.
    @Test
    fun madeForYouListsHaveTheirOwnShelf() {
        val playlists = listOf(
            Playlist("mine"),
            Playlist("og-deep", octoList = "deepCuts"),
            Playlist("og-new", octoList = "newReleases"),
            Playlist("og-later", octoList = "aKindFromANewerServer"),
            Playlist("og-re", octoList = "rediscover"),
            Playlist("og-liked", octoList = "liked"),
        )
        assertEquals(listOf("og-liked", "og-new", "og-re", "og-deep", "og-later"), madeForYou(playlists).map { it.id })
        assertEquals(listOf("mine"), yourPlaylists(playlists).map { it.id })
        assertTrue("a server without the lists marks none", madeForYou(listOf(Playlist("a"), Playlist("b"))).isEmpty())
    }
}

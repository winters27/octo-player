package app.winters.octo.desktop.home

import app.winters.octo.desktop.FakeServer
import app.winters.octo.subsonic.Album
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
        assertEquals(setOf("newest", "recent", "frequent", "random"), types)
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
    fun theRandomPickStaysUntilNewAlbumsComeIn() {
        fun shelf(vararg ids: String) = ids.map { Album(it) }
        val shown = HomeData(recentlyAdded = shelf("n1"), random = shelf("r1", "r2"))
        val again = HomeData(recentlyAdded = shelf("n1"), mostPlayed = shelf("m1"), random = shelf("r3"))
        val kept = keepRandom(shown, again)
        assertEquals(listOf("r1", "r2"), kept.random.map { it.id })
        assertEquals("the other shelves are fresh", listOf("m1"), kept.mostPlayed.map { it.id })
        val newer = HomeData(recentlyAdded = shelf("n2", "n1"), random = shelf("r3"))
        assertEquals(listOf("r3"), keepRandom(shown, newer).random.map { it.id })
        assertEquals(listOf("r3"), keepRandom(null, again).random.map { it.id })
    }
}

package app.winters.octo.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Octo's ranked lists: an artist's top songs and the chart, each song in the
// library or found online, as Octo sends them.
class TopSongsTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client() = SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient())

    private fun answer(body: String) = server.enqueue(MockResponse.Builder().body(body).build())

    private fun ok(inner: String) = """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","openSubsonic":true,$inner}}"""

    private val list = """"topSongs":{"artist":"Daft Punk","source":"lastfm","entry":[
        {"rank":1,"plays":2536925,"listeners":220546,"inLibrary":true,"song":{"id":"nd-1","title":"Harder, Better, Faster, Stronger","artist":"Daft Punk","album":"Discovery","isExternal":false,"starred":"2026-01-01T00:00:00Z"}},
        {"rank":2,"plays":null,"listeners":null,"inLibrary":false,"song":{"id":"Xy12","title":"Aerodynamic","artist":"Daft Punk","album":"Discovery","isExternal":true,"duration":212},"later":1}
    ]}"""

    @Test
    fun asksForTheArtistByNameAndIdAndReadsTheRanks() = runTest {
        answer(ok(list))
        val top = client().artistTopSongs("Daft Punk", artistId = "ar-27", count = 20)

        val request = server.takeRequest()
        assertEquals("/rest/getArtistTopSongs", request.url.encodedPath)
        assertEquals("Daft Punk", request.url.queryParameter("artist"))
        assertEquals("ar-27", request.url.queryParameter("id"))
        assertEquals("20", request.url.queryParameter("count"))

        assertEquals("Daft Punk", top.artist)
        assertEquals(TOP_SONGS_LASTFM, top.source)
        val (first, second) = top.entry
        assertEquals(1, first.rank)
        assertEquals(2_536_925L, first.plays)
        assertTrue(first.inLibrary)
        assertEquals("nd-1", first.song?.id)
        assertFalse(first.song!!.isExternal)
        assertEquals(2, second.rank)
        assertNull(second.plays)
        assertFalse(second.inLibrary)
        assertTrue(second.song!!.isExternal)
    }

    @Test
    fun theChartNamesNoArtist() = runTest {
        answer(ok(""""topSongs":{"artist":null,"source":"deezer","entry":[]}"""))
        val chart = client().topChart(50)

        val request = server.takeRequest()
        assertEquals("/rest/getTopChart", request.url.encodedPath)
        assertEquals("50", request.url.queryParameter("count"))
        assertNull(chart.artist)
        assertEquals(TOP_SONGS_DEEZER, chart.source)
        assertTrue(chart.entry.isEmpty())
    }

    @Test
    fun aServerThatSendsNoListHasNone() = runTest {
        answer(ok(""""x":1"""))
        assertTrue(client().topChart().entry.isEmpty())
    }
}

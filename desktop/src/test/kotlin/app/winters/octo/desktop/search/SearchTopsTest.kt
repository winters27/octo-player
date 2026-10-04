package app.winters.octo.desktop.search

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.library.LibraryIndex
import app.winters.octo.desktop.songJson
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Artist
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Search's ranked lists: the top songs of the artist a search names, and the
// chart for an empty search, only from a server that ranks them.
class SearchTopsTest {
    private val server = FakeServer()

    @After fun stop() = server.close()

    private val library = LibraryIndex(
        songs = listOf(Song("lib1", "One More Time", artist = "Daft Punk", duration = 320)),
        albums = listOf(Album("alb1", "Discovery")),
        artists = listOf(Artist("art1", "Daft Punk")),
    )

    private val top = """"topSongs":{"artist":"Daft Punk","source":"lastfm","entry":[
        {"rank":1,"plays":2500000,"inLibrary":true,"song":${songJson("lib1", "One More Time")}},
        {"rank":2,"plays":1500000,"inLibrary":false,"song":{"id":"ext9","title":"Aerodynamic","artist":"Daft Punk","isExternal":true}}
    ]}"""

    private fun model(extensions: List<String>, scope: CoroutineScope) =
        SearchModel(server.connection(extensions), { library }, { emptyList() }, scope)

    private suspend fun searched(model: SearchModel, text: String) {
        model.type(text)
        withTimeout(5_000) { while (model.state !is SearchState.Done) delay(20) }
    }

    @Test
    fun searchingAnArtistByNameAsksForTheirTopSongs() = runBlocking {
        server.answer("search3", """"searchResult3":{"artist":[{"id":"art1","name":"Daft Punk"}],"song":[${songJson("lib1", "One More Time")}]}""")
        server.answer("getArtistTopSongs", top, type = "octo")
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val model = model(listOf("octoTopSongs:1", "octoAcquisitions:1"), scope)

        searched(model, "daft punk")
        withTimeout(5_000) { while (model.tops.artist == null) delay(20) }

        val asked = server.calls.single { it.url.pathSegments.last() == "getArtistTopSongs" }.url
        assertEquals("Daft Punk", asked.queryParameter("artist"))
        assertEquals("art1", asked.queryParameter("id"))
        assertEquals(listOf("lib1", "ext9"), model.tops.artist!!.entry.map { it.song?.id })

        // Typing on forgets them until the next search comes back.
        model.type("daft punk o")
        assertNull(model.tops.artist)
        scope.cancel()
    }

    @Test
    fun aSearchThatNamesNoArtistAsksNothing() = runBlocking {
        server.answer("search3", """"searchResult3":{"artist":[{"id":"art1","name":"Daft Punk"}]}""")
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val model = model(listOf("octoTopSongs:1"), scope)

        searched(model, "daft")
        delay(200)

        assertNull(model.tops.artist)
        assertFalse(server.endpoints().contains("getArtistTopSongs"))
        scope.cancel()
    }

    @Test
    fun aServerThatDoesNotRankAsksNothing() = runBlocking {
        server.answer("search3", """"searchResult3":{"artist":[{"id":"art1","name":"Daft Punk"}]}""")
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val model = model(emptyList(), scope)

        searched(model, "daft punk")
        model.tops.loadChart()
        delay(200)

        assertFalse(server.endpoints().any { it == "getArtistTopSongs" || it == "getTopChart" })
        scope.cancel()
    }

    @Test
    fun theChartIsAskedOnceAndKept() = runBlocking {
        server.answer("getTopChart", """"topSongs":{"source":"deezer","entry":[{"rank":1,"song":{"id":"ext1","title":"Dracula","artist":"Tame Impala","isExternal":true}}]}""", type = "octo")
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val model = model(listOf("octoTopSongs:1"), scope)

        model.tops.loadChart()
        withTimeout(5_000) { while (model.tops.chart == null) delay(20) }
        model.tops.loadChart()
        delay(100)

        assertEquals(1, server.endpoints().count { it == "getTopChart" })
        assertEquals("50", server.calls.single().url.queryParameter("count"))
        assertTrue(model.tops.chart!!.entry.single().song!!.isExternal)
        scope.cancel()
    }
}

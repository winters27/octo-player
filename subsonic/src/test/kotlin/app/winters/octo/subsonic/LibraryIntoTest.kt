package app.winters.octo.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Collections

class LibraryIntoTest {
    private val server = MockWebServer()
    private val asked = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client() = SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient())

    private fun ok(key: String, body: String) = """{"subsonic-response":{"status":"ok","$key":$body}}"""

    private fun albums(vararg ids: String) =
        ok("albumList2", """{"album":[${ids.joinToString(",") { """{"id":"$it","name":"Album $it"}""" }}]}""")

    private fun songs(vararg ids: String) =
        ok("searchResult3", """{"song":[${ids.joinToString(",") { """{"id":"$it","title":"Song $it"${if (it.startsWith("x")) ""","isExternal":true""" else ""}}""" }}]}""")

    private fun album(id: String, vararg songIds: String) =
        ok("album", """{"id":"$id","name":"Album $id","song":[${songIds.joinToString(",") { """{"id":"$it","title":"Song $it","albumId":"$id"}""" }}]}""")

    private val artists = ok("artists", """{"index":[{"name":"A","artist":[{"id":"ar1","name":"A1"},{"id":"ar1","name":"A1"},{"id":"ar2","name":"A2"}]}]}""")

    private fun serve(answer: (endpoint: String, request: RecordedRequest) -> String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.url
                val endpoint = url.pathSegments.last()
                val detail = url.queryParameter("offset") ?: url.queryParameter("songOffset") ?: url.queryParameter("id")
                asked += listOfNotNull(endpoint, detail).joinToString(" ")
                return MockResponse.Builder().body(answer(endpoint, request)).build()
            }
        }
    }

    // Notes every call, in order, with what it was given.
    private class Recording : LibrarySink {
        val calls = Collections.synchronizedList(mutableListOf<String>())

        override suspend fun albums(page: List<Album>, offset: Int) {
            calls += "albums $offset ${page.joinToString(",") { it.id }}"
        }

        override suspend fun artists(artists: List<Artist>) {
            calls += "artists ${artists.joinToString(",") { it.id }}"
        }

        override suspend fun songs(page: List<Song>) {
            calls += "songs ${page.joinToString(",") { it.id }}"
        }
    }

    @Test
    fun eachPageIsHandedOnAsItArrivesWithOffsetsByRawSize() = runTest {
        serve { endpoint, request ->
            val offset = request.url.queryParameter("offset") ?: request.url.queryParameter("songOffset")
            when (endpoint) {
                "getAlbumList2" -> if (offset == "0") albums("a1", "a2") else albums("a2")
                "getArtists" -> artists
                "search3" -> when (offset) {
                    "0" -> songs("s1", "x1")
                    "2" -> songs("s1", "s2")
                    else -> songs("s3")
                }
                else -> error("Unexpected $endpoint")
            }
        }
        val sink = Recording()
        client().readLibraryInto(sink, page = 2)
        // Songs outside the library are left out, repeats are passed on (the
        // sink keeps the first), and an outside song still counts toward the offset.
        assertEquals(listOf("songs s1", "songs s1,s2", "songs s3"), sink.calls.filter { it.startsWith("songs") })
        assertEquals(listOf("albums 0 a1,a2", "albums 2 a2"), sink.calls.filter { it.startsWith("albums") })
        assertEquals(listOf("artists ar1,ar2"), sink.calls.filter { it.startsWith("artists") })
        assertEquals(listOf("search3 0", "search3 2", "search3 4"), asked.filter { it.startsWith("search3") })
    }

    @Test
    fun withoutAnEmptySearchSongsComeAlbumByAlbumInListOrder() = runTest {
        serve { endpoint, request ->
            when (endpoint) {
                "getAlbumList2" -> albums("a1", "gone", "a2", "a3", "a4", "a5")
                "getArtists" -> artists
                "search3" -> songs()
                "getAlbum" -> when (val id = request.url.queryParameter("id")) {
                    "gone" -> """{"subsonic-response":{"status":"failed","error":{"code":70,"message":"Not found"}}}"""
                    else -> album(id!!, "$id-1", "$id-2")
                }
                else -> error("Unexpected $endpoint")
            }
        }
        val sink = Recording()
        client().readLibraryInto(sink)
        // Four albums at a time, each batch in album list order.
        assertEquals(
            listOf("songs a1-1,a1-2,a2-1,a2-2,a3-1,a3-2", "songs a4-1,a4-2,a5-1,a5-2"),
            sink.calls.filter { it.startsWith("songs") },
        )
    }
}

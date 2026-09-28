package app.winters.octo.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Collections

class LibraryTest {
    private val server = MockWebServer()
    private val asked = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client() = SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient())

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name.json")).readText(Charsets.UTF_8)

    private fun ok(key: String, body: String) = """{"subsonic-response":{"status":"ok","$key":$body}}"""

    private fun albums(vararg ids: String) =
        ok("albumList2", """{"album":[${ids.joinToString(",") { """{"id":"$it","name":"Album $it"}""" }}]}""")

    private fun songs(vararg ids: String) =
        ok("searchResult3", """{"song":[${ids.joinToString(",") { """{"id":"$it","title":"Song $it"}""" }}]}""")

    // Answers each request by what it asks for, and notes it down.
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

    @Test
    fun songPageAsksForEverythingAndReadsLibraryFields() = runTest {
        server.enqueue(MockResponse.Builder().body(fixture("search3")).build())
        val page = client().songPage(size = 500, offset = 1000)
        val url = server.takeRequest().url
        assertEquals("", url.queryParameter("query"))
        assertEquals("500", url.queryParameter("songCount"))
        assertEquals("1000", url.queryParameter("songOffset"))
        assertEquals("0", url.queryParameter("artistCount"))
        assertEquals("0", url.queryParameter("albumCount"))

        val song = page.first()
        assertEquals("Kavinsky • Kavinsky feat. Angèle & Phoenix", song.displayAlbumArtist)
        assertEquals("Electro", song.genre)
        assertEquals("2026-09-20T15:51:15.236955739Z", song.created)
        assertEquals("2026-09-22T13:36:41.43536229Z", song.played)
        assertEquals(4L, song.playCount)
        assertEquals(44_100, song.samplingRate)
        assertEquals(16, song.bitDepth)
        assertEquals(1032, song.bitRate)
        // A plain Subsonic song has none of the extras.
        assertNull(page[1].played)
        assertNull(page[1].playCount)
    }

    @Test
    fun readsPagesUntilOneComesBackShort() = runTest {
        serve { endpoint, request ->
            val offset = request.url.queryParameter("offset") ?: request.url.queryParameter("songOffset")
            when (endpoint) {
                "getAlbumList2" -> if (offset == "0") albums("a1", "a2") else albums("a3")
                "getArtists" -> fixture("getArtists")
                "search3" -> when (offset) {
                    "0" -> songs("s1", "s2")
                    "2" -> songs("s3", "s4")
                    else -> songs()
                }
                else -> error("Unexpected $endpoint")
            }
        }
        val library = client().readLibrary(page = 2)
        assertEquals(listOf("a1", "a2", "a3"), library.albums.map { it.id })
        assertEquals(listOf("s1", "s2", "s3", "s4"), library.songs.map { it.id })
        assertEquals(4, library.artists.size)
        assertEquals(
            listOf("getAlbumList2 0", "getAlbumList2 2", "getArtists", "search3 0", "search3 2", "search3 4"),
            asked,
        )
    }

    @Test
    fun keepsOnceWhatShiftedPagesListTwice() = runTest {
        // Something added mid-read pushes the last of one page onto the next.
        serve { endpoint, request ->
            val offset = request.url.queryParameter("offset") ?: request.url.queryParameter("songOffset")
            when (endpoint) {
                "getAlbumList2" -> if (offset == "0") albums("a1", "a2") else albums("a2")
                "getArtists" -> fixture("getArtists")
                "search3" -> when (offset) {
                    "0" -> songs("s1", "s2")
                    "2" -> songs("s2", "s3")
                    else -> songs()
                }
                else -> error("Unexpected $endpoint")
            }
        }
        val library = client().readLibrary(page = 2)
        assertEquals(listOf("a1", "a2"), library.albums.map { it.id })
        assertEquals(listOf("s1", "s2", "s3"), library.songs.map { it.id })
    }

    @Test
    fun withoutAnEmptySearchSongsComeAlbumByAlbum() = runTest {
        serve { endpoint, request ->
            when (endpoint) {
                "getAlbumList2" -> albums("7wsVynyqPtHmsFWz6wUDCj", "gone")
                "getArtists" -> fixture("getArtists")
                "search3" -> songs()
                "getAlbum" ->
                    if (request.url.queryParameter("id") == "gone") fixture("error70") else fixture("getAlbum")
                else -> error("Unexpected $endpoint")
            }
        }
        val library = client().readLibrary()
        assertEquals(listOf("XEjJFBng9tY4swUvEp7Wj7"), library.songs.map { it.id })
        assertEquals(setOf("getAlbum 7wsVynyqPtHmsFWz6wUDCj", "getAlbum gone"), asked.filter { it.startsWith("getAlbum ") }.toSet())
    }
}

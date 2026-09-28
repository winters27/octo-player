package app.winters.octo.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Octo marks songs that are not files in the library (found online, for an
// album the library only partly has): they read as outside, and a copy of
// the library never takes them in.
class OutsideSongsTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client() = SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient())

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name.json")).readText(Charsets.UTF_8)

    @Test
    fun anAlbumListsItsSongsOutsideTheLibraryWithTheirMark() = runTest {
        server.enqueue(MockResponse.Builder().body(fixture("getAlbumPartlyOwned")).build())
        val songs = client().album("4pQx7N1rVb2mKc8sLd0Wz3").song
        assertEquals(listOf(false, true, true), songs.map { it.isExternal })

        val owned = songs[0]
        assertEquals("Randy Rogers Band/Randy Rogers Band/01 - Buy Myself a Chance.flac", owned.path)
        assertEquals(31_456_789L, owned.size)

        // Found online: how it streams, but no file on the server.
        val online = songs[1]
        assertNull(online.path)
        assertNull(online.size)
        assertNull(online.created)
        assertEquals("m4a", online.suffix)
        assertEquals(128, online.bitRate)
        assertEquals(245, online.duration)
        assertEquals(listOf("USUM70813712"), online.isrc)
    }

    @Test
    fun aSongWithNoMarkIsInTheLibrary() = runTest {
        server.enqueue(MockResponse.Builder().body(fixture("getAlbum")).build())
        assertFalse(client().album("7wsVynyqPtHmsFWz6wUDCj").song.single().isExternal)
    }

    @Test
    fun readingTheLibraryAlbumByAlbumLeavesOutSongsOutsideIt() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when (request.url.pathSegments.last()) {
                    "getAlbumList2" -> """{"subsonic-response":{"status":"ok","albumList2":{"album":[{"id":"4pQx7N1rVb2mKc8sLd0Wz3","name":"Randy Rogers Band"}]}}}"""
                    "getArtists" -> fixture("getArtists")
                    "search3" -> """{"subsonic-response":{"status":"ok","searchResult3":{}}}"""
                    "getAlbum" -> fixture("getAlbumPartlyOwned")
                    else -> error("Unexpected ${request.url}")
                }
                return MockResponse.Builder().body(body).build()
            }
        }
        val library = client().readLibrary()
        assertEquals(listOf("Ab3dE5fG7hJ9kL1mN3pQ5r"), library.songs.map { it.id })
        assertTrue(library.songs.none { it.isExternal })
    }

    @Test
    fun readingTheLibraryFromAnEmptySearchLeavesOutSongsOutsideIt() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val offset = request.url.queryParameter("songOffset")
                val body = when (request.url.pathSegments.last()) {
                    "getAlbumList2" -> """{"subsonic-response":{"status":"ok","albumList2":{}}}"""
                    "getArtists" -> fixture("getArtists")
                    "search3" -> if (offset == "0") {
                        """{"subsonic-response":{"status":"ok","searchResult3":{"song":[{"id":"s1","title":"Mine"},{"id":"s2","title":"Found online","isExternal":true}]}}}"""
                    } else {
                        """{"subsonic-response":{"status":"ok","searchResult3":{}}}"""
                    }
                    else -> error("Unexpected ${request.url}")
                }
                return MockResponse.Builder().body(body).build()
            }
        }
        assertEquals(listOf("s1"), client().readLibrary(page = 2).songs.map { it.id })
    }
}

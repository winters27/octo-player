package app.winters.octo.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The four folder calls: what each sends, and the folders each answers.
class PlaylistFoldersTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client() = SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient())

    private val answer = """{"subsonic-response":{"status":"ok","version":"1.16.1","playlistFolders":{"changed":"2026-11-14T10:00:00.000Z",
        "folder":[{"id":"pfAAAAAAAAAAAAAAAAAAAA","name":"Road trips","created":"2026-11-14T09:00:00.000Z"},
                  {"id":"pfBBBBBBBBBBBBBBBBBBBB","name":"Summer","parent":"pfAAAAAAAAAAAAAAAAAAAA","created":"2026-11-14T09:01:00.000Z"}],
        "place":[{"playlist":"pl1","folder":"pfBBBBBBBBBBBBBBBBBBBB"}]}}}"""

    private fun answered() = server.enqueue(MockResponse.Builder().body(answer).build())

    private fun params() = server.takeRequest().url.let { url -> url.queryParameterNames.associateWith { url.queryParameterValues(it) } }

    @Test
    fun theFoldersAndTheirPlacesAreRead() = runTest {
        answered()
        val got = client().playlistFolders()
        assertEquals("2026-11-14T10:00:00.000Z", got.changed)
        assertEquals(listOf("Road trips", "Summer"), got.folders.map { it.name })
        assertNull(got.folders[0].parent)
        assertEquals("pfAAAAAAAAAAAAAAAAAAAA", got.folders[1].parent)
        assertEquals(listOf(PlaylistPlace("pl1", "pfBBBBBBBBBBBBBBBBBBBB")), got.places)
        assertTrue(server.takeRequest().url.encodedPath.endsWith("/rest/getPlaylistFolders"))
    }

    @Test
    fun aSaveSendsItsParentOnlyWhenItHasOne() = runTest {
        answered()
        client().savePlaylistFolder("pfCCCCCCCCCCCCCCCCCCCC", "Night", null)
        val top = params()
        assertEquals(listOf("pfCCCCCCCCCCCCCCCCCCCC"), top["id"])
        assertEquals(listOf("Night"), top["name"])
        assertNull(top["parentId"])
        answered()
        client().savePlaylistFolder("pfCCCCCCCCCCCCCCCCCCCC", "Night", "pfAAAAAAAAAAAAAAAAAAAA")
        assertEquals(listOf("pfAAAAAAAAAAAAAAAAAAAA"), params()["parentId"])
    }

    @Test
    fun aPlaceNamesEachPlaylistAndNoFolderForTheTop() = runTest {
        answered()
        client().setPlaylistFolder(listOf("pl1", "pl2"), null)
        val sent = params()
        assertEquals(listOf("pl1", "pl2"), sent["playlistId"])
        assertNull(sent["folderId"])
        answered()
        client().deletePlaylistFolder("pfAAAAAAAAAAAAAAAAAAAA")
        assertEquals(listOf("pfAAAAAAAAAAAAAAAAAAAA"), params()["id"])
    }

    @Test
    fun aDeletedFolderIsNotFound() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"failed","error":{"code":70,"message":"There is no such folder."}}}""").build())
        val failed = runCatching { client().savePlaylistFolder("pfAAAAAAAAAAAAAAAAAAAA", "Road trips", null) }.exceptionOrNull()
        assertTrue(failed is SubsonicException.NotFound)
    }

    @Test
    fun anAnswerWithoutFoldersIsAnError() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""").build())
        assertTrue(runCatching { client().playlistFolders() }.exceptionOrNull() is SubsonicException.Server)
    }
}

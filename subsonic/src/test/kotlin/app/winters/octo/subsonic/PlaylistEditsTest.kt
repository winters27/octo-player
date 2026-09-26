package app.winters.octo.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

// Making, changing and deleting playlists on the server.
class PlaylistEditsTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client() = SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient())

    private fun ok(extra: String = "") =
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1"$extra}}""").build())

    private val created = ""","playlist":{"id":"pl9","name":"Drive","songCount":2,"owner":"winters","changed":"2026-09-26T10:00:00Z","entry":[]}"""

    // The params a request carried, in order, from its address or its form body.
    private fun params(request: RecordedRequest): List<Pair<String, String>> {
        val text = if (request.method == "POST") request.body?.utf8().orEmpty() else request.url.encodedQuery.orEmpty()
        return text.split('&').filter { it.isNotEmpty() }.map {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
    }

    private fun List<Pair<String, String>>.all(name: String) = filter { it.first == name }.map { it.second }

    private fun ids(n: Int) = (1..n).map { "s$it" }

    @Test
    fun aFormBodyCarriesEveryPlayInOneCall() {
        val calls = replaceSongsCalls("pl1", ids(400), formPost = true)
        assertEquals(1, calls.size)
        assertEquals("createPlaylist", calls[0].endpoint)
        assertEquals("pl1", calls[0].params.first { it.first == "playlistId" }.second)
        assertEquals(ids(400), calls[0].params.all("songId"))
    }

    @Test
    fun withoutAFormBodyTheListIsReplacedThenAddedTo() {
        val calls = replaceSongsCalls("pl1", ids(400), formPost = false, perCall = 150)
        assertEquals(listOf("createPlaylist", "updatePlaylist", "updatePlaylist"), calls.map { it.endpoint })
        assertEquals(ids(150), calls[0].params.all("songId"))
        assertEquals(ids(400).subList(150, 300), calls[1].params.all("songIdToAdd"))
        assertEquals(ids(400).subList(300, 400), calls[2].params.all("songIdToAdd"))
        assertTrue(calls.all { call -> call.params.contains("playlistId" to "pl1") })
        // Every song once, in order.
        assertEquals(ids(400), calls.flatMap { it.params.all("songId") + it.params.all("songIdToAdd") })
    }

    @Test
    fun anEmptyListStillClearsThePlaylist() {
        val calls = replaceSongsCalls("pl1", emptyList(), formPost = false)
        assertEquals(listOf(PlaylistCall("createPlaylist", listOf("playlistId" to "pl1"))), calls)
    }

    @Test
    fun anUpdateSendsOnlyWhatIsGiven() {
        val calls = updateCalls("pl1", name = "Road", comment = null, public = null, songIdsToAdd = emptyList(), songIndexesToRemove = listOf(3, 3, 0), formPost = false)
        assertEquals(1, calls.size)
        assertEquals(listOf("playlistId" to "pl1", "name" to "Road", "songIndexToRemove" to "3", "songIndexToRemove" to "3", "songIndexToRemove" to "0"), calls[0].params)
    }

    @Test
    fun createPostsAFormWhenTheServerTakesOne() = runTest {
        ok(created)
        val playlist = client().createPlaylist("Drive", listOf("a", "b"), formPost = true)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.url.encodedPath.endsWith("/rest/createPlaylist"))
        // The songs are in the body, not the address.
        assertNull(request.url.queryParameter("songId"))
        val sent = params(request)
        assertEquals("Drive", sent.all("name").single())
        assertEquals(listOf("a", "b"), sent.all("songId"))
        assertEquals("pl9", playlist?.id)
        assertEquals("2026-09-26T10:00:00Z", playlist?.changed)
        assertEquals("winters", playlist?.owner)
    }

    @Test
    fun createInBatchesAddsTheRestToTheNewPlaylist() = runTest {
        ok(created)
        ok()
        ok()
        client().createPlaylist("Drive", ids(PLAYLIST_SONGS_PER_CALL * 2 + 1))

        val first = server.takeRequest()
        assertEquals("GET", first.method)
        assertEquals("Drive", first.url.queryParameter("name"))
        assertEquals(PLAYLIST_SONGS_PER_CALL, first.url.queryParameterValues("songId").size)
        val second = server.takeRequest()
        assertTrue(second.url.encodedPath.endsWith("/rest/updatePlaylist"))
        assertEquals("pl9", second.url.queryParameter("playlistId"))
        assertEquals(PLAYLIST_SONGS_PER_CALL, second.url.queryParameterValues("songIdToAdd").size)
        val third = server.takeRequest()
        assertEquals(listOf("s${PLAYLIST_SONGS_PER_CALL * 2 + 1}"), third.url.queryParameterValues("songIdToAdd"))
    }

    @Test
    fun anOlderServerSendsNothingBack() = runTest {
        ok()
        assertNull(client().createPlaylist("Drive", listOf("a")))
    }

    @Test
    fun replacingSendsThePlaylistIdAndEverySongInOrder() = runTest {
        ok()
        client().replacePlaylistSongs("pl1", listOf("c", "a", "c"))

        val url = server.takeRequest().url
        assertTrue(url.encodedPath.endsWith("/rest/createPlaylist"))
        assertEquals("pl1", url.queryParameter("playlistId"))
        assertNull(url.queryParameter("name"))
        assertEquals(listOf("c", "a", "c"), url.queryParameterValues("songId"))
    }

    @Test
    fun updateRenamesRemovesAndAdds() = runTest {
        ok()
        client().updatePlaylist("pl1", name = "Night drive", songIdsToAdd = listOf("x"), songIndexesToRemove = listOf(2, 0))

        val url = server.takeRequest().url
        assertTrue(url.encodedPath.endsWith("/rest/updatePlaylist"))
        assertEquals("pl1", url.queryParameter("playlistId"))
        assertEquals("Night drive", url.queryParameter("name"))
        assertEquals(listOf("2", "0"), url.queryParameterValues("songIndexToRemove"))
        assertEquals(listOf("x"), url.queryParameterValues("songIdToAdd"))
        assertNull(url.queryParameter("public"))
    }

    @Test
    fun deleteSendsTheId() = runTest {
        ok()
        client().deletePlaylist("pl1")
        val url = server.takeRequest().url
        assertTrue(url.encodedPath.endsWith("/rest/deletePlaylist"))
        assertEquals("pl1", url.queryParameter("id"))
    }

    @Test
    fun aRefusalIsAnError() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"subsonic-response":{"status":"failed","error":{"code":70,"message":"Playlist not found"}}}""")
                .build(),
        )
        try {
            client().deletePlaylist("gone")
            fail("expected an error")
        } catch (e: SubsonicException.NotFound) {
            // As expected.
        }
    }

    @Test
    fun playlistsSayWhichAreReadOnly() = runTest {
        ok(
            ""","playlists":{"playlist":[
                {"id":"pl1","name":"Mine","owner":"winters","songCount":3,"changed":"2026-09-20T08:00:00Z"},
                {"id":"or1","name":"Your Mix","owner":"winters","songCount":40,"readonly":true,"validUntil":"2026-09-27T00:00:00Z"}
            ]}""",
        )
        val lists = client().playlists()
        assertFalse(lists[0].readonly)
        assertTrue(lists[1].readonly)
        assertEquals("2026-09-27T00:00:00Z", lists[1].validUntil)
        assertEquals("2026-09-20T08:00:00Z", lists[0].changed)
    }
}

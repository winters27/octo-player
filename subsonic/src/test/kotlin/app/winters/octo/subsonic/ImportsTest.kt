package app.winters.octo.subsonic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Octo's Spotify import as the apps call it: the overview, one list, the
// actions, and the loopback page that catches Spotify's answer on the device.
class ImportsTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client() = SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient())

    private fun answer(body: String) = server.enqueue(MockResponse.Builder().body(body).build())

    private fun ok(inner: String) = """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","openSubsonic":true,$inner}}"""

    @Test
    fun readsTheOverview() = runBlocking {
        answer(
            ok(
                """"imports":{"spotify":{"configured":true,"connected":true,"account":"Brandon","redirectUri":"http://127.0.0.1/callback",
                "octoFinishes":false,"endsUtc":"2027-04-01T00:00:00Z"},"reading":{"busy":true,"step":"Reading your liked songs"},
                "lists":[{"id":"spotify-liked","name":"Liked Songs","source":"spotifyLiked","total":10,"have":6,"missing":2,"queued":1,
                "downloading":1,"keepPlaylist":false,"getMissing":true,"canRefresh":true,"newField":1}],
                "trickle":{"state":"waitingForSoulseek","perHour":20,"queued":1,"current":{"key":"spotify:a","title":"Angel","artist":"Massive Attack",
                "state":"downloading","progress":0.4}},"libraryProblem":null}""",
            ),
        )
        val overview = client().imports()
        assertEquals("/rest/getImports", server.takeRequest().url.encodedPath)
        assertTrue(overview.spotify.connected)
        assertEquals("Reading your liked songs", overview.reading.step)
        val liked = overview.lists.single()
        assertEquals(ImportSource.SpotifyLiked, liked.origin)
        assertEquals(0.6f, liked.fraction, 0.001f)
        assertEquals(2, liked.coming)
        assertEquals(TrickleState.WaitingForSoulseek, overview.trickle.stage)
        assertEquals(ImportTrackState.Downloading, overview.trickle.current?.stage)
    }

    @Test
    fun readsOneListsSongs() = runBlocking {
        answer(ok(""""import":{"list":{"id":"file-1","name":"Gym","source":"file"},"tracks":[
            {"key":"song:a","title":"A","artist":"B","state":"notFound","detail":"No source had it"},
            {"key":"song:c","title":"C","artist":"D","state":"have","libraryId":"nd-1"},
            {"key":"song:e","title":"E","artist":"F","state":"brandNew"}]}"""))
        val detail = client().importList("file-1")
        assertEquals("file-1", server.takeRequest().url.queryParameter("id"))
        assertEquals(listOf(ImportTrackState.NotFound, ImportTrackState.Have, ImportTrackState.Unknown), detail.tracks.map { it.stage })
        assertTrue(detail.tracks[0].stage.askable)
        assertFalse(detail.tracks[1].stage.askable)
    }

    @Test
    fun anActionSendsItsParametersAndEverySongKey() = runBlocking {
        answer(ok(""""importAction":{"ok":true,"message":"Queued 2 songs.","count":2}"""))
        val result = client().importAction(ImportActions.SONGS, mapOf("id" to "file-1"), listOf("song:a", "spotify:b"))
        val request = server.takeRequest()
        assertEquals("/rest/importAction", request.url.encodedPath)
        assertEquals("songs", request.url.queryParameter("action"))
        assertEquals(listOf("song:a", "spotify:b"), request.url.queryParameterValues("key"))
        assertTrue(result.ok)
        assertEquals(2, result.count)
    }

    @Test
    fun aRefusedActionStillReadsAsAnAnswer() = runBlocking {
        answer(ok(""""importAction":{"ok":false,"message":"Add your Spotify app's Client ID first."}"""))
        val result = client().importAction(ImportActions.CONNECT, mapOf("redirect" to "http://127.0.0.1:5000/callback"))
        assertFalse(result.ok)
        assertNull(result.url)
    }

    // ---- The loopback page -------------------------------------------------------------------

    @Test
    fun onlyAPortlessLoopbackRedirectCanBeCaughtOnTheDevice() {
        LoopbackCallback.open("http://127.0.0.1/callback")!!.use { catcher ->
            assertTrue(catcher.redirectUri.matches(Regex("""http://127\.0\.0\.1:\d+/callback""")))
        }
        assertNull(LoopbackCallback.open("http://127.0.0.1:8888/callback"))
        assertNull(LoopbackCallback.open("https://octo.example.com/imports/spotify/callback"))
        assertNull(LoopbackCallback.open("http://localhost/callback"))
        assertNull(LoopbackCallback.open("not a uri"))
    }

    @Test
    fun theBrowsersReturnIsCaught_AndAnyOtherAddressIsNot() = runBlocking {
        LoopbackCallback.open("http://127.0.0.1/callback")!!.use { catcher ->
            val waiting = async(Dispatchers.IO) { catcher.await(10_000) }
            val http = OkHttpClient()
            val elsewhere = withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url("${catcher.redirectUri.removeSuffix("/callback")}/favicon.ico").build()).execute().use { it.code }
            }
            assertEquals(404, elsewhere)
            val page = withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url("${catcher.redirectUri}?code=AQB%2Fx&state=s1").build()).execute().use { it.body.string() }
            }
            assertTrue(page.contains("go back to Octo"))
            val answer = waiting.await()
            assertNotNull(answer)
            assertEquals(LoopbackAnswer("AQB/x", "s1", null), answer)
        }
    }

    @Test
    fun aRefusalIsCaughtToo() {
        LoopbackCallback.open("http://127.0.0.1/callback")!!.use { catcher ->
            assertEquals(LoopbackAnswer(null, "s1", "access_denied"), catcher.parse("/callback?error=access_denied&state=s1"))
            assertNull(catcher.parse("/callback"))
            assertNull(catcher.parse("/other?code=x"))
        }
    }
}

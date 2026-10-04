package app.winters.octo.ui.imports

import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.ImportListSummary
import app.winters.octo.subsonic.ImportTrackState
import app.winters.octo.subsonic.SpotifyStatus
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.TrickleStatus
import java.time.ZoneOffset
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// What both apps say about an import, and the Spotify sign-in they share.
class SpotifyImportTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun ok(inner: String) = """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","openSubsonic":true,$inner}}"""

    private fun answer(body: String) = server.enqueue(MockResponse.Builder().body(body).build())

    private fun client() = SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient())

    @Test
    fun aListSaysWhereItIsFromAndWhatTheLibraryHas() {
        val list = ImportListSummary(source = "spotifyPlaylist", by = "Spotify", total = 10, have = 6, missing = 2, queued = 1, downloading = 1, notFound = 1)
        assertEquals("Spotify · by Spotify", list.originLine())
        assertEquals("6 of 10 · 2 missing · 2 coming · 1 not found", list.countsLine())
        assertEquals("File", ImportListSummary(source = "file").originLine())
        assertEquals("Fetched", ImportTrackState.Done.label())
    }

    @Test
    fun theTrickleSaysWhatItWaitsFor() {
        val running = TrickleStatus(state = "running", perHour = 20, queued = 3, nextUtc = "2026-10-04T14:05:00Z", done = 2)
        assertEquals("Waiting for the next turn", running.title())
        assertEquals("Up to 20 songs an hour. Next song at 14:05. 3 waiting, 2 fetched.", running.line(ZoneOffset.UTC))
        assertEquals("Waiting for Soulseek", TrickleStatus(state = "waitingForSoulseek").title())
        assertEquals("Songs an hour is 0 on the server, so nothing starts.", TrickleStatus(state = "off").line(ZoneOffset.UTC))
        assertEquals("Nothing to fetch", TrickleStatus(state = "idle").title())
    }

    @Test
    fun theAccountLineSaysWhatToDoFirst() {
        assertTrue(SpotifyStatus().line().contains("Client ID"))
        assertEquals("Connected as Brandon", SpotifyStatus(configured = true, connected = true, account = "Brandon").title())
        assertTrue(SpotifyStatus(configured = true, connected = true, endsUtc = "2027-04-01T00:00:00Z").line(ZoneOffset.UTC).contains("2027"))
    }

    @Test
    fun theAppCatchesSpotifysAnswerOnTheDevice_AndTheServerFinishes() = runBlocking {
        answer(ok(""""imports":{"spotify":{"configured":true,"redirectUri":"http://127.0.0.1/callback"}}"""))
        answer(ok(""""importAction":{"ok":true,"message":"Opened Spotify.","url":"https://accounts.spotify.com/authorize?x=1"}"""))
        answer(ok(""""importAction":{"ok":true,"message":"Connected to Spotify as Brandon."}"""))
        val opened = ArrayList<String>()
        val result = signInToSpotify(client(), open = { url ->
            opened += url
            server.takeRequest()
            val redirect = server.takeRequest().url.queryParameter("redirect")!!
            // The browser, coming back from Spotify.
            thread {
                OkHttpClient().newCall(Request.Builder().url("$redirect?code=the-code&state=s1").build()).execute().close()
            }
        }, timeoutMs = 10_000)
        assertEquals(listOf("https://accounts.spotify.com/authorize?x=1"), opened)
        assertTrue(result.ok)
        assertEquals("Connected to Spotify as Brandon.", result.message)
        val finish = server.takeRequest()
        assertEquals("finish", finish.url.queryParameter("action"))
        assertEquals("the-code", finish.url.queryParameter("code"))
        assertEquals("s1", finish.url.queryParameter("state"))
    }

    @Test
    fun aServerWithoutAClientIdIsNotAskedToConnect() = runBlocking {
        answer(ok(""""imports":{"spotify":{"configured":false,"redirectUri":"http://127.0.0.1/callback"}}"""))
        val result = signInToSpotify(client(), open = { error("nothing to open") })
        assertFalse(result.ok)
        assertTrue(result.message.contains("Client ID"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aRedirectWithAPortCannotBeCaughtHere() = runBlocking {
        answer(ok(""""imports":{"spotify":{"configured":true,"redirectUri":"http://127.0.0.1:8888/callback"}}"""))
        val result = signInToSpotify(client(), open = { error("nothing to open") })
        assertFalse(result.ok)
        assertTrue(result.message.contains("http://127.0.0.1/callback"))
    }

    @Test
    fun aServerOnHttpsFinishesItself() = runBlocking {
        answer(ok(""""imports":{"spotify":{"configured":true,"octoFinishes":true,"redirectUri":"https://octo.example.com/imports/spotify/callback"}}"""))
        answer(ok(""""importAction":{"ok":true,"message":"Opened Spotify.","url":"https://accounts.spotify.com/authorize?y=2"}"""))
        val opened = ArrayList<String>()
        val result = signInToSpotify(client(), open = { opened += it })
        assertTrue(result.ok)
        assertEquals(listOf("https://accounts.spotify.com/authorize?y=2"), opened)
        server.takeRequest()
        assertEquals(null, server.takeRequest().url.queryParameter("redirect"))
    }
}

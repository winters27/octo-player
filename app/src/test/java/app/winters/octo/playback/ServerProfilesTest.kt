package app.winters.octo.playback

import app.winters.octo.data.Session
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.OCTO_TRANSITIONS
import app.winters.octo.subsonic.SubsonicClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The phone asks the server in use for the transition profiles of the songs
// it plays from it, keeps them, and plans with them in place of scouting.
class ServerProfilesTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun session(extensions: Set<String>, isOcto: Boolean = true) =
        Session(SubsonicClient(server.url("/"), Credentials("winters", "pw"), OkHttpClient()), "octo", null, isOcto, adminReachable = false, extensions = extensions)

    private fun answer(inner: String) = server.enqueue(
        MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","openSubsonic":true,$inner}}""").build(),
    )

    private val ready = """"transitionProfile":{"id":"nd-1","ready":true,"version":1,"durationMs":200000,"bodyDb":-14.0,
        "tempo":{"bpm":120.0,"trusted":true,"beatMs":500.0,"firstBeatMs":0.0,"downbeatMs":0.0,"confidence":2.0,"consistency":0.9,"steady":true},
        "head":{"startMs":0,"hopMs":100,"levels":"qqqq","bodyDb":-14.0,"gateDb":-59.0,"soundStartMs":0,"soundEndMs":30000,"boundariesMs":[]},
        "tail":{"startMs":140000,"hopMs":100,"levels":"qqqq","bodyDb":-14.0,"gateDb":-59.0,"soundStartMs":140000,"soundEndMs":199000,"outroStartMs":190000,"boundariesMs":[]}}"""

    private fun song(id: String, source: String?) = streamUri(StreamRef(source, id, "audio/flac", 900))

    private fun ask(profiles: ServerProfiles, uri: String): TransitionProfile? = runBlocking {
        val answer = CompletableDeferred<TransitionProfile?>()
        assertTrue(profiles.request(uri) { answer.complete(it) })
        withTimeout(10_000) { answer.await() }
    }

    @Test
    fun aSongFromTheServerInUseIsNamedByItsIdThere() {
        assertEquals("nd-1", serverSongOf(song("nd-1", "home"), "home"))
        assertEquals("nd-1", serverSongOf(song("nd-1", null), "home"))
        assertNull(serverSongOf(song("nd-1", "other"), "home"))
        assertNull(serverSongOf("file:///sdcard/Music/a.flac", "home"))
        assertNull(serverSongOf(null, "home"))
    }

    @Test
    fun aProfileIsAskedForOnceAndKept() {
        val signedIn = session(setOf("$OCTO_TRANSITIONS:1"))
        val profiles = ServerProfiles(CoroutineScope(Dispatchers.IO), Dispatchers.IO) { signedIn }
        answer(ready)
        val uri = song("nd-1", signedIn.sourceId)

        val profile = ask(profiles, uri)!!
        assertEquals(190_000L, profile.tail.features.outroStartMs)
        assertEquals(120.0, profile.tempoPrior!!, 0.0)
        assertSame(profile, profiles.cached(uri))
        val request = server.takeRequest()
        assertEquals("/rest/getTransitionProfile", request.url.encodedPath)
        assertEquals("nd-1", request.url.queryParameter("id"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aServerThatDoesNotSayAskedOnceAndThenLeftAlone() {
        val signedIn = session(emptySet())
        val profiles = ServerProfiles(CoroutineScope(Dispatchers.IO), Dispatchers.IO) { signedIn }
        answer(""""openSubsonicExtensions":[{"name":"octoAcquisitions","versions":[1,2]}]""")
        assertNull(ask(profiles, song("nd-1", signedIn.sourceId)))
        assertEquals("/rest/getOpenSubsonicExtensions", server.takeRequest().url.encodedPath)
        // It said no: nothing more is asked of it.
        assertFalse(profiles.request(song("nd-2", signedIn.sourceId)) {})
        assertEquals(1, server.requestCount)
    }

    @Test
    fun nothingIsAskedOfAServerThatIsNotOcto() {
        val signedIn = session(setOf("$OCTO_TRANSITIONS:1"), isOcto = false)
        val profiles = ServerProfiles(CoroutineScope(Dispatchers.IO), Dispatchers.IO) { signedIn }
        assertFalse(profiles.request(song("nd-1", signedIn.sourceId)) {})
        assertNull(profiles.cached(song("nd-1", signedIn.sourceId)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun withAProfileThePlannerHearsNoLevelAndTheWholeSongsTempo() {
        val signedIn = session(setOf("$OCTO_TRANSITIONS:1"))
        val profiles = ServerProfiles(CoroutineScope(Dispatchers.IO), Dispatchers.IO) { signedIn }
        answer(ready)
        val profile = ask(profiles, song("nd-1", signedIn.sourceId))
        assertEquals(null to 120.0, heardFor(profile, -12.0, 98.0))
        assertEquals(-12.0 to 98.0, heardFor(null, -12.0, 98.0))
    }
}

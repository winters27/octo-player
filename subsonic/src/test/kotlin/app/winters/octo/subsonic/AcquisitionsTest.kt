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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// How Octo says its downloads are going, and servers that cannot say.
class AcquisitionsTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client() = SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient())

    private fun answer(name: String) {
        val body = requireNotNull(javaClass.getResource("/fixtures/$name.json")).readText(Charsets.UTF_8)
        server.enqueue(MockResponse.Builder().body(body).build())
    }

    @Test
    fun readsOneDownloadOnItsWay() = runTest {
        answer("getAcquisitions")
        val list = client().acquisitions()

        val request = server.takeRequest()
        assertEquals("/rest/getAcquisitions", request.url.encodedPath)
        assertEquals("json", request.url.queryParameter("f"))
        assertEquals("winters", request.url.queryParameter("u"))

        val one = list.single()
        assertEquals("dz-3135556", one.id)
        assertEquals("Daft Punk", one.artist)
        assertEquals("Harder, Better, Faster, Stronger", one.title)
        assertEquals("Discovery", one.album)
        assertEquals(AcquisitionStage.Downloading, one.stage)
        assertEquals(0.42f, one.progress!!, 0.0001f)
        assertEquals(0.42f, one.fraction!!, 0.0001f)
        assertEquals(12345L, one.bytesDone)
        assertEquals(29_000_000L, one.bytesTotal)
        assertEquals("Soulseek", one.source)
        assertEquals("2026-09-26T18:00:00Z", one.startedAt)
        assertNull(one.error)
        assertNull(one.libraryId)
    }

    @Test
    fun toleratesMissingFieldsAndStagesItDoesNotKnow() = runTest {
        answer("getAcquisitionsMixed")
        val byId = client().acquisitions().associateBy { it.id }
        assertEquals(8, byId.size)

        assertEquals(AcquisitionStage.Queued, byId.getValue("a1").stage)
        // Case does not matter, and no progress is unknown progress.
        assertEquals(AcquisitionStage.Searching, byId.getValue("a2").stage)
        assertEquals("", byId.getValue("a2").artist)
        assertNull(byId.getValue("a2").fraction)
        assertEquals(AcquisitionStage.Importing, byId.getValue("a3").stage)
        assertEquals(AcquisitionStage.Done, byId.getValue("a4").stage)
        assertEquals("nd-77", byId.getValue("a4").libraryId)
        assertEquals(AcquisitionStage.Failed, byId.getValue("a5").stage)
        assertEquals("No source had this song", byId.getValue("a5").error)
        // A stage from a newer server, beside a field this app does not know.
        assertEquals(AcquisitionStage.Unknown, byId.getValue("a6").stage)
        // Progress worked out from the bytes when the server gives only those.
        assertEquals(0.5f, byId.getValue("a7").fraction!!, 0.0001f)
        // And kept between 0 and 1 whatever is sent.
        assertEquals(1f, byId.getValue("a8").fraction!!, 0f)
    }

    @Test
    fun anEmptyAnswerIsNoDownloads() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""").build())
        assertTrue(client().acquisitions().isEmpty())
    }

    @Test
    fun octoListsTheExtension() = runTest {
        answer("getOpenSubsonicExtensionsOcto")
        assertTrue(client().supports(OCTO_ACQUISITIONS))
        assertEquals("/rest/getOpenSubsonicExtensions", server.takeRequest().url.encodedPath)
    }

    @Test
    fun onlyTheVersionListedCounts() = runTest {
        answer("getOpenSubsonicExtensionsOcto")
        assertFalse(client().supports(OCTO_ACQUISITIONS, version = 2))
    }

    @Test
    fun plainNavidromeDoesNotListIt() = runTest {
        answer("getOpenSubsonicExtensions")
        assertFalse(client().supports(OCTO_ACQUISITIONS))
    }

    @Test
    fun aServerWithoutExtensionsListsNone() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("Not found").build())
        assertFalse(client().supports(OCTO_ACQUISITIONS))
    }

    @Test
    fun onlyTheServersOwnAnswerIsAYesOrNo() = runTest {
        answer("getOpenSubsonicExtensionsOcto")
        assertEquals(true, client().supportsIfKnown(OCTO_ACQUISITIONS))
        answer("getOpenSubsonicExtensions")
        assertEquals(false, client().supportsIfKnown(OCTO_ACQUISITIONS))
        // No such address, or a Subsonic error: the server has none to list.
        server.enqueue(MockResponse.Builder().code(404).body("Not found").build())
        assertEquals(false, client().supportsIfKnown(OCTO_ACQUISITIONS))
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":0,"message":"Unknown method"}}}""").build())
        assertEquals(false, client().supportsIfKnown(OCTO_ACQUISITIONS))

        // A proxy while the server restarts, or a page that is not the
        // server's: not known yet, and never a no.
        server.enqueue(MockResponse.Builder().code(502).body("Bad gateway").build())
        assertNull(client().supportsIfKnown(OCTO_ACQUISITIONS))
        server.enqueue(MockResponse.Builder().body("<html>Sign in to the Wi-Fi</html>").build())
        assertNull(client().supportsIfKnown(OCTO_ACQUISITIONS))
        server.enqueue(MockResponse.Builder().code(503).body("").build())
        assertFalse("Still a plain no where only a yes counts", client().supports(OCTO_ACQUISITIONS))
    }

    @Test
    fun aServerOutOfReachIsNotKnown() = runTest {
        val gone = client()
        server.close()
        assertNull(gone.supportsIfKnown(OCTO_ACQUISITIONS))
        assertFalse(gone.supports(OCTO_ACQUISITIONS))
    }

    @Test
    fun anOlderServerWithoutTheEndpointSaysSo() = runTest {
        // Navidrome answers an unknown endpoint with a 404; a Subsonic
        // server may answer with an error instead. Both are errors here.
        server.enqueue(MockResponse.Builder().code(404).body("404 page not found").build())
        try {
            client().acquisitions()
            fail("a missing endpoint must not look like no downloads")
        } catch (e: SubsonicException.NotSubsonic) {
            // expected
        }
        answer("error70")
        try {
            client().acquisitions()
            fail("an error answer must not look like no downloads")
        } catch (e: SubsonicException.NotFound) {
            // expected
        }
    }

    @Test
    fun readsOneSong() = runTest {
        answer("getSong")
        val song = client().song("nd-77")
        val request = server.takeRequest()
        assertEquals("/rest/getSong", request.url.encodedPath)
        assertEquals("nd-77", request.url.queryParameter("id"))
        assertEquals("Roadgame", song.title)
        assertEquals("al-9", song.albumId)
        assertEquals(229, song.duration)
    }
}

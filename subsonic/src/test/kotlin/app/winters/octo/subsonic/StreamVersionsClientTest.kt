package app.winters.octo.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// The stream versions calls against answers in the exact form an Octo
// server sends them.
class StreamVersionsClientTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client() = SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient())

    private fun answer(body: String) = server.enqueue(MockResponse.Builder().body(body).build())

    private fun error(code: Int, message: String) =
        answer("""{"subsonic-response":{"status":"failed","version":"1.16.1","type":"octo","error":{"code":$code,"message":"$message"}}}""")

    private val picked = StreamVersions(
        id = "X",
        playsFrom = PLAYS_FROM_STREAM,
        playing = StreamVersionPlaying(
            videoId = "v1",
            title = "Song",
            channel = "Artist - Topic",
            duration = 215,
            kind = VERSION_KIND_RELEASE,
            picked = PICKED_PERSON,
            pickedBy = "ede",
            mine = false,
            pickedAt = "2026-11-14T10:00:00Z",
        ),
        heardVideoId = "v0",
        busy = false,
        version = listOf(StreamVersion("v2", "Song (Live)", "Artist", 240, VERSION_KIND_VIDEO)),
        missed = listOf(MISSED_YOUTUBE_MUSIC),
        ids = listOf("X", "Y"),
    )

    @Test
    fun aListingAsksByTheSongsIdAndReadsEveryField() = runTest {
        answer(PICKED)
        assertEquals(picked, client().streamVersions("X"))
        val url = server.takeRequest().url
        assertEquals("/rest/getStreamVersions", url.encodedPath)
        assertEquals("X", url.queryParameter("id"))
        assertEquals("json", url.queryParameter("f"))
    }

    @Test
    fun aSongPlayedFromAFileOrDownloadedOnPlayReadsWhatItLeavesOut() = runTest {
        answer(FILE)
        val file = client().streamVersions("X")
        assertEquals(PLAYS_FROM_FILE, file.playsFrom)
        assertEquals(StreamVersionPlaying(videoId = "v9", picked = PICKED_OCTO), file.playing)
        assertNull(file.heardVideoId)
        assertTrue(file.busy)
        assertTrue(file.version.isEmpty() && file.missed.isEmpty())
        assertEquals(listOf("X"), file.ids)
        answer(DOWNLOAD)
        val download = client().streamVersions("X")
        assertEquals(PLAYS_FROM_DOWNLOAD, download.playsFrom)
        assertNull(download.playing)
        assertEquals(listOf("X"), download.ids)
    }

    @Test
    fun aPickSendsItsVideoAndGoingBackSendsAuto() = runTest {
        answer(PICKED)
        assertEquals(picked, client().setStreamVersion("X", "v2"))
        val pick = server.takeRequest().url
        assertEquals("/rest/setStreamVersion", pick.encodedPath)
        assertEquals("X", pick.queryParameter("id"))
        assertEquals("v2", pick.queryParameter("video"))
        answer(PICKED)
        client().setStreamVersion("X", STREAM_VERSION_AUTO)
        assertEquals("auto", server.takeRequest().url.queryParameter("video"))
    }

    @Test
    fun aRefusalIsTheServersErrorInItsOwnWords() = runTest {
        val notOffered = "That version is not offered for this song any more. Open the list again."
        error(70, notOffered)
        try {
            client().setStreamVersion("X", "v9")
            fail("a refusal must not look like a pick")
        } catch (e: SubsonicException.NotFound) {
            assertEquals(notOffered, e.message)
        }
        val beingAdded = "This song is being added to your library right now. You can choose its version once that finishes."
        error(0, beingAdded)
        try {
            client().setStreamVersion("X", "v2")
            fail("a refusal must not look like a pick")
        } catch (e: SubsonicException.Server) {
            assertEquals(0, e.code)
            assertEquals(beingAdded, e.message)
        }
        error(50, "The version chosen for a song plays for everyone on this server, so this account cannot choose one. Ask a family manager.")
        try {
            client().setStreamVersion("X", "v2")
            fail("a refusal must not look like a pick")
        } catch (e: SubsonicException.Server) {
            assertEquals(50, e.code)
        }
    }

    @Test
    fun anAnswerWithoutItsVersionsIsAnError() = runTest {
        answer("""{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo"}}""")
        try {
            client().streamVersions("X")
            fail("an answer without versions must not read as none")
        } catch (e: SubsonicException.Server) {
            assertEquals("Missing \"streamVersions\" in the answer", e.message)
        }
    }

    @Test
    fun theChangeFeedSendsTheCursorAndReadsEveryChange() = runTest {
        answer(CHANGES)
        assertEquals(
            StreamVersionChanges(
                "e1",
                7,
                true,
                listOf(StreamVersionChange(6, listOf("X", "Y")), StreamVersionChange(7, listOf("Z"))),
            ),
            client().streamVersionChanges("e1", 5),
        )
        val url = server.takeRequest().url
        assertEquals("/rest/getStreamVersionChanges", url.encodedPath)
        assertEquals("e1", url.queryParameter("epoch"))
        assertEquals("5", url.queryParameter("since"))
        answer(CHANGES_INCOMPLETE)
        assertEquals(StreamVersionChanges("e2", 0, false), client().streamVersionChanges(null, null))
        val first = server.takeRequest().url
        assertNull(first.queryParameter("epoch"))
        assertNull(first.queryParameter("since"))
    }

    @Test
    fun aPlayTokenIsTheEntryAndItsRestartCount() {
        assertEquals("e7.0", playToken("e7", 0))
        assertEquals("q-12.3", playToken("q-12", 3))
        assertEquals("octoPlay", PLAY_TOKEN_PARAM)
        assertEquals("X-Octo-Video", VIDEO_HEADER)
    }

    @Test
    fun scoutReadsAreMarkedScout() {
        assertEquals("scout", OctoPurpose.Scout.wire)
    }

    private companion object {
        const val PICKED =
            """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","streamVersions":{"id":"X","playsFrom":"stream","playing":{"videoId":"v1","title":"Song","channel":"Artist - Topic","duration":215,"kind":"release","picked":"person","pickedBy":"ede","mine":false,"pickedAt":"2026-11-14T10:00:00Z"},"heardVideoId":"v0","busy":false,"version":[{"videoId":"v2","title":"Song (Live)","channel":"Artist","duration":240,"kind":"video"}],"missed":["youtubeMusic"],"ids":["X","Y"]}}}"""
        const val FILE =
            """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","streamVersions":{"id":"X","playsFrom":"file","playing":{"videoId":"v9","title":null,"channel":null,"duration":null,"kind":null,"picked":"octo","pickedBy":null,"mine":false,"pickedAt":null},"busy":true,"version":[],"missed":[],"ids":["X"]}}}"""
        const val DOWNLOAD =
            """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","streamVersions":{"id":"X","playsFrom":"download","busy":false,"version":[],"missed":[],"ids":["X"]}}}"""
        const val CHANGES =
            """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","streamVersionChanges":{"epoch":"e1","seq":7,"complete":true,"change":[{"seq":6,"ids":["X","Y"]},{"seq":7,"ids":["Z"]}]}}}"""
        const val CHANGES_INCOMPLETE =
            """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","streamVersionChanges":{"epoch":"e2","seq":0,"complete":false,"change":[]}}}"""
    }
}

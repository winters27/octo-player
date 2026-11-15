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
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

// What a server listing octoPlaylistSongs is sent and answers: a replace's
// stamp and own-ids marks on its first call only, the ids it skipped, and
// its refusal of a stale replace.
class PlaylistSongsAnswersTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client() = SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient())

    private fun ok(extra: String = "") =
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1"$extra}}""").build())

    private fun params(request: RecordedRequest): List<Pair<String, String>> {
        val text = if (request.method == "POST") request.body?.utf8().orEmpty() else request.url.encodedQuery.orEmpty()
        return text.split('&').filter { it.isNotEmpty() }.map {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
    }

    private fun List<Pair<String, String>>.one(name: String) = firstOrNull { it.first == name }?.second

    @Test
    fun onlyTheFirstCallOfASplitReplaceCarriesTheMarks() {
        val calls = replaceSongsCalls("pl1", (1..400).map { "s$it" }, formPost = false, perCall = 150, ifStamp = "2026-11-14T10:00:00.123Z", ownIds = true)
        assertEquals("2026-11-14T10:00:00.123Z", calls[0].params.one(IF_STAMP_PARAM))
        assertEquals("1", calls[0].params.one(OWN_IDS_PARAM))
        assertTrue(calls.drop(1).all { call -> call.params.none { it.first == IF_STAMP_PARAM || it.first == OWN_IDS_PARAM } })
        val plain = replaceSongsCalls("pl1", listOf("s1"), formPost = true)
        assertNull(plain[0].params.one(IF_STAMP_PARAM))
        assertNull(plain[0].params.one(OWN_IDS_PARAM))
    }

    @Test
    fun aPagedReplaceSendsTheMarksAndHearsWhatWasSkipped() = runTest {
        ok(""","octoSkipped":["ext-deezer-song-9"]""")
        val skipped = ArrayList<String>()
        client().replacePlaylistSongs("pl1", PagedValues.of(listOf("s1", "ext-deezer-song-9")), formPost = true, ifStamp = "2026-11-14T10:00:00.123Z", ownIds = true) {
            skipped += it
        }
        val sent = params(server.takeRequest())
        assertEquals("2026-11-14T10:00:00.123Z", sent.one(IF_STAMP_PARAM))
        assertEquals("1", sent.one(OWN_IDS_PARAM))
        assertEquals(listOf("ext-deezer-song-9"), skipped)
    }

    @Test
    fun aListReplaceAndAnUpdateHearWhatWasSkipped() = runTest {
        ok(""","octoSkipped":["x1"]""")
        val skipped = ArrayList<String>()
        client().replacePlaylistSongs("pl1", listOf("s1", "x1"), skipped = { skipped += it })
        ok(""","octoSkipped":["x2","x3"]""")
        client().updatePlaylist("pl1", songIdsToAdd = listOf("x2", "x3"), skipped = { skipped += it })
        ok()
        client().updatePlaylist("pl1", songIdsToAdd = PagedValues.of(listOf("s4")), skipped = { skipped += it })
        assertEquals(listOf("x1", "x2", "x3"), skipped)
    }

    @Test
    fun aNewPlaylistHearsWhatWasSkipped() = runTest {
        ok(""","playlist":{"id":"pl9","name":"Drive","songCount":1,"entry":[]},"octoSkipped":["x1"]""")
        val skipped = ArrayList<String>()
        val made = client().createPlaylist("Drive", PagedValues.of(listOf("s1", "x1")), skipped = { skipped += it })
        assertEquals("pl9", made?.id)
        assertEquals(listOf("x1"), skipped)
    }

    @Test
    fun aStaleReplaceIsAConflictAndAnyOtherRefusalIsNot() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"subsonic-response":{"status":"failed","error":{"code":0,"message":"The playlist changed. Read it again.","octoConflict":true}}}""")
                .build(),
        )
        val stale = runCatching { client().replacePlaylistSongs("pl1", PagedValues.of(listOf("s1")), ifStamp = "old") }.exceptionOrNull()
        assertTrue(stale is SubsonicException.Server && stale.conflict)
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"failed","error":{"code":50,"message":"Only the playlist's owner can change it."}}}""").build())
        val refused = runCatching { client().replacePlaylistSongs("pl1", listOf("s1")) }.exceptionOrNull()
        assertTrue(refused is SubsonicException.Server)
        assertFalse((refused as SubsonicException.Server).conflict)
    }

    @Test
    fun theUpkeepAndImportMarksAreRead() = runTest {
        ok(""","playlists":{"playlist":[{"id":"a","name":"Review","octoUpkeep":true,"octoUpkeepKind":"notice"},{"id":"b","name":"Imported","readonly":true,"octoImport":true},{"id":"c","name":"Mine"}]}""")
        val rows = client().playlists()
        assertEquals(listOf(true, false, false), rows.map { it.octoUpkeep })
        assertEquals(listOf("notice", null, null), rows.map { it.octoUpkeepKind })
        assertEquals(listOf(false, true, false), rows.map { it.octoImport })
    }
}

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
import org.junit.Before
import org.junit.Test

// A download's log, clearing finished ones, and Find songs, as an Octo
// server says them.
class DownloadLogTest {
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
    fun readsOneDownloadWithItsLog() = runTest {
        answer("getAcquisition")
        val one = client().acquisition("soulseek:3kX9Qm")

        val request = server.takeRequest()
        assertEquals("/rest/getAcquisition", request.url.encodedPath)
        assertEquals("soulseek:3kX9Qm", request.url.queryParameter("key"))

        assertEquals("soulseek:3kX9Qm", one.key)
        assertEquals(AcquisitionKind.DOWNLOAD, one.kind)
        assertEquals("FLAC 16-bit 44.1 kHz", one.quality)
        assertEquals("peer1", one.peer)
        assertEquals(4, one.logLines)
        assertEquals(listOf(LogKind.Queued, LogKind.Search, LogKind.Found, LogKind.Unknown), one.event.map { it.logKind })
        assertEquals("By winters", one.event[0].detail)
        val copy = one.event[2].candidate.single()
        assertEquals("peer1", copy.peer)
        assertEquals(1, copy.rank)
        assertEquals(true, copy.freeSlot)
        assertEquals(31_000_000L, copy.size)
    }

    @Test
    fun readsAFindSongsSearch() = runTest {
        answer("findSongs")
        val found = client().findSongs("nd-7")

        assertEquals("/rest/findSongs", server.takeRequest().url.encodedPath)
        assertFalse(found.searching)
        assertEquals("Sexy Boy", found.song.title)
        assertEquals("MP3 220 kbps", found.song.quality)
        assertEquals(listOf(FIND_DONE, FIND_OFF), found.source.map { it.state })
        assertEquals(listOf(0, 1), found.candidate.map { it.index })
        assertNull(found.candidate[1].rank)
        assertEquals("Another version: a remix, an edit or a live take", found.candidate[1].note)
    }

    @Test
    fun pickingSendsTheSearchAndThePlace() = runTest {
        answer("pickFoundSong")
        val result = client().pickFoundSong("a1b2c3", 3)

        val request = server.takeRequest()
        assertEquals("/rest/pickFoundSong", request.url.encodedPath)
        assertEquals("a1b2c3", request.url.queryParameter("search"))
        assertEquals("3", request.url.queryParameter("candidate"))
        assertTrue(result.queued)
        assertEquals("soulseek:3kX9Qm", result.key)
    }

    @Test
    fun clearingAnswersHowManyLeft() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1","cleared":{"count":3}}}""").build())
        assertEquals(3, client().clearAcquisitions())
        assertNull(server.takeRequest().url.queryParameter("key"))

        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1","cleared":{"count":1}}}""").build())
        assertEquals(1, client().clearAcquisitions("soulseek:x"))
        assertEquals("soulseek:x", server.takeRequest().url.queryParameter("key"))
    }
}

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

class LyricsClientTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client() = SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient())

    private fun answer(name: String) = server.enqueue(
        MockResponse.Builder()
            .body(requireNotNull(javaClass.getResource("/fixtures/$name.json")).readText(Charsets.UTF_8))
            .build(),
    )

    @Test
    fun enhancedLyricsAskForVersionTwoAndParse() = runTest {
        answer("getLyricsBySongId")
        val all = client().lyricsBySongId("song-1", enhanced = true)
        val url = server.takeRequest().url
        assertEquals("/rest/getLyricsBySongId", url.encodedPath)
        assertEquals("song-1", url.queryParameter("id"))
        assertEquals("true", url.queryParameter("enhanced"))

        assertEquals(3, all.size)
        val main = all.first()
        assertEquals("main", main.kind)
        assertTrue(main.synced)
        assertEquals(-100.0, main.offset, 0.0)
        assertEquals(1000L, main.line.first().start)
        assertEquals("bg", main.agents.first { it.id == "backing" }.role)
        val lead = main.cueLine.first()
        assertEquals("Café naïve 日本", lead.value)
        assertEquals(13, lead.cue[2].byteStart)
        assertEquals(15, lead.cue[2].byteEnd)
        assertEquals("translation", all[1].kind)
        // A plain set has no start times.
        assertNull(all[2].line.first().start)
    }

    @Test
    fun plainRequestLeavesEnhancedOut() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","lyricsList":{}}}""").build())
        assertTrue(client().lyricsBySongId("song-1", enhanced = false).isEmpty())
        assertNull(server.takeRequest().url.queryParameter("enhanced"))
    }

    @Test
    fun olderCallSendsArtistAndTitle() = runTest {
        answer("getLyrics")
        assertEquals("Café naïve\nFin", client().lyrics("Octo Test", "Café"))
        val url = server.takeRequest().url
        assertEquals("/rest/getLyrics", url.encodedPath)
        assertEquals("Octo Test", url.queryParameter("artist"))
        assertEquals("Café", url.queryParameter("title"))
    }

    @Test
    fun olderCallWithNothingIsNull() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","lyrics":{}}}""").build())
        assertNull(client().lyrics("Nobody", "Nothing"))
    }
}

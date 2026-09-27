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

// Octo's lyrics copies and the choice between them that holds for the
// whole server, and the word timings its lyrics carry.
class LyricsPinsTest {
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
    fun theExtensionIsFoundWhenListed() = runTest {
        answer("getOpenSubsonicExtensionsOctoLyrics")
        assertTrue(client().supports(OCTO_LYRICS))
        answer("getOpenSubsonicExtensionsOcto")
        assertFalse(client().supports(OCTO_LYRICS))
    }

    @Test
    fun candidatesAreReadWithTheChoice() = runTest {
        answer("getLyricsCandidates")
        val found = client().lyricsCandidates("ext-deezer-song-3135556")

        val url = server.takeRequest().url
        assertEquals("/rest/getLyricsCandidates", url.encodedPath)
        assertEquals("ext-deezer-song-3135556", url.queryParameter("id"))
        assertNull(url.queryParameter("title"))
        assertNull(url.queryParameter("artist"))
        // Standard sign-in goes with it.
        assertEquals("winters", url.queryParameter("u"))
        assertEquals("json", url.queryParameter("f"))

        assertEquals("ext-deezer-song-3135556", found.id)
        assertEquals("kugou:9f2a.77c1", found.choice)
        assertEquals(3, found.candidate.size)
        val first = found.candidate[0]
        assertEquals("kugou", first.source)
        assertEquals("Café", first.title)
        assertEquals("Premier", first.album)
        assertEquals(215, first.duration)
        assertEquals("word", first.kind)
        assertTrue(first.sameSong)
        assertTrue(first.chosen)
        assertEquals(listOf("Café au lait", "日本語の歌"), first.preview)
        // Nulls from the server read as unknown.
        val second = found.candidate[1]
        assertNull(second.album)
        assertNull(second.duration)
        assertFalse(second.sameSong)
        assertEquals("line", second.kind)
        assertTrue(found.candidate[2].preview.isEmpty())
    }

    @Test
    fun aSearchByHandSendsTitleAndArtist() = runTest {
        answer("getLyricsCandidates")
        client().lyricsCandidates("song-1", title = " Café ", artist = "Octo Test")
        val url = server.takeRequest().url
        assertEquals("Café", url.queryParameter("title"))
        assertEquals("Octo Test", url.queryParameter("artist"))
    }

    @Test
    fun blankSearchWordsAreLeftOut() {
        assertEquals(mapOf("id" to "1", "title" to "a"), lyricsCandidatesParams("1", "a", " "))
    }

    @Test
    fun aChoiceIsSentAndItsAnswerRead() = runTest {
        answer("setLyricsChoice")
        assertEquals("lrclib:4242", client().setLyricsChoice("ext-deezer-song-3135556", "lrclib:4242"))
        val url = server.takeRequest().url
        assertEquals("/rest/setLyricsChoice", url.encodedPath)
        assertEquals("ext-deezer-song-3135556", url.queryParameter("id"))
        assertEquals("lrclib:4242", url.queryParameter("candidate"))
    }

    @Test
    fun hidingAndAutomaticAreChoicesToo() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","lyricsChoice":{"id":"s","choice":"none"}}}""").build())
        assertEquals(LYRICS_NONE, client().setLyricsChoice("s", LYRICS_NONE))
        assertEquals(LYRICS_NONE, server.takeRequest().url.queryParameter("candidate"))
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","lyricsChoice":{"id":"s","choice":"auto"}}}""").build())
        assertEquals(LYRICS_AUTO, client().setLyricsChoice("s", LYRICS_AUTO))
    }

    @Test
    fun lookupsSwitchedOffAreAServerError() = runTest {
        answer("lyricsLookupsOff")
        try {
            client().lyricsCandidates("song-1")
            fail("expected an error")
        } catch (e: SubsonicException.Server) {
            assertEquals("Lyrics lookups are off on this server", e.message)
        }
    }

    @Test
    fun wordCuesArriveWithTheirBytes() = runTest {
        answer("getLyricsBySongIdWordCues")
        val all = client().lyricsBySongId("song-1", enhanced = true)
        assertEquals("true", server.takeRequest().url.queryParameter("enhanced"))
        val main = all.single()
        assertEquals("main", main.kind)
        assertEquals("xxx", main.lang)
        assertTrue(main.synced)
        assertEquals(0.0, main.offset, 0.0)
        assertEquals(listOf(1000L, 4000L, 7000L), main.line.map { it.start })
        assertEquals(listOf(0, 1), main.cueLine.map { it.index })
        val cafe = main.cueLine[0].cue[0]
        assertEquals("Café ", cafe.value)
        assertEquals(0, cafe.byteStart)
        assertEquals(5, cafe.byteEnd)
        assertEquals(1500L, cafe.end)
        val song = main.cueLine[1].cue[2]
        assertEquals("歌", song.value)
        assertEquals(12, song.byteStart)
        assertEquals(14, song.byteEnd)
    }
}

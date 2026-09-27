package app.winters.octo.lyrics

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
import java.io.IOException

// The copies the lyrics menu offers from the online library, always
// against a local stand-in, never the real one.
class OnlineCopiesTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun library() = OnlineLyrics(OkHttpClient(), server.url("/"))

    private fun answer(body: String, code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    private val search = """[
        {"id":1,"trackName":"Song","artistName":"Artist","albumName":"Other","duration":260.0,"syncedLyrics":"[00:01.00] Live take"},
        {"id":2,"trackName":"Song","artistName":"Artist","albumName":"Album","duration":201.0,"plainLyrics":"Plain words"},
        {"id":3,"trackName":"Song (Club Remix)","artistName":"Artist","albumName":"Remixes","duration":300.0,"syncedLyrics":"[00:01.00] Remix"},
        {"id":4,"trackName":"Song","artistName":"Artist","albumName":"Album","duration":202.0,"syncedLyrics":"[00:01.00] Same album"},
        {"id":5,"trackName":"Another Song","artistName":"Artist","duration":201.0,"syncedLyrics":"[00:01.00] Not this song"},
        {"id":6,"trackName":"Song","artistName":"Someone Else","duration":201.0,"syncedLyrics":"[00:01.00] Theirs"},
        {"id":7,"trackName":"Song","artistName":"Artist","duration":201.0}
    ]"""

    @Test
    fun theExactMatchKeepsItsNumber() = runTest {
        answer("""{"id":42,"trackName":"Song","artistName":"Artist","albumName":"Album","duration":201.0,"syncedLyrics":"[00:01.00] Hello"}""")
        val copy = library().exact("Song", "Artist", "Album", 201_000)!!
        assertEquals(42L, copy.id)
        assertEquals(42L, copy.lyrics.onlineId)
        assertEquals(201_000L, copy.durationMs)
        assertEquals("Album", copy.album)
        assertTrue(copy.lyrics.synced)
        assertEquals("/api/get", server.takeRequest().url.encodedPath)
    }

    @Test
    fun aSearchThatFailsIsNotTakenForNoLyrics() = runTest {
        answer("""{"code":404}""", code = 404)
        answer("busy", code = 503)
        val failed = runCatching { library().find("Song", "Artist", "Album", 201_000) }.exceptionOrNull()
        assertTrue(failed is IOException)
    }

    @Test
    fun plainLyricsStandWhenTheSearchFails() = runTest {
        answer("""{"id":9,"trackName":"Song","artistName":"Artist","plainLyrics":"Plain words"}""")
        answer("busy", code = 503)
        val found = library().find("Song", "Artist", "Album", 201_000)
        assertEquals("Plain words", found?.lines?.single()?.text)
    }

    @Test
    fun noExactMatchIsNoCopy() = runTest {
        answer("""{"code":404}""", code = 404)
        assertNull(library().exact("Song", "Artist", "", 0))
    }

    @Test
    fun theSongsCopiesKeepEveryVersionOfItByItsArtist() = runTest {
        answer(search)
        val copies = library().copiesOf("Song", "Artist", "Album", 201_000)
        // Other songs, other artists' songs and copies with no words are
        // left out; a remix stays, named, since the listener picks. Timed
        // copies come first, the same album first among them.
        assertEquals(listOf(4L, 1L, 3L, 2L), copies.map { it.id })
        assertEquals("Song (Club Remix)", copies[2].title)
        assertEquals(false, copies.last().lyrics.synced)
        val request = server.takeRequest().url
        assertEquals("/api/search", request.encodedPath)
        assertEquals("Song", request.queryParameter("track_name"))
        assertEquals("Artist", request.queryParameter("artist_name"))
    }

    @Test
    fun aSearchByHandTakesWhateverTheLibraryFinds() = runTest {
        answer(search)
        val copies = library().search("  song artist ")
        // Only copies with no words at all are left out.
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), copies.map { it.id })
        val request = server.takeRequest().url
        assertEquals("/api/search", request.encodedPath)
        assertEquals("song artist", request.queryParameter("q"))
        assertNull(request.queryParameter("track_name"))
    }

    @Test
    fun aPickedCopyIsFetchedAgainByItsNumber() = runTest {
        answer("""{"id":4,"trackName":"Song","artistName":"Artist","syncedLyrics":"[00:01.00] Same album"}""")
        val lyrics = library().byId(4)!!
        assertEquals("Same album", lyrics.lines.first().text)
        assertEquals(4L, lyrics.onlineId)
        assertEquals("/api/get/4", server.takeRequest().url.encodedPath)

        answer("""{"code":404}""", code = 404)
        assertNull(library().byId(5))
    }

    @Test
    fun theAutomaticLookupStillTakesOnlyTheSameRecording() = runTest {
        // Nothing timed from the exact lookup, so the search is asked: the
        // remix and the other songs never stand in.
        answer("""{"id":2,"trackName":"Song","artistName":"Artist","plainLyrics":"Plain words"}""")
        answer(search)
        val found = library().find("Song", "Artist", "Album", 201_000)!!
        assertEquals("Same album", found.lines.first().text)
        assertEquals(4L, found.onlineId)
    }
}

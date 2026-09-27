package app.winters.octo.lyrics

import app.winters.octo.admin.DownloadRecord
import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.catalog.SongQuery
import app.winters.octo.discovery.downloadMatches
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

// Which online lyrics are the song playing, and what the online library is
// asked, always against a local stand-in, never the real one.
class LyricsMatchTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun answer(body: String, code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    private fun copy(title: String, artist: String, seconds: Double = 170.0, words: String = "Found") =
        """{"id":1,"trackName":"$title","artistName":"$artist","albumName":"Album","duration":$seconds,"syncedLyrics":"[00:01.00] $words"}"""

    private fun found(body: String, title: String, artist: String, ms: Long = 169_500) =
        bestSearchMatch("[$body]", title, artist, "Album", ms)?.lines?.first()?.text

    private val dollar = "${'$'}"

    @Test
    fun stylizedNamesFindTheirLyrics() {
        assertEquals("Found", found(copy("Suicide", "Suicideboys"), "${dollar}UICIDE", "${dollar}uicideboy$dollar"))
        assertNull(found(copy("Ultimate ${dollar}uicide", "${dollar}uicideboy$dollar"), "${dollar}UICIDE", "${dollar}uicideboy$dollar"))
    }

    @Test
    fun aCurlyApostropheFindsTheStraightOne() {
        assertEquals("Found", found(copy("Huntin' Wabbitz", "${dollar}uicideboy$dollar"), "Huntin’ Wabbitz", "${dollar}uicideboy$dollar"))
    }

    @Test
    fun creditsJoinedWithACommaOfTheirOwnStillMatch() {
        assertEquals("Found", found(copy("Real Friends (Explicit)", "Kanye West、Ty Dolla ${dollar}ign"), "Real Friends", "Kanye West"))
        assertEquals("Found", found(copy("Can't Tell Me Nothing", "Ye (侃爷)"), "Can't Tell Me Nothing", "Kanye West"))
    }

    @Test
    fun aKnownNameIsNotItsFirstWord() {
        assertEquals("Found", found(copy("EARFQUAKE", "Tyler, The Creator"), "EARFQUAKE", "Tyler The Creator"))
        assertNull(found(copy("EARFQUAKE", "Tyler"), "EARFQUAKE", "Tyler, The Creator"))
    }

    @Test
    fun aCleanEditCarriesTheSongsLyrics() {
        // The same words at the same times, a few bleeped: good lyrics for
        // either, both ways round.
        assertEquals("Found", found(copy("Movie Star (Clean)", "Jack Harlow"), "Movie Star", "Jack Harlow"))
        assertEquals("Found", found(copy("Movie Star", "Jack Harlow"), "Movie Star (Clean)", "Jack Harlow"))
        assertTrue(sameLyricsSong("Movie Star", "Jack Harlow", "Movie Star (Clean)", "Jack Harlow、Pharrell Williams"))
        // Other versions still never stand in.
        assertNull(found(copy("Movie Star (Remix)", "Jack Harlow"), "Movie Star", "Jack Harlow"))
        assertNull(found(copy("Movie Star (Live)", "Jack Harlow"), "Movie Star (Clean)", "Jack Harlow"))
    }

    @Test
    fun aCleanEditIsStillAnotherDownload() {
        val find = OnlineSongEntity("find:e1", "s", "e1", "Movie Star", "Jack Harlow", "", null, null, 0, null, null, null, 0)
        assertFalse(downloadMatches(find, DownloadRecord(artist = "Jack Harlow", title = "Movie Star (Clean)")))
        assertTrue(downloadMatches(find, DownloadRecord(artist = "Jack Harlow", title = "Movie Star (Explicit)")))
    }

    @Test
    fun searchesKeepTheArtistAndStopAtThree() {
        assertEquals(
            listOf(SongQuery("${dollar}UICIDE", "${dollar}uicideboy$dollar"), SongQuery("SUICIDE", "suicideboys")),
            lyricsSearches("${dollar}UICIDE", "${dollar}uicideboy$dollar"),
        )
        assertEquals(
            listOf("Drake feat. Rihanna Too Good (feat. Rihanna)", "Drake feat. Rihanna Too Good", "Drake Too Good"),
            lyricsSearches("Too Good (feat. Rihanna)", "Drake feat. Rihanna").map { it.text },
        )
        assertEquals(listOf(SongQuery("Song", "Artist")), lyricsSearches("Song", "Artist"))
    }

    @Test
    fun theLookupTriesTheNextSearchWhenOneFindsNothing() = runTest {
        answer("""{"code":404}""", code = 404)
        answer("[]")
        answer("[${copy("Suicide", "Suicideboys")}]")
        val lyrics = OnlineLyrics(OkHttpClient(), server.url("/")).find("${dollar}UICIDE", "${dollar}uicideboy$dollar", "", 170_000)
        assertEquals("Found", lyrics?.lines?.first()?.text)
        assertEquals("/api/get", server.takeRequest().url.encodedPath)
        val first = server.takeRequest().url
        assertEquals("${dollar}uicideboy$dollar", first.queryParameter("artist_name"))
        val second = server.takeRequest().url
        assertEquals("SUICIDE", second.queryParameter("track_name"))
        assertEquals("suicideboys", second.queryParameter("artist_name"))
    }

    @Test
    fun theCopiesToChooseFromComeFromTheFirstSearchThatHasAny() = runTest {
        answer("[]")
        answer("[${copy("Suicide (Live)", "Suicideboys")}]")
        val copies = OnlineLyrics(OkHttpClient(), server.url("/")).copiesOf("${dollar}UICIDE", "${dollar}uicideboy$dollar", "", 0)
        // A live take stays in the list, since the listener picks.
        assertEquals(listOf("Suicide (Live)"), copies.map { it.title })
        assertEquals(2, server.requestCount)
    }
}

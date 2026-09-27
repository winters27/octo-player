package app.winters.octo.listening

import app.winters.octo.catalog.CopyCount
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// A favourite album or artist made on the phone, as it reaches a server:
// always a local stand-in, never a real one.
class FavouriteSendTest {
    private val server = MockWebServer()
    private val source = "server:music.example"

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client() = SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient())

    private fun ok() = server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""").build())

    private fun broken() = server.enqueue(MockResponse.Builder().code(500).build())

    private fun row(native: String) = "$source:$native"

    // One try at sending, as FavouriteSync makes it: true when it went through.
    private suspend fun send(kind: FavouriteKind, star: Boolean, ids: List<String>): Boolean =
        try {
            favouriteCall(client(), kind, star)(ids)
            true
        } catch (e: SubsonicException) {
            false
        }

    // A library that is only the server's: every album and artist keeps the
    // server's own id, with nothing from the phone merged in.
    private val albums = serverCopiesOf(listOf(CopyCount(row("al1"), row("al1"), 10)), source)
    private val artists = serverCopiesOf(listOf(CopyCount(row("ar1"), row("ar1"), 10), CopyCount(row("ar1"), row("ar1"), 0)), source)

    @Test
    fun aFavouriteAlbumInAServerOnlyLibraryStarsItsAlbumId() = runTest {
        ok()
        val ids = idsToSend(albums, row("al1"), liked = true, likedNow = true)
        assertEquals(listOf("al1"), ids)
        assertTrue(send(FavouriteKind.Album, star = true, ids))
        val url = server.takeRequest().url
        assertEquals("/rest/star", url.encodedPath)
        assertEquals(listOf("al1"), url.queryParameterValues("albumId"))
        assertTrue(url.queryParameterValues("id").isEmpty())
        assertTrue(url.queryParameterValues("artistId").isEmpty())
    }

    @Test
    fun aFavouriteArtistInAServerOnlyLibraryStarsItsArtistId() = runTest {
        ok()
        val ids = idsToSend(artists, row("ar1"), liked = true, likedNow = true)
        assertEquals(listOf("ar1"), ids)
        assertTrue(send(FavouriteKind.Artist, star = true, ids))
        val url = server.takeRequest().url
        assertEquals("/rest/star", url.encodedPath)
        assertEquals(listOf("ar1"), url.queryParameterValues("artistId"))
        assertTrue(url.queryParameterValues("id").isEmpty())
        assertTrue(url.queryParameterValues("albumId").isEmpty())
    }

    @Test
    fun removingAFavouriteUnstarsIt() = runTest {
        ok()
        assertTrue(send(FavouriteKind.Album, star = false, idsToSend(albums, row("al1"), liked = false, likedNow = false)))
        val url = server.takeRequest().url
        assertEquals("/rest/unstar", url.encodedPath)
        assertEquals(listOf("al1"), url.queryParameterValues("albumId"))
    }

    @Test
    fun aFailedStarIsSentAgain() = runTest {
        broken()
        broken()
        ok()
        val ids = idsToSend(albums, row("al1"), liked = true, likedNow = true)
        assertTrue(retried(FAVOURITE_RETRY_WAITS) { send(FavouriteKind.Album, star = true, ids) })
        assertEquals(3, server.requestCount)
        repeat(3) {
            val url = server.takeRequest().url
            assertEquals("/rest/star", url.encodedPath)
            assertEquals(listOf("al1"), url.queryParameterValues("albumId"))
        }
    }

    @Test
    fun afterTheLastTryItIsLeftToTheNextSync() = runTest {
        repeat(FAVOURITE_RETRY_WAITS.size + 1) { broken() }
        assertFalse(retried(FAVOURITE_RETRY_WAITS) { send(FavouriteKind.Album, star = true, listOf("al1")) })
        assertEquals(FAVOURITE_RETRY_WAITS.size + 1, server.requestCount)
    }

    @Test
    fun aChangeUndoneBeforeItsTurnSendsNothing() {
        // Hearted, then un-hearted before a retry: the newer change owns it.
        assertTrue(idsToSend(albums, row("al1"), liked = true, likedNow = false).isEmpty())
        assertTrue(idsToSend(albums, row("al1"), liked = false, likedNow = true).isEmpty())
    }

    @Test
    fun anAlbumOnlyOnThePhoneSendsNothing() {
        assertTrue(idsToSend(albums, "device:album:3", liked = true, likedNow = true).isEmpty())
    }
}

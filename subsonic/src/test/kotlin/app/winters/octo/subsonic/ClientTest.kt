package app.winters.octo.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ClientTest {
    private val server = MockWebServer()
    private val password = "correct horse battery staple"

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client(base: String = "/") =
        SubsonicClient(server.url(base), Credentials("winters", password), OkHttpClient())

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name.json")).readText(Charsets.UTF_8)

    private fun answer(name: String) = server.enqueue(MockResponse.Builder().body(fixture(name)).build())

    private inline fun <reified E : SubsonicException> assertThrowsAsync(block: () -> Unit) {
        try {
            block()
            fail("Expected ${E::class.simpleName}")
        } catch (e: SubsonicException) {
            assertTrue("Got ${e::class.simpleName}", e is E)
        }
    }

    @Test
    fun everyCallIsSignedAndNeverSendsThePassword() = runTest {
        answer("ping")
        client().ping()
        val url = server.takeRequest().url
        listOf("u", "t", "s", "v", "c", "f").forEach { assertTrue(it, url.queryParameter(it) != null) }
        assertEquals(32, url.queryParameter("t")!!.length)
        assertNull(url.queryParameter("p"))
        assertFalse(url.toString().contains("horse"))
        assertEquals("json", url.queryParameter("f"))
    }

    @Test
    fun saltChangesPerCall() = runTest {
        answer("ping")
        answer("ping")
        client().ping()
        client().ping()
        val first = server.takeRequest().url
        val second = server.takeRequest().url
        assertNotEquals(first.queryParameter("s"), second.queryParameter("s"))
        assertNotEquals(first.queryParameter("t"), second.queryParameter("t"))
    }

    @Test
    fun parsesEachFixture() = runTest {
        val c = client()

        answer("ping")
        val info = c.ping()
        assertEquals("navidrome", info.type)
        assertTrue(info.openSubsonic)

        answer("getOpenSubsonicExtensions")
        assertEquals(6, c.extensions().size)

        answer("getUser")
        assertTrue(c.user("winters").adminRole)

        answer("getAlbumList2")
        val albums = c.albumList(AlbumListType.NEWEST, 2)
        assertEquals(2, albums.size)
        assertEquals("27olR01OlsFec4YYFzCF6M", albums.first().id)

        answer("getAlbum")
        val album = c.album("7wsVynyqPtHmsFWz6wUDCj")
        assertEquals("Nightcall", album.name)
        assertEquals("flac", album.song.first().suffix)

        answer("getArtist")
        assertEquals(1, c.artist("1mNJ8hAlbt4jJc2dFaf38y").album.size)

        answer("getArtists")
        val index = c.artists()
        assertEquals(2, index.size)
        assertTrue(index.all { it.artist.size == 2 })

        answer("getStarred2")
        assertEquals(2, c.starred().song.size)
    }

    @Test
    fun error40IsWrongCredentials() = runTest {
        answer("error40")
        assertThrowsAsync<SubsonicException.WrongCredentials> { client().ping() }
    }

    @Test
    fun error70IsNotFound() = runTest {
        answer("error70")
        assertThrowsAsync<SubsonicException.NotFound> { client().album("missing") }
    }

    @Test
    fun htmlIsNotSubsonic() = runTest {
        server.enqueue(MockResponse.Builder().body("<html><body>Welcome</body></html>").build())
        assertThrowsAsync<SubsonicException.NotSubsonic> { client().ping() }
    }

    @Test
    fun http404IsNotSubsonic() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("Not found").build())
        assertThrowsAsync<SubsonicException.NotSubsonic> { client().ping() }
    }

    @Test
    fun deadServerIsUnreachable() = runTest {
        val c = client()
        server.close()
        assertThrowsAsync<SubsonicException.Unreachable> { c.ping() }
    }

    @Test
    fun searchSendsCounts() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","searchResult3":{}}}""").build())
        val result = client().search("portishead")
        assertTrue(result.album.isEmpty())
        val url = server.takeRequest().url
        assertEquals("portishead", url.queryParameter("query"))
        listOf("artistCount", "albumCount", "songCount").forEach { assertTrue(it, url.queryParameter(it) != null) }
    }

    @Test
    fun subPathBaseKeepsItsPath() = runTest {
        answer("ping")
        client("/music/").ping()
        assertEquals("/music/rest/ping", server.takeRequest().url.encodedPath)
    }
}

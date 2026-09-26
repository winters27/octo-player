package app.winters.octo.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The ways of signing in, the extra headers and the library folders.
class ConnectionTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun ok(payload: String = "") = server.enqueue(
        MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1"$payload}}""").build(),
    )

    @Test
    fun legacyPasswordIsHexEncoded() {
        assertEquals("enc:736573616d65", legacyPassword("sesame"))
        // UTF-8 bytes, so a non-ASCII password survives.
        assertEquals("enc:c3a9", legacyPassword("é"))
    }

    @Test
    fun legacyModeSendsPlainPasswordAndNoToken() = runTest {
        ok()
        SubsonicClient(server.url("/"), Credentials("u", "sesame", AuthMode.LegacyPassword), OkHttpClient()).ping()
        val url = server.takeRequest().url
        assertEquals("u", url.queryParameter("u"))
        assertEquals("enc:736573616d65", url.queryParameter("p"))
        assertNull(url.queryParameter("t"))
        assertNull(url.queryParameter("s"))
    }

    @Test
    fun apiKeyReplacesUsernameAndToken() = runTest {
        ok()
        SubsonicClient(server.url("/"), Credentials("", "key123", AuthMode.ApiKey), OkHttpClient()).ping()
        val url = server.takeRequest().url
        assertEquals("key123", url.queryParameter("apiKey"))
        listOf("u", "t", "s", "p").forEach { assertNull(it, url.queryParameter(it)) }
        assertEquals("json", url.queryParameter("f"))
    }

    @Test
    fun tokenInfoNamesTheKeysOwner() = runTest {
        ok(""","tokenInfo":{"username":"winters"}""")
        val client = SubsonicClient(server.url("/"), Credentials("", "key123", AuthMode.ApiKey), OkHttpClient())
        assertEquals("winters", client.tokenInfo())
    }

    @Test
    fun aBadApiKeyIsWrongCredentials() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"subsonic-response":{"status":"failed","error":{"code":44,"message":"Invalid API key"}}}""")
                .build(),
        )
        val client = SubsonicClient(server.url("/"), Credentials("", "nope", AuthMode.ApiKey), OkHttpClient())
        val error = runCatching { client.ping() }.exceptionOrNull()
        assertTrue(error is SubsonicException.WrongCredentials)
    }

    @Test
    fun anUnsupportedWayOfSigningInSaysSo() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"subsonic-response":{"status":"failed","error":{"code":41,"message":"Token auth not supported"}}}""")
                .build(),
        )
        val error = runCatching { SubsonicClient(server.url("/"), Credentials("u", "p"), OkHttpClient()).ping() }.exceptionOrNull()
        assertTrue(error is SubsonicException.AuthNotSupported)
    }

    @Test
    fun extraHeadersGoOnEveryCall() = runTest {
        ok()
        ok()
        val headers = mapOf("CF-Access-Client-Id" to "id.access", "X-Proxy-Token" to "abc")
        val client = SubsonicClient(server.url("/"), Credentials("u", "p"), OkHttpClient(), headers = headers)
        client.ping()
        client.star(listOf("1"))
        repeat(2) {
            val request = server.takeRequest()
            assertEquals("id.access", request.headers["CF-Access-Client-Id"])
            assertEquals("abc", request.headers["X-Proxy-Token"])
        }
    }

    @Test
    fun headersAlsoGoWithSignedAddressesFetchedDirectly() {
        // Covers and streams are fetched from a signed address by other
        // code; the same interceptor on that client adds the headers.
        server.enqueue(MockResponse.Builder().body("image").build())
        val base = server.url("/")
        val scope = HeaderScope(setOf(origin(base)), mapOf("X-Proxy-Token" to "abc"))
        val http = OkHttpClient.Builder().addNetworkInterceptor(ServerHeaders { scope }).build()
        val client = SubsonicClient(base, Credentials("u", "p"), http)
        http.newCall(Request.Builder().url(client.coverArtUrl("al-1", 300)).build()).execute().close()
        assertEquals("abc", server.takeRequest().headers["X-Proxy-Token"])
    }

    @Test
    fun headersNeverGoToAnotherHost() {
        val scope = HeaderScope(setOf(origin("https://music.example.com/".toHttpUrl())), mapOf("X" to "secret"))
        assertTrue(scope.covers("https://music.example.com/rest/ping".toHttpUrl()))
        assertTrue(scope.covers("https://MUSIC.example.com:443/x".toHttpUrl()))
        assertFalse(scope.covers("https://other.example.com/".toHttpUrl()))
        assertFalse(scope.covers("http://music.example.com/".toHttpUrl()))
        assertFalse(scope.covers("https://music.example.com:8443/".toHttpUrl()))
        assertFalse("secret" in scope.toString())
    }

    @Test
    fun noHeadersWhenNoneAreSet() = runTest {
        ok()
        SubsonicClient(server.url("/"), Credentials("u", "p"), OkHttpClient()).ping()
        assertNull(server.takeRequest().headers["X-Proxy-Token"])
    }

    @Test
    fun readsMusicFoldersWithNumberOrTextIds() = runTest {
        ok(""","musicFolders":{"musicFolder":[{"id":1,"name":"Music"},{"id":"lib-2","name":"Audiobooks"}]}""")
        val folders = SubsonicClient(server.url("/"), Credentials("u", "p"), OkHttpClient()).musicFolders()
        assertEquals(listOf(MusicFolder("1", "Music"), MusicFolder("lib-2", "Audiobooks")), folders)
        assertEquals("/rest/getMusicFolders", server.takeRequest().url.encodedPath)
    }

    @Test
    fun theChosenFolderGoesWithEveryCallThatTakesOne() = runTest {
        repeat(4) { ok() }
        val client = SubsonicClient(server.url("/"), Credentials("u", "p"), OkHttpClient(), musicFolderId = "2")
        client.albumList(AlbumListType.NEWEST, 10)
        client.artists()
        client.search("abc")
        client.songPage(500, 0)
        repeat(4) { assertEquals("2", server.takeRequest().url.queryParameter("musicFolderId")) }
    }

    @Test
    fun aFolderCanBeGivenPerCallAndIsLeftOutForAll() = runTest {
        ok()
        ok()
        val client = SubsonicClient(server.url("/"), Credentials("u", "p"), OkHttpClient())
        client.albumList(AlbumListType.NEWEST, 10, musicFolderId = "7")
        client.albumList(AlbumListType.NEWEST, 10)
        assertEquals("7", server.takeRequest().url.queryParameter("musicFolderId"))
        assertNull(server.takeRequest().url.queryParameter("musicFolderId"))
    }

    @Test
    fun callsFollowTheActiveAddressWhileThePrimaryStays() = runTest {
        ok()
        val primary = "https://music.example.com/".toHttpUrl()
        val home = server.url("/")
        val client = SubsonicClient(primary, Credentials("u", "p"), OkHttpClient(), route = { home })
        client.ping()
        assertEquals(home, client.baseUrl)
        assertEquals(primary, client.primaryUrl)
        assertEquals("/rest/ping", server.takeRequest().url.encodedPath)
    }
}

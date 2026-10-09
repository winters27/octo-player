package app.winters.octo.subsonic

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

// The device's name and id, and what a request is for, go to the app's
// own server and nowhere else.
class ServerHeadersTest {
    private val own = MockWebServer()
    private val other = MockWebServer()

    @Before
    fun start() {
        own.start()
        other.start()
    }

    @After
    fun stop() {
        own.close()
        other.close()
    }

    private val device = DeviceIdentity("6f1c2a9e-1111-4222-8333-944455556666", "Pixel 9")

    private fun http(scope: HeaderScope?) = OkHttpClient.Builder().addNetworkInterceptor(ServerHeaders { scope }).build()

    private fun fetch(client: OkHttpClient, server: MockWebServer, purpose: OctoPurpose? = null): mockwebserver3.RecordedRequest {
        server.enqueue(MockResponse.Builder().body("ok").build())
        val request = Request.Builder().url(server.url("/rest/stream")).apply { purpose?.let { purpose(it) } }.build()
        client.newCall(request).execute().close()
        return server.takeRequest()
    }

    @Test
    fun deviceHeadersOnlyToOwnServer() {
        val client = http(HeaderScope(setOf(origin(own.url("/"))), emptyMap(), device))

        val mine = fetch(client, own)
        assertEquals("6f1c2a9e-1111-4222-8333-944455556666", mine.headers[DEVICE_ID_HEADER])
        assertEquals("Pixel%209", mine.headers[DEVICE_NAME_HEADER])
        // Playing is never marked offline.
        assertNull(mine.headers[PURPOSE_HEADER])

        val elsewhere = fetch(client, other, OctoPurpose.Offline)
        assertNull(elsewhere.headers[DEVICE_ID_HEADER])
        assertNull(elsewhere.headers[DEVICE_NAME_HEADER])
        assertNull(elsewhere.headers[PURPOSE_HEADER])
    }

    @Test
    fun anOfflineCopySaysSoToItsOwnServer() {
        val client = http(HeaderScope(setOf(origin(own.url("/"))), emptyMap(), device))
        assertEquals("offline", fetch(client, own, OctoPurpose.Offline).headers[PURPOSE_HEADER])
    }

    @Test
    fun aClientMarkedForOfflineMarksEveryRequestItMakes() {
        val shared = http(HeaderScope(setOf(origin(own.url("/"))), emptyMap(), device))
        val offline = shared.markedFor(OctoPurpose.Offline)
        assertEquals("offline", fetch(offline, own).headers[PURPOSE_HEADER])
        assertEquals("Pixel%209", fetch(offline, own).headers[DEVICE_NAME_HEADER])
        assertNull(fetch(offline, other).headers[PURPOSE_HEADER])
        // The shared client itself is left as it was.
        assertNull(fetch(shared, own).headers[PURPOSE_HEADER])
    }

    @Test
    fun aRedirectToAnotherHostCarriesNothing() {
        val client = http(HeaderScope(setOf(origin(own.url("/"))), mapOf("X-Proxy-Token" to "abc"), device))
        own.enqueue(MockResponse.Builder().code(302).addHeader("Location", other.url("/file").toString()).build())
        other.enqueue(MockResponse.Builder().body("bytes").build())
        val request = Request.Builder().url(own.url("/rest/download")).purpose(OctoPurpose.Offline).build()
        client.newCall(request).execute().close()

        val first = own.takeRequest()
        assertEquals("offline", first.headers[PURPOSE_HEADER])
        assertEquals("Pixel%209", first.headers[DEVICE_NAME_HEADER])
        val second = other.takeRequest()
        assertNull(second.headers[PURPOSE_HEADER])
        assertNull(second.headers[DEVICE_ID_HEADER])
        assertNull(second.headers[DEVICE_NAME_HEADER])
        assertNull(second.headers["X-Proxy-Token"])
    }

    @Test
    fun nothingIsAddedWhenSignedOut() {
        val request = fetch(http(null), own, OctoPurpose.Offline)
        assertNull(request.headers[DEVICE_ID_HEADER])
        assertNull(request.headers[PURPOSE_HEADER])
    }

    @Test
    fun anyNameGoesPercentEncodedAsUtf8() {
        val typed = "Sam\u2019s Caf\u00e9 phone \uD83D\uDCF1"
        val named = DeviceIdentity("id", typed)
        // Kept as typed.
        assertEquals(typed, named.name)
        val client = http(HeaderScope(setOf(origin(own.url("/"))), emptyMap(), named))
        val sent = fetch(client, own).headers[DEVICE_NAME_HEADER]!!
        assertEquals("Sam%E2%80%99s%20Caf%C3%A9%20phone%20%F0%9F%93%B1", sent)
        assertEquals(typed, java.net.URLDecoder.decode(sent, Charsets.UTF_8))
        // At most 64 characters, never cutting one in two.
        assertEquals(64, DeviceIdentity("id", "x".repeat(200)).name.length)
        val emoji = DeviceIdentity("id", "\uD83D\uDCF1".repeat(70)).name
        assertEquals(64, emoji.codePointCount(0, emoji.length))
        // Nothing usable falls back to a plain name.
        assertEquals("Octo device", DeviceIdentity("id", "  ").name)
    }

    @Test
    fun theScopeNeverPrintsItsValues() {
        val scope = HeaderScope(setOf("https://music.example.com:443"), mapOf("X" to "secret"), device)
        assertFalse("secret" in scope.toString())
        assertFalse("Pixel" in scope.toString())
    }
}

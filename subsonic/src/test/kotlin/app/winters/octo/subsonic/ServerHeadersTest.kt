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
        assertEquals("Pixel 9", mine.headers[DEVICE_NAME_HEADER])
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
    fun aRedirectToAnotherHostCarriesNothing() {
        val client = http(HeaderScope(setOf(origin(own.url("/"))), mapOf("X-Proxy-Token" to "abc"), device))
        own.enqueue(MockResponse.Builder().code(302).addHeader("Location", other.url("/file").toString()).build())
        other.enqueue(MockResponse.Builder().body("bytes").build())
        val request = Request.Builder().url(own.url("/rest/download")).purpose(OctoPurpose.Offline).build()
        client.newCall(request).execute().close()

        val first = own.takeRequest()
        assertEquals("offline", first.headers[PURPOSE_HEADER])
        assertEquals("Pixel 9", first.headers[DEVICE_NAME_HEADER])
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
    fun aNameWithAccentsOrCurlyQuotesStillGoes() {
        val named = DeviceIdentity("id", "Brandon’s Café phone 📱")
        assertEquals("Brandon's Cafe phone", named.name)
        val client = http(HeaderScope(setOf(origin(own.url("/"))), emptyMap(), named))
        assertEquals("Brandon's Cafe phone", fetch(client, own).headers[DEVICE_NAME_HEADER])
        // Nothing usable falls back to a plain name.
        assertEquals("Octo device", DeviceIdentity("id", "📱").name)
        assertEquals(64, DeviceIdentity("id", "x".repeat(200)).name.length)
    }

    @Test
    fun theScopeNeverPrintsItsValues() {
        val scope = HeaderScope(setOf("https://music.example.com:443"), mapOf("X" to "secret"), device)
        assertFalse("secret" in scope.toString())
        assertFalse("Pixel" in scope.toString())
    }
}

package app.winters.octo.subsonic.gateway

import app.winters.octo.subsonic.origin
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// A web page where a song, cover or file was asked of the server is
// refused; media and other addresses pass untouched.
class RefuseHtmlTest {
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

    private fun client() = OkHttpClient.Builder().addInterceptor(RefuseHtml { setOf(origin(own.url("/"))) }).build()

    @Test
    fun aServersSongThatComesBackAsAPageIsRefused() {
        own.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/outpost.goauthentik.io/start?rd=x").build())
        own.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/html; charset=utf-8").body(AUTHENTIK_PAGE).build())
        val error = runCatching { client().newCall(Request.Builder().url(own.url("/rest/stream.view?id=tr-1")).build()).execute().close() }.exceptionOrNull()
        assertTrue("got $error", error is SignInPageAnswer)
        assertEquals(Gateway.Authentik, (error as SignInPageAnswer).gateway)
        assertEquals(200, error.status)
    }

    @Test
    fun audioAndOtherAddressesPassUntouched() {
        own.enqueue(MockResponse.Builder().addHeader("Content-Type", "audio/flac").body("fLaC").build())
        other.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/html").body("<html>station</html>").build())
        val http = client()
        http.newCall(Request.Builder().url(own.url("/rest/stream.view?id=tr-1")).build()).execute().use { assertEquals("fLaC", it.body.string()) }
        http.newCall(Request.Builder().url(other.url("/listen")).build()).execute().use { assertEquals(200, it.code) }
    }

    @Test
    fun aRedirectToAudioElsewhereStillPlays() {
        own.enqueue(MockResponse.Builder().code(302).addHeader("Location", other.url("/file.flac").toString()).build())
        other.enqueue(MockResponse.Builder().addHeader("Content-Type", "audio/flac").body("fLaC").build())
        client().newCall(Request.Builder().url(own.url("/rest/stream.view?id=tr-1")).build()).execute().use { assertEquals("fLaC", it.body.string()) }
    }

    private fun refusalOf(http: OkHttpClient): Throwable? =
        runCatching { http.newCall(Request.Builder().url(own.url("/rest/stream.view?id=tr-1")).build()).execute().close() }.exceptionOrNull()

    // A 401 or 403 a sign-in page gives at the server's address is
    // refused once, named, with its status. Red when RefuseHtml judges 2xx
    // answers only, or loses the recognizer's name.
    @Test
    fun aSignInPagesRefusalAtTheServersAddressIsRefusedAndNamed() {
        own.enqueue(MockResponse.Builder().code(401).addHeader("Content-Type", HTML).body(AUTHENTIK_PAGE).build())
        own.enqueue(MockResponse.Builder().code(403).addHeader("cf-access-aud", "aud").body("Forbidden").build())
        val http = client()
        val authentik = refusalOf(http)
        assertTrue("got $authentik", authentik is SignInPageAnswer)
        assertEquals(Gateway.Authentik, (authentik as SignInPageAnswer).gateway)
        assertEquals(401, authentik.status)
        val cloudflare = refusalOf(http)
        assertTrue("got $cloudflare", cloudflare is SignInPageAnswer)
        assertEquals(Gateway.CloudflareAccess, (cloudflare as SignInPageAnswer).gateway)
        assertEquals(403, cloudflare.status)
        assertEquals(2, own.requestCount)
    }

    // A 401 web page with no gateway marks and no sign-in sent is
    // still a sign-in page. Red when RefuseHtml refuses only a 401 the
    // recognizer names.
    @Test
    fun aRefusalAsAWebPageIsASignInPageEvenUnnamed() {
        own.enqueue(MockResponse.Builder().code(401).addHeader("Content-Type", "text/html").body("<html><body>Please sign in</body></html>").build())
        val error = refusalOf(client())
        assertTrue("got $error", error is SignInPageAnswer)
        assertEquals(Gateway.SignInPage, (error as SignInPageAnswer).gateway)
        assertEquals(401, error.status)
        assertEquals(1, own.requestCount)
    }

    // The server's own plain refusals, a missing page, and a sign-in
    // page's refusal at another address pass as they are. Red when
    // RefuseHtml refuses every 401 or 403, any HTML error, or looks past the
    // server's addresses.
    @Test
    fun theServersOwnRefusalsAndOtherAddressesPassAsTheyAre() {
        own.enqueue(MockResponse.Builder().code(401).addHeader("Content-Type", "application/json").body("""{"error":"no"}""").build())
        own.enqueue(MockResponse.Builder().code(403).addHeader("Content-Type", "text/plain").body("Forbidden").build())
        own.enqueue(MockResponse.Builder().code(404).addHeader("Content-Type", HTML).body("<html>gone</html>").build())
        other.enqueue(MockResponse.Builder().code(401).addHeader("Content-Type", HTML).body(AUTHENTIK_PAGE).build())
        val http = client()
        val stream = Request.Builder().url(own.url("/rest/stream.view?id=tr-1")).build()
        http.newCall(stream).execute().use { assertEquals(401, it.code) }
        http.newCall(stream).execute().use { assertEquals(403, it.code) }
        http.newCall(stream).execute().use { assertEquals(404, it.code) }
        http.newCall(Request.Builder().url(other.url("/listen")).build()).execute().use { assertEquals(401, it.code) }
    }

    @Test
    fun aStoredPageIsToldFromMedia() {
        assertTrue(looksLikeHtmlFile("<!DOCTYPE html>".toByteArray()))
        assertTrue(looksLikeHtmlFile(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "\r\n  <html>".toByteArray()))
        assertFalse(looksLikeHtmlFile(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertFalse(looksLikeHtmlFile("fLaC".toByteArray()))
        assertFalse(looksLikeHtmlFile(ByteArray(0)))
    }
}

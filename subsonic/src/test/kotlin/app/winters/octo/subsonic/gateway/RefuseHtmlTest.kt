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

    @Test
    fun aStoredPageIsToldFromMedia() {
        assertTrue(looksLikeHtmlFile("<!DOCTYPE html>".toByteArray()))
        assertTrue(looksLikeHtmlFile(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "\r\n  <html>".toByteArray()))
        assertFalse(looksLikeHtmlFile(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertFalse(looksLikeHtmlFile("fLaC".toByteArray()))
        assertFalse(looksLikeHtmlFile(ByteArray(0)))
    }
}

package app.winters.octo.subsonic.gateway

import app.winters.octo.subsonic.HeaderScope
import app.winters.octo.subsonic.gatewayOrigins
import app.winters.octo.subsonic.httpsTwin
import app.winters.octo.subsonic.origin
import app.winters.octo.subsonic.serverOrigins
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

// A sign-in page's headers (all three names, from any row) go to the main
// address and an https home address only; every other header goes to
// every one of the server's addresses as before.
class HeaderScopeTest {
    private val main = MockWebServer()
    private val home = MockWebServer()

    private val rows = mapOf(
        "authorization" to "Bearer t0k",
        "CF-ACCESS-CLIENT-ID" to "id.access",
        "cf-access-client-secret" to "s3cret",
        "X-Proxy-Token" to "abc",
    )

    @Before
    fun start() {
        main.start()
        home.start()
    }

    @After
    fun stop() {
        main.close()
        home.close()
    }

    @Test
    fun aSignInPagesHeadersNeverGoToAPlainHttpHomeAddress() {
        main.enqueue(MockResponse.Builder().body("ok").build())
        home.enqueue(MockResponse.Builder().body("ok").build())
        val http = securedClient(main.url("/"), rows, home = home.url("/"))
        http.newCall(Request.Builder().url(main.url("/rest/ping.view")).build()).execute().close()
        http.newCall(Request.Builder().url(home.url("/rest/ping.view")).build()).execute().close()

        val atMain = main.takeRequest()
        assertEquals("Bearer t0k", atMain.headers["Authorization"])
        assertEquals("id.access", atMain.headers["CF-Access-Client-Id"])
        assertEquals("s3cret", atMain.headers["CF-Access-Client-Secret"])
        assertEquals("abc", atMain.headers["X-Proxy-Token"])
        val atHome = home.takeRequest()
        assertNull(atHome.headers["Authorization"])
        assertNull(atHome.headers["CF-Access-Client-Id"])
        assertNull(atHome.headers["CF-Access-Client-Secret"])
        assertEquals("abc", atHome.headers["X-Proxy-Token"])
    }

    // Red when gatewayOrigins gives an http home address itself, leaves out
    // an http address's https twin, or lets a public http main address have
    // any.
    @Test
    fun theGatewayAddressesAreTheMainOneAndAnHttpsHomeOnly() {
        val https = "https://music.example.com/".toHttpUrl()
        val lan = "http://192.168.1.20:4533/".toHttpUrl()
        val secureHome = "https://home.example.com:8443/".toHttpUrl()
        assertEquals(setOf(origin(https), "https://192.168.1.20:443"), gatewayOrigins(https, lan))
        assertEquals(setOf(origin(https), origin(secureHome)), gatewayOrigins(https, secureHome))
        assertEquals(setOf(origin(lan), "https://192.168.1.20:443"), gatewayOrigins(lan, null))
        assertEquals(emptySet<String>(), gatewayOrigins("http://music.example.com/".toHttpUrl(), secureHome))
    }

    // At the scope: an http address's https twin (same host, port 443)
    // is the server too, so its headers, a sign-in page's among them, go
    // there and ServerHeaders keeps and sends its cookies there (covers);
    // another host, or the same host on another port, is not it. Red when
    // httpsTwin, serverOrigins or gatewayOrigins leave the twin out, or
    // when the twin keeps the http port.
    @Test
    fun anHttpAddressBringsTheSameHostOverHttps() {
        val main = "http://music.home.arpa:4533/".toHttpUrl()
        val lan = "http://192.168.1.20:4533/".toHttpUrl()
        val scope = HeaderScope(serverOrigins(main, lan), rows, gatewayOrigins(main, lan), GatewayCookies())
        val twin = "https://music.home.arpa/rest/ping.view".toHttpUrl()
        assertTrue(scope.covers(twin))
        assertEquals(rows, scope.headersFor(twin))
        assertEquals(rows, scope.headersFor("https://192.168.1.20/rest/stream.view".toHttpUrl()))
        assertEquals(mapOf("X-Proxy-Token" to "abc"), scope.headersFor("http://192.168.1.20:4533/rest/ping.view".toHttpUrl()))
        assertFalse(scope.covers("https://other.home.arpa/".toHttpUrl()))
        assertFalse(scope.covers("https://music.home.arpa:8443/".toHttpUrl()))
        assertEquals("https://music.home.arpa:443", httpsTwin(main))
        assertNull(httpsTwin("https://music.example.com/".toHttpUrl()))
        val public = "http://music.example.com/".toHttpUrl()
        assertEquals(setOf(origin(public), "https://music.example.com:443"), serverOrigins(public, null))
        assertEquals(emptySet<String>(), gatewayOrigins(public, null))
        assertEquals(setOf("https://music.example.com:443"), serverOrigins("https://music.example.com/".toHttpUrl(), null))
    }

    @Test
    fun headersForKeepsEveryOtherHeaderEverywhere() {
        val https = "https://music.example.com/".toHttpUrl()
        val lan = "http://192.168.1.20:4533/".toHttpUrl()
        val scope = HeaderScope(setOf(origin(https), origin(lan)), rows, gatewayOrigins(https, lan), GatewayCookies())
        assertEquals(rows, scope.headersFor("https://music.example.com/rest/ping.view".toHttpUrl()))
        assertEquals(mapOf("X-Proxy-Token" to "abc"), scope.headersFor("http://192.168.1.20:4533/rest/ping.view".toHttpUrl()))
        assertEquals(emptyMap<String, String>(), scope.headersFor("https://other.example.com/".toHttpUrl()))
        assertFalse("s3cret" in scope.toString())
        assertFalse("t0k" in scope.toString())
    }

    @Test
    fun theSubsonicClientTakesNoHeadersOfItsOwn() {
        val source = File("src/main/kotlin/app/winters/octo/subsonic/SubsonicClient.kt").readText()
        val constructor = source.substringAfter("class SubsonicClient(").substringBefore(") {")
        assertFalse(constructor, Regex("""\bheaders\s*:""").containsMatchIn(constructor))
        assertFalse("ServerHeaders" in source)
    }
}

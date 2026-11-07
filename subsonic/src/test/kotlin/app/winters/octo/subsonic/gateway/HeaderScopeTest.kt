package app.winters.octo.subsonic.gateway

import app.winters.octo.subsonic.HeaderScope
import app.winters.octo.subsonic.gatewayOrigins
import app.winters.octo.subsonic.origin
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun theGatewayAddressesAreTheMainOneAndAnHttpsHomeOnly() {
        val https = "https://music.example.com/".toHttpUrl()
        val lan = "http://192.168.1.20:4533/".toHttpUrl()
        val secureHome = "https://home.example.com:8443/".toHttpUrl()
        assertEquals(setOf(origin(https)), gatewayOrigins(https, lan))
        assertEquals(setOf(origin(https), origin(secureHome)), gatewayOrigins(https, secureHome))
        assertEquals(setOf(origin(lan)), gatewayOrigins(lan, null))
        assertEquals(emptySet<String>(), gatewayOrigins("http://music.example.com/".toHttpUrl(), secureHome))
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

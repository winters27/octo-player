package app.winters.octo.subsonic.gateway

import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.FamilyPreset
import app.winters.octo.subsonic.FamilyWeb
import app.winters.octo.subsonic.HeaderScope
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.origin
import app.winters.octo.subsonic.serverHeaders
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
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
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

// A sign-in page's session cookies: which are kept, where they go, when
// they end, and which configuration they belong to. Expiries are given as
// Expires dates, read against the jar's own clock.
class GatewayCookiesTest {
    private var now = Instant.parse("2026-11-06T18:00:00Z").toEpochMilli()
    private val jar = GatewayCookies { now }
    private val music = "https://music.example.com/rest/ping.view".toHttpUrl()
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

    private fun get(http: OkHttpClient, url: HttpUrl) = http.newCall(Request.Builder().url(url).build()).execute().close()

    private fun ok(vararg cookies: String) =
        MockResponse.Builder().apply { cookies.forEach { addHeader("Set-Cookie", it) } }.body("ok").build()

    private fun expires(day: Int): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.of(2026, 12, day, 0, 0, 0, 0, ZoneOffset.UTC))

    @Test
    fun onlyAGatewaysOwnCookiesAreKept() {
        jar.keep(
            music,
            listOf(
                "octo_admin=a1; Path=/; HttpOnly",
                "octo_family=f1; Path=/",
                "authentik_proxy_3c=p1; Path=/",
                "authelia_session=s1; Path=/",
                "CF_Authorization=c1; Path=/",
                "session=x; Path=/",
            ),
        )
        assertEquals("authentik_proxy_3c=p1; authelia_session=s1; CF_Authorization=c1", jar.cookieHeader(music))
    }

    @Test
    fun anExpiryInThePastRemovesTheCookie() {
        jar.keep(music, listOf("authentik_proxy_a=p1; Path=/"))
        jar.keep(music, listOf("authentik_proxy_a=; Path=/; Expires=Thu, 01 Jan 2026 00:00:00 GMT"))
        assertNull(jar.cookieHeader(music))
        assertTrue(jar.isEmpty())
    }

    @Test
    fun aDeleteThenASetInOneAnswerKeepsTheNewOne() {
        jar.keep(music, listOf("authentik_proxy_a=old; Path=/"))
        jar.keep(music, listOf("authentik_proxy_a=; Path=/; Expires=Thu, 01 Jan 2026 00:00:00 GMT", "authentik_proxy_a=new; Path=/"))
        assertEquals("authentik_proxy_a=new", jar.cookieHeader(music))
    }

    @Test
    fun aSecureCookieNeverGoesOverHttp() {
        jar.keep(music, listOf("authentik_proxy_a=p1; Path=/; Secure"))
        assertEquals("authentik_proxy_a=p1", jar.cookieHeader(music))
        assertNull(jar.cookieHeader("http://music.example.com/rest/ping.view".toHttpUrl()))
    }

    @Test
    fun aHostOnlyCookieNeverGoesToASubdomain() {
        jar.keep(music, listOf("authentik_proxy_a=p1; Path=/"))
        assertNull(jar.cookieHeader("https://home.music.example.com/rest/ping.view".toHttpUrl()))
    }

    @Test
    fun theCookieThatExpiresFirstMakesRoom() {
        val days = listOf(2, 3, 4, 5, 1) + (6..17)
        days.forEach { day -> jar.keep(music, listOf("authentik_proxy_$day=v; Path=/; Expires=${expires(day)}")) }
        val sent = jar.cookieHeader(music)!!.split("; ")
        assertEquals(16, sent.size)
        assertFalse("authentik_proxy_1=v" in sent)
        assertTrue("authentik_proxy_17=v" in sent)
        assertTrue("authentik_proxy_2=v" in sent)
    }

    @Test
    fun theJarReadsItsOwnClock() {
        jar.keep(music, listOf("authentik_proxy_a=p1; Path=/; Expires=Sat, 07 Nov 2026 18:00:00 GMT"))
        assertEquals("authentik_proxy_a=p1", jar.cookieHeader(music))
        now = Instant.parse("2026-11-07T18:00:01Z").toEpochMilli()
        assertNull(jar.cookieHeader(music))
    }

    @Test
    fun theJarNeverPrintsItsValues() {
        jar.keep(music, listOf("authentik_proxy_a=p1-secret; Path=/"))
        assertFalse("p1-secret" in jar.toString())
    }

    @Test
    fun aCookieFromARedirectHopGoesWithTheNextHopAndBothComeBack() {
        val http = securedClient(own.url("/"), cookies = GatewayCookies { now })
        own.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/rest/ping.view?again=1").addHeader("Set-Cookie", "authentik_proxy_a=1; Path=/").build())
        own.enqueue(ok("authentik_proxy_b=2; Path=/"))
        own.enqueue(ok())
        get(http, own.url("/rest/ping.view"))
        get(http, own.url("/rest/ping.view"))
        assertNull(own.takeRequest().headers["Cookie"])
        assertEquals("authentik_proxy_a=1", own.takeRequest().headers["Cookie"])
        assertEquals("authentik_proxy_a=1; authentik_proxy_b=2", own.takeRequest().headers["Cookie"])
    }

    @Test
    fun octosOwnCookiesAndAnotherAddressesAreNeitherKeptNorSent() {
        val cookies = GatewayCookies { now }
        val http = securedClient(own.url("/"), cookies = cookies)
        own.enqueue(ok("octo_admin=a1; Path=/", "authentik_proxy_a=1; Path=/"))
        other.enqueue(ok("authentik_proxy_o=9; Path=/"))
        other.enqueue(ok())
        get(http, own.url("/rest/ping.view"))
        get(http, other.url("/rest/ping.view"))
        get(http, other.url("/rest/ping.view"))
        // Both servers are on this machine: a cookie for one host would
        // match the other by name; only the address keeps them apart.
        assertEquals("authentik_proxy_a=1", cookies.cookieHeader(own.url("/")))
        assertNull(other.takeRequest().headers["Cookie"])
        assertNull(other.takeRequest().headers["Cookie"])
    }

    @Test
    fun anAnswerThatStartedBeforeASwitchLandsInTheOldJar() {
        val before = GatewayCookies { now }
        val after = GatewayCookies { now }
        val address = own.url("/")
        val scopes = AtomicReference(HeaderScope(setOf(origin(address)), emptyMap(), setOf(origin(address)), before))
        val http = OkHttpClient.Builder()
            .serverHeaders { scopes.get() }
            .addInterceptor { chain ->
                scopes.set(HeaderScope(setOf(origin(address)), emptyMap(), setOf(origin(address)), after))
                chain.proceed(chain.request())
            }
            .build()
        own.enqueue(ok("authentik_proxy_a=1; Path=/"))
        get(http, own.url("/rest/ping.view"))
        assertFalse(before.isEmpty())
        assertTrue(after.isEmpty())
    }

    @Test
    fun theFamilyPagesOwnJarWorksBesideItAndHoldsNoGatewayCookie() = runTest {
        own.enqueue(
            MockResponse.Builder()
                .addHeader("Set-Cookie", "octo_family=f1; Path=/")
                .addHeader("Set-Cookie", "authentik_proxy_a=1; Path=/")
                .body("""{"member":{"username":"sam"},"inviteLink":"https://music.example.com/family/join#invite=t9"}""")
                .build(),
        )
        own.enqueue(MockResponse.Builder().body("""{"username":"sam"}""").build())
        FamilyWeb(own.url("/"), securedClient(own.url("/"), cookies = GatewayCookies { now })).addMember("sam", "Sam", FamilyPreset.Kid)
        own.takeRequest()
        assertEquals(listOf("octo_family=f1", "authentik_proxy_a=1"), own.takeRequest().headers["Cookie"]!!.split("; "))
    }

    @Test
    fun aStaleCookieWithTheHeaderPassesAndIsReplaced() = runTest {
        val basic = basicCredentials("alice", "app password")
        own.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val cookie = request.headers["Cookie"].orEmpty()
                return when {
                    "authentik_proxy_s=fresh" in cookie -> MockResponse.Builder().body(PING).build()
                    request.headers["Authorization"] == basic ->
                        MockResponse.Builder().addHeader("Set-Cookie", "authentik_proxy_s=fresh; Path=/").body(PING).build()
                    else -> MockResponse.Builder().code(401).addHeader("Content-Type", HTML).body(AUTHENTIK_PAGE).build()
                }
            }
        }
        val cookies = GatewayCookies { now }
        cookies.keep(own.url("/"), listOf("authentik_proxy_s=stale; Path=/"))
        val client = SubsonicClient(own.url("/"), Credentials("alice", "pw"), securedClient(own.url("/"), mapOf("Authorization" to basic), cookies = cookies))
        client.ping()
        client.ping()
        val first = own.takeRequest()
        assertEquals("authentik_proxy_s=stale", first.headers["Cookie"])
        assertEquals(basic, first.headers["Authorization"])
        val second = own.takeRequest()
        assertEquals("authentik_proxy_s=fresh", second.headers["Cookie"])
        assertEquals(basic, second.headers["Authorization"])
    }
}

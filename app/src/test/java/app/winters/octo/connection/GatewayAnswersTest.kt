package app.winters.octo.connection

import app.winters.octo.admin.AdminStatus
import app.winters.octo.admin.AdminUnavailable
import app.winters.octo.admin.readAdminAnswer
import app.winters.octo.data.reachOf
import app.winters.octo.server.Reach
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.gateway.Gateway
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

// What answers from a sign-in page in front of the server read as through
// the app's own client: named, with whether the app's sign-in for it went,
// and never a refused password.
@RunWith(RobolectricTestRunner::class)
class GatewayAnswersTest {
    private val server = MockWebServer()
    private val security = testSecurity()
    private lateinit var main: HttpUrl
    private lateinit var http: OkHttpClient
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun start() {
        main = server.startHome()
        http = security.install(OkHttpClient.Builder()).build()
    }

    @After
    fun stop() = server.close()

    private fun withHeaders(vararg headers: ServerHeader) = security.configure(main, ConnectionSettings(headers = headers.toList()))

    // Authentik turning away a missing or wrong app password: 401 and its page.
    private fun authentikRefusal() = server.enqueue(
        MockResponse.Builder().code(401).addHeader("Content-Type", "text/html; charset=utf-8")
            .body("<html><body aria-label=\"authentik\"></body></html>").build(),
    )

    private fun pingFailure(): SubsonicException.NotSubsonic = runBlocking {
        try {
            SubsonicClient(main, Credentials("alice", "pw"), http).ping()
            throw AssertionError("The ping passed")
        } catch (e: SubsonicException.NotSubsonic) {
            e
        }
    }

    private fun adminStatus(): AdminStatus =
        http.newCall(Request.Builder().url(main.resolve("/api/admin/status")!!).build()).execute().use { response ->
            readAdminAnswer(response, "status", json, AdminStatus.serializer())
        }

    // Red when loginSent is read from the pre-interceptor request, or when
    // gateway headers are withheld from a home-network http address.
    @Test
    fun authentikIsNamedWithTheAppsSignInSent() {
        withHeaders(ServerHeader("Authorization", "Basic YWxpY2U6d3Jvbmc="))
        authentikRefusal()
        val failure = pingFailure()
        assertEquals(Gateway.Authentik, failure.gateway)
        assertTrue(failure.loginSent)
        assertEquals(401, failure.status)
    }

    // Red when loginSent is always true.
    @Test
    fun withoutGatewayFieldsTheSameAnswerSaysNothingWasSent() {
        withHeaders()
        authentikRefusal()
        val failure = pingFailure()
        assertEquals(Gateway.Authentik, failure.gateway)
        assertFalse(failure.loginSent)
    }

    // Red when the reader looks at the final URL only.
    @Test
    fun autheliaAndCloudflareAreNamed() {
        withHeaders()
        server.enqueue(MockResponse.Builder().code(401).addHeader("Location", "https://auth.example.com/?rd=https%3A%2F%2Fmusic.example.com%2F&rm=GET").build())
        assertEquals(Gateway.Authelia, pingFailure().gateway)
        server.enqueue(
            MockResponse.Builder().code(403).addHeader("cf-access-aud", "abc123").addHeader("Content-Type", "text/html")
                .body("<html>Forbidden</html>").build(),
        )
        assertEquals(Gateway.CloudflareAccess, pingFailure().gateway)
    }

    // Red when an admin call reads its answer outside the answer reader.
    @Test
    fun anAdminCallBehindASignInPageNamesIt() {
        withHeaders()
        authentikRefusal()
        val refused = assertThrows(AdminUnavailable::class.java) { adminStatus() }
        assertEquals(Gateway.Authentik, refused.gateway)
        assertEquals(GatewayWords.failure(Gateway.Authentik, false), refused.message)
    }

    // Red when the 403 branch or the JSON decode is lost.
    @Test
    fun octosOwnAnswersReadAsBefore() {
        withHeaders()
        server.enqueue(MockResponse.Builder().code(403).addHeader("Content-Type", "application/json").body("""{"error":"admin only"}""").build())
        val refused = assertThrows(AdminUnavailable::class.java) { adminStatus() }
        assertNull(refused.gateway)
        assertEquals("Needs a Navidrome admin account", refused.message)
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json").body("""{"octo":{"ok":true}}""").build())
        assertTrue(adminStatus().octo.ok)
    }

    // T5: red when a gateway answer reads as a refused password (which
    // asks for the password again) or is lost as out of reach.
    @Test
    fun aKeptServerBehindASignInPageSaysSoAndKeepsItsPassword() {
        val met = reachOf(SubsonicException.NotSubsonic("HTTP 401 from ping", 401, Gateway.Authelia, true)) as Reach.SignInPage
        assertEquals(Gateway.Authelia, met.gateway)
        assertTrue(met.loginSent)
        assertEquals(Reach.WrongPassword, reachOf(SubsonicException.WrongCredentials("Wrong username or password")))
        assertEquals(Reach.Unreachable, reachOf(SubsonicException.NotSubsonic("HTTP 502 from ping", 502)))
        assertEquals(Reach.Unreachable, reachOf(SubsonicException.Unreachable(IOException("refused"))))
    }

    // Red when needsHttps ignores the address or the header names' case.
    @Test
    fun aGatewaySignInOverPublicHttpNeedsHttps() {
        val signIn = listOf(ServerHeader("authorization", "Basic YWxpY2U6cHc="))
        assertTrue(needsHttps("http://music.example.com/".toHttpUrl(), signIn))
        assertTrue(needsHttps("http://music.example.com/".toHttpUrl(), listOf(ServerHeader("CF-Access-Client-Id", "id"))))
        assertFalse(needsHttps("https://music.example.com/".toHttpUrl(), signIn))
        assertFalse(needsHttps("http://192.168.1.20:4533/".toHttpUrl(), signIn))
        assertFalse(needsHttps("http://music.example.com/".toHttpUrl(), listOf(ServerHeader("X-Token", "abc"))))
    }
}

package app.winters.octo.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.connection.ConnectionSettings
import app.winters.octo.connection.ServerClients
import app.winters.octo.connection.ServerHeader
import app.winters.octo.connection.startHome
import app.winters.octo.connection.testDevices
import app.winters.octo.connection.testSecurity
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.gateway.Gateway
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64

// A sign-in, edit or new server is looked at through a client of its own:
// every step (ping, extensions, the Octo probe) carries its own sign-in page
// fields, and a session the server in use holds never lets a new password or
// another account through.
@RunWith(RobolectricTestRunner::class)
class ConnectionCheckTest {
    private val server = MockWebServer()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val looks = ConnectionCheck(ServerClients(context, testDevices()), OctoProbe())
    private lateinit var main: HttpUrl

    private val good = basic("alice", "app-pass")

    private fun basic(user: String, password: String) = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    private fun subsonic(extra: String = "") =
        """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","serverVersion":"2026.11.1","openSubsonic":true$extra}}"""

    // A stub Authentik in front of Octo: the right app password, or the
    // session cookie it hands out on a sign-in with it, gets through;
    // anything else gets its 401 page.
    @Before
    fun start() {
        main = server.startHome()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val byHeader = request.headers["Authorization"] == good
                val byCookie = request.headers["Cookie"].orEmpty().contains("authentik_proxy_s=old")
                if (!byHeader && !byCookie) {
                    return MockResponse.Builder().code(401).addHeader("Content-Type", "text/html; charset=utf-8")
                        .body("<html><body aria-label=\"authentik\"></body></html>").build()
                }
                val answer = MockResponse.Builder().addHeader("Content-Type", "application/json")
                if (byHeader) answer.addHeader("Set-Cookie", "authentik_proxy_s=old; Path=/")
                return when (request.url.encodedPath) {
                    "/rest/ping" -> answer.body(subsonic())
                    "/rest/getOpenSubsonicExtensions" -> answer.body(subsonic(""","openSubsonicExtensions":[{"name":"octoAcquisitions","versions":[1]}]"""))
                    "/api/admin/status" -> answer.body("""{"octo":{"ok":true}}""")
                    else -> answer.code(404).body("{}")
                }.build()
            }
        }
    }

    @After
    fun stop() = server.close()

    private fun look(settings: ConnectionSettings, user: String = "alice"): Checked =
        runBlocking { looks.look(main, settings, Credentials(user, "pw")) }

    private fun refusal(checked: Checked): SubsonicException.NotSubsonic =
        ((checked as Checked.Refused).error as SignInError.Failed).cause as SubsonicException.NotSubsonic

    // The server in use signed in through the gateway, so the shared client
    // holds its session cookie.
    private fun serverInUse(): OkHttpClient {
        val shared = testSecurity().apply { configure(main, ConnectionSettings(headers = listOf(ServerHeader("Authorization", good)))) }
        val live = shared.install(OkHttpClient.Builder()).build()
        runBlocking { SubsonicClient(main, Credentials("alice", "pw"), live).ping() }
        return live
    }

    // Red when the Octo probe or the extensions go through any client but
    // the check's own (OctoProbe keeping an injected client, say).
    @Test
    fun everyStepGoesThroughTheChecksOwnSignIn() {
        val found = look(ConnectionSettings(headers = listOf(ServerHeader("Authorization", good)))) as Checked.Found
        assertTrue(found.octo)
        assertTrue("octoAcquisitions:1" in found.extensions)
        assertEquals(3, server.requestCount)
        repeat(3) { assertEquals(good, server.takeRequest().headers["Authorization"]) }
    }

    // Red when a check rides the shared session (a jar shared between
    // securities, or a check on the shared client).
    @Test
    fun aNewPasswordIsNeverPassedOnTheSessionOfTheServerInUse() {
        val live = serverInUse()
        val refused = refusal(look(ConnectionSettings(headers = listOf(ServerHeader("Authorization", basic("alice", "wrong"))))))
        assertEquals(Gateway.Authentik, refused.gateway)
        assertTrue(refused.loginSent)
        // The server in use keeps its own sign-in all along.
        runBlocking { SubsonicClient(main, Credentials("alice", "pw"), live).ping() }
    }

    // Red the same way, for a second account at the same address.
    @Test
    fun anotherAccountAtTheSameAddressNeedsItsOwnSignIn() {
        serverInUse()
        val refused = refusal(look(ConnectionSettings(), user = "bob"))
        assertEquals(Gateway.Authentik, refused.gateway)
        assertFalse(refused.loginSent)
    }
}

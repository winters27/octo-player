package app.winters.octo.server

import app.winters.octo.connection.GATEWAY_PAGE
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.PAGE_TYPE
import app.winters.octo.connection.securedHttp
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.basicCredentials
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// A sign-in page on a kept server's row, and in a change of password.
class GatewayRowsTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    @Test
    fun aSignInPageOrPlainHttpIsSaidInTheRowAsAWarning() {
        val page = ServerCheck(Reach.SignInPage(Gateway.Authentik, loginSent = true))
        assertEquals("Octo 0.9.3 · Authentik didn't accept the sign-in", statusLine("octo", "0.9.3", false, page))
        assertEquals("Octo 0.9.3 · Needs https for its sign-in page", statusLine("octo", "0.9.3", false, ServerCheck(Reach.NeedsHttps)))
        assertTrue(page.isWarning())
        assertTrue(ServerCheck(Reach.NeedsHttps).isWarning())
    }

    // The server in use says in its row, as a warning, that it needs https
    // or that its sign-in page turned it away, since nothing else says so;
    // its other troubles stay below the list. Red when the in-use branch of
    // statusLine comes before either, or warnsInRow hides one.
    @Test
    fun theServerInUseSaysItsSignInPageTroubleInItsRow() {
        val needs = ServerCheck(Reach.NeedsHttps)
        assertEquals("Octo 0.9.3 · Needs https for its sign-in page", statusLine("octo", "0.9.3", false, needs, inUse = true))
        assertTrue(needs.warnsInRow(inUse = true))
        val refused = ServerCheck(Reach.SignInPage(Gateway.Authentik, loginSent = true))
        assertEquals("Octo 0.9.3 · Authentik didn't accept the sign-in", statusLine("octo", "0.9.3", false, refused, inUse = true))
        assertTrue(refused.warnsInRow(inUse = true))
        val page = ServerCheck(Reach.SignInPage(Gateway.Authentik, loginSent = false))
        assertEquals("Octo 0.9.3 · Authentik is in front of this server", statusLine("octo", "0.9.3", false, page, inUse = true))
        assertTrue(page.warnsInRow(inUse = true))
        assertTrue(page.warnsInRow(inUse = false))
        val gone = ServerCheck(Reach.Unreachable)
        assertEquals("Octo 0.9.3", statusLine("octo", "0.9.3", false, gone, inUse = true))
        assertEquals(false, gone.warnsInRow(inUse = true))
        assertEquals("Signed out. Sign in again to use it.", statusLine("octo", "0.9.3", true, needs))
    }

    // The account card names the sign-in page that turned the server away,
    // and a wrong password as such, rather than calling either out of reach.
    @Test
    fun theAccountCardsTroubleNamesWhatAnswered() {
        assertEquals("Authentik didn't accept the sign-in", ServerCheck(Reach.SignInPage(Gateway.Authentik, loginSent = true)).troubleWords())
        assertEquals("Authentik is in front of this server", ServerCheck(Reach.SignInPage(Gateway.Authentik, loginSent = false)).troubleWords())
        assertEquals("Needs https for its sign-in page", ServerCheck(Reach.NeedsHttps).troubleWords())
        assertEquals("Didn't take the saved password", ServerCheck(Reach.WrongPassword).troubleWords())
        assertEquals("Can't reach", ServerCheck(Reach.Unreachable).troubleWords())
        assertEquals(null, ServerCheck(Reach.Answers, 40).troubleWords())
        assertEquals(null, null.troubleWords())
    }

    @Test
    fun aPasswordChangeBehindASignInPageSaysWhatAnswered() = runTest {
        server.enqueue(MockResponse.Builder().code(401).addHeader("Content-Type", PAGE_TYPE).body(GATEWAY_PAGE).build())
        val http = securedHttp(server.url("/"), mapOf("Authorization" to basicCredentials("winters", "app password")))
        val client = SubsonicClient(server.url("/"), Credentials("winters", "old"), http)
        assertEquals(PasswordChange.Failed(GatewayWords.failure(Gateway.Authentik, true)), changeOwnPassword(client, "old", "new"))
    }
}

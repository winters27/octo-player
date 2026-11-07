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

    @Test
    fun aPasswordChangeBehindASignInPageSaysWhatAnswered() = runTest {
        server.enqueue(MockResponse.Builder().code(401).addHeader("Content-Type", PAGE_TYPE).body(GATEWAY_PAGE).build())
        val http = securedHttp(server.url("/"), mapOf("Authorization" to basicCredentials("winters", "app password")))
        val client = SubsonicClient(server.url("/"), Credentials("winters", "old"), http)
        assertEquals(PasswordChange.Failed(GatewayWords.failure(Gateway.Authentik, true)), changeOwnPassword(client, "old", "new"))
    }
}

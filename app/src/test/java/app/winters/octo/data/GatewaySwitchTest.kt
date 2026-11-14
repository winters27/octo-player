package app.winters.octo.data

import app.winters.octo.connection.ConnectionSettings
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.MemoryPrefs
import app.winters.octo.connection.ServerHeader
import app.winters.octo.connection.encodeConnection
import app.winters.octo.connection.sealed
import app.winters.octo.connection.startHome
import app.winters.octo.server.switchFailedWords
import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.basicCredentials
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A switch to a kept server whose sign-in page turns the app away, run
// through the sessions themselves: it names the page and leaves the server
// with its password, never asking for the password again.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GatewaySwitchTest {
    private val server = MockWebServer()
    private lateinit var main: HttpUrl
    private val appPassword = basicCredentials("alice", "old-app-pass")

    @Before
    fun start() {
        installTestKeystore()
        main = server.startHome()
        // Authentik in front of Octo, turning away the app password it is
        // sent: 401 and its page.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder().code(401)
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .body("<html><body aria-label=\"authentik\"></body></html>").build()
        }
    }

    @After
    fun stop() = server.close()

    // Red when switchTo reads a sign-in page's answer as a refused password.
    @Test
    fun aSwitchToAServerBehindASignInPageNamesItAndKeepsThePassword() = runBlocking {
        val vault = CredentialVault()
        val store = SessionStore(MemoryPrefs())
        val signIn = ConnectionSettings(headers = listOf(ServerHeader("Authorization", appPassword)))
        val kept = StoredServer(
            serverUrl = main.toString(),
            username = "alice",
            passwordSealed = vault.seal("pw"),
            connection = encodeConnection(signIn.sealed(vault::seal)),
            id = "alice-gateway",
        )
        store.update { KeptServers(listOf(kept)) }
        val sessions = testSessions(store, vault)

        val result = sessions.switchTo(kept.id)

        val failed = result as? SwitchResult.Failed
        assertNotNull("The switch met a sign-in page and answered $result", failed)
        assertEquals(switchFailedWords(kept.name, GatewayWords.failure(Gateway.Authentik, true)), failed!!.message)
        // The ping carried the saved sign-in page password.
        assertEquals(appPassword, server.takeRequest().headers["Authorization"])
        val after = store.read()
        assertFalse(after.find(kept.id)!!.signedOut)
        assertEquals("pw", vault.open(after.find(kept.id)!!.passwordSealed))
        assertNull(after.active)
        // A switch that failed asks for no sign-in form.
        assertNull(sessions.signInAsk.value)
    }
}

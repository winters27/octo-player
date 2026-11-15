package app.winters.octo.data

import app.winters.octo.connection.ConnectionSettings
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.MemoryPrefs
import app.winters.octo.connection.ServerHeader
import app.winters.octo.connection.encodeConnection
import app.winters.octo.connection.sealed
import app.winters.octo.connection.startHome
import app.winters.octo.server.passwordRefusedWords
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A switch that needs the password asks for the server's sign-in form
// itself, in the sessions, where nothing on screen can cut it short; a
// switch that works, or fails some other way, asks for nothing; and asking
// again sends nothing to the server.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SwitchAskTest {
    private val server = MockWebServer()

    @Before
    fun start() = installTestKeystore()

    @After
    fun stop() = server.close()

    private fun answering(body: String) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
    }

    // The sessions keeping `kept` (made with `vault`), none in use.
    private fun sessionsKeeping(vault: CredentialVault, kept: StoredServer): SessionRepository {
        val store = SessionStore(MemoryPrefs())
        runBlocking { store.update { KeptServers(listOf(kept)) } }
        return testSessions(store, vault)
    }

    // The ask the sessions raised; the row fails when there is none.
    private fun SessionRepository.raised(): SignInAsk = signInAsk.value ?: throw AssertionError("The switch raised no ask")

    private fun stored(vault: CredentialVault, address: String, settings: ConnectionSettings = ConnectionSettings()) = StoredServer(
        serverUrl = address,
        username = "alice",
        passwordSealed = vault.seal("pw"),
        connection = encodeConnection(settings.sealed(vault::seal)),
        id = "a",
    )

    // Red when a switch to a server signed out of asks for nothing (only the
    // page used to open the form, and a tab change cancelled it).
    @Test
    fun aServerSignedOutOfAsksForItsForm() {
        val vault = CredentialVault()
        val sessions = sessionsKeeping(vault, StoredServer("https://music.example.com/", "alice", id = "a", signedOut = true))
        assertTrue(runBlocking { sessions.switchTo("a") } is SwitchResult.NeedsPassword)
        val ask = sessions.raised()
        assertEquals("a", ask.serverId)
        assertNull(ask.note)
    }

    // A refused password (a 200 saying so; a bare 401 reads as not a
    // Subsonic server) asks with its words, and asking again ("Sign in")
    // sends nothing. Red when the refusal asks for nothing, or asking again
    // switches again.
    @Test
    fun aRefusedPasswordAsksAndAskingAgainSendsNothing() {
        val main = server.startHome()
        server.dispatcher = answering("""{"subsonic-response":{"status":"failed","error":{"code":40}}}""")
        val vault = CredentialVault()
        val kept = stored(vault, main.toString())
        val sessions = sessionsKeeping(vault, kept)
        assertTrue(runBlocking { sessions.switchTo("a") } is SwitchResult.NeedsPassword)
        val ask = sessions.raised()
        assertEquals("a", ask.serverId)
        assertEquals(passwordRefusedWords(kept.name), ask.note)
        val sent = server.requestCount
        sessions.signInAsked(ask)
        assertNull(sessions.signInAsk.value)
        sessions.ask(ask)
        assertSame(ask, sessions.signInAsk.value)
        assertEquals(sent, server.requestCount)
    }

    // A sign-in page whose password would cross the internet readable (a
    // public host over plain http). Red when that switch asks for nothing.
    @Test
    fun aSignInPageOverPlainHttpAsksWithTheHttpsWords() {
        val vault = CredentialVault()
        val kept = stored(vault, "http://music.example.com/", ConnectionSettings(headers = listOf(ServerHeader("Authorization", "Basic YTpi"))))
        val sessions = sessionsKeeping(vault, kept)
        assertTrue(runBlocking { sessions.switchTo("a") } is SwitchResult.NeedsPassword)
        assertEquals(GatewayWords.NEEDS_HTTPS, sessions.raised().note)
    }

    // Red when a switch that works raises an ask.
    @Test
    fun aSwitchThatWorksAsksForNothing() {
        val main = server.startHome()
        server.dispatcher = answering("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""")
        val vault = CredentialVault()
        val sessions = sessionsKeeping(vault, stored(vault, main.toString()))
        assertTrue(runBlocking { sessions.switchTo("a") } is SwitchResult.Done)
        assertNull(sessions.signInAsk.value)
    }
}

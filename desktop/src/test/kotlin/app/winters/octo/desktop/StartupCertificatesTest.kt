package app.winters.octo.desktop

import app.winters.octo.data.HeaderDraft
import app.winters.octo.desktop.audio.FakeEngine
import app.winters.octo.desktop.audio.openPlayer
import app.winters.octo.desktop.secrets.SecretStore
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.secrets.secretAccount
import app.winters.octo.desktop.server.CertFixtures
import app.winters.octo.desktop.server.CertFixtures.PASSWORD
import app.winters.octo.desktop.server.CertStore
import app.winters.octo.desktop.server.CertificateDraft
import app.winters.octo.desktop.server.ClientAuthServer
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.server.SignInRequest
import app.winters.octo.desktop.server.SwitchOutcome
import app.winters.octo.desktop.server.startOn
import app.winters.octo.desktop.settings.AppPlaces
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.settings.currentOs
import app.winters.octo.server.PasswordChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StartupCertificatesTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val secrets = CertStore()
    private val opened = mutableListOf<AutoCloseable>()
    private val places by lazy { AppPlaces(File(folder.root, "config"), File(folder.root, "cache")) }
    private val settingsFile get() = File(places.config, SettingsStore.FILE_NAME)
    private val a by lazy { CertFixtures.copy("rsa2048.p12", folder.root) }

    @After
    fun stop() {
        scope.cancel()
        opened.forEach { runCatching { it.close() } }
    }

    private fun tls(auth: ClientAuthServer.Auth = ClientAuthServer.Auth.Required) = ClientAuthServer(auth).also { opened += it }

    private fun plain() = FakeServer(CertFixtures.serverContext().socketFactory).also { server ->
        opened += server
        server.answer("ping", type = "octo")
        server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[]""", type = "octo")
        server.answer("changePassword", type = "octo")
    }

    // A start on the real settings file, as Main makes one, with the test
    // servers trusted.
    private fun start(store: SecretStore = secrets, engine: FakeEngine = FakeEngine()): StartupParts {
        val settings = startOn(settingsFile)
        CertFixtures.trustTestServers(settings)
        return Startup(settings, places, currentOs(), openSecrets = { store }) { engine }.now()
    }

    // A read-only run, as Open without my settings gives while another
    // program holds the settings file: the defaults, with no saved servers,
    // and nothing written.
    private fun readOnlyStart(settings: SettingsStore = SettingsStore.readOnly(), store: SecretStore = secrets): StartupParts {
        CertFixtures.trustTestServers(settings)
        return Startup(settings, places, currentOs(), openSecrets = { store }) { FakeEngine() }.now()
    }

    // Red when a read-only run's sign-in or certificate reaches the system's
    // store.
    @Test
    fun aReadOnlyRunSignsInWithACertificateAndLeavesNothingInTheStore() = runTest {
        val server = tls()
        val settings = SettingsStore.readOnly()
        val parts = readOnlyStart(settings)
        assertTrue(parts.accounts.signIn(SignInRequest(server.address, "winters", "pw", certificate = CertificateDraft.Use(a.path, PASSWORD))) is SignInOutcome.Done)
        // Closed without signing out.
        settings.flush()
        assertTrue(secrets.writes.isEmpty())
        assertFalse(secrets.kept.keys.any { it.startsWith("certificate@") })
        val later = start()
        assertTrue(later.accounts.servers.isEmpty())
        assertNull(later.restored)
        assertTrue(secrets.kept.isEmpty())
    }

    // Red when a read-only run over a store that lasts only for the run
    // says so: its banner already says nothing is kept.
    @Test
    fun aReadOnlyRunOverAStoreForTheRunAloneSaysNothingOfTheStore() = runTest {
        val server = tls()
        val parts = readOnlyStart(store = SessionOnlySecrets())
        val done = parts.accounts.signIn(SignInRequest(server.address, "winters", "pw", certificate = CertificateDraft.Use(a.path, PASSWORD))) as SignInOutcome.Done
        assertNull(done.note)
        assertFalse(parts.accounts.remembersSignIn)
    }

    // Red when a read-only run's sign-out deletes the password the real
    // settings sign in with.
    @Test
    fun aReadOnlyRunsSignOutIsNotKept() = runTest {
        val server = plain()
        start().accounts.signIn(SignInRequest(server.address, "winters", "pw")) as SignInOutcome.Done
        val parts = readOnlyStart()
        assertNull("a read-only run starts with no saved servers", parts.restored)
        parts.accounts.signIn(SignInRequest(server.address, "winters", "pw")) as SignInOutcome.Done
        parts.accounts.signOut()
        val later = start()
        assertNotNull(later.restored)
        assertFalse(later.accounts.active!!.signedOut)
    }

    // Red when a header value changed in a read-only run is lost at its own
    // sign-out, or reaches the system's store.
    @Test
    fun aReadOnlyRunSendsTheHeaderValueItChanged() = runTest {
        val server = plain()
        val k = (start().accounts.signIn(SignInRequest(server.address, "winters", "pw", headers = listOf(HeaderDraft("X-Token", "old")))) as SignInOutcome.Done).connection.server
        val parts = readOnlyStart()
        val run = (parts.accounts.signIn(SignInRequest(server.address, "winters", "pw", headers = listOf(HeaderDraft("X-Token", "old")))) as SignInOutcome.Done).connection.server
        assertEquals("the same account, the same id", k.id, run.id)
        parts.accounts.edit(run.id, SignInRequest(server.address, "winters", "", headers = listOf(HeaderDraft("X-Token", "new")))) as SignInOutcome.Done
        parts.accounts.signOut()
        parts.accounts.signIn(SignInRequest(server.address, "winters", "pw", headers = listOf(HeaderDraft("X-Token", "", saved = true)))) as SignInOutcome.Done
        assertEquals("new", server.calls.last().headers["X-Token"])
        assertTrue(secrets.kept.getValue("headers@${k.id}").contains("old"))
    }

    // Remember off for this run only, then a new password: the server has
    // it, so the next start must sign in with it.
    @Test
    fun aReadOnlyRunKeepsANewPasswordWhereTheOldOneIs() = runTest {
        val server = plain()
        val k = (start().accounts.signIn(SignInRequest(server.address, "winters", "pw")) as SignInOutcome.Done).connection.server
        val parts = readOnlyStart()
        val done = parts.accounts.signIn(SignInRequest(server.address, "winters", "pw", rememberPassword = false)) as SignInOutcome.Done
        assertEquals(PasswordChange.Changed, parts.accounts.changePassword(done.connection, "pw", "pw2").result)
        assertEquals("pw2", secrets.kept[secretAccount("winters", k.address)])
        assertNotNull(start().restored)
    }

    // The restored server's certificate reaches the engine before the
    // player is made, and a switch gives it before it returns.
    @Test
    fun theEngineHasTheCertificateBeforeAnySong() = runTest {
        val server = tls()
        val other = plain()
        val k = (start().accounts.signIn(SignInRequest(server.address, "winters", "pw", certificate = CertificateDraft.Use(a.path, PASSWORD))) as SignInOutcome.Done).connection.server
        val engine = FakeEngine()
        val parts = start(engine = engine)
        assertNotNull(parts.restored)
        openPlayer(startOn(settingsFile), scope, { null }, identity = parts.accounts.engineIdentity, opened = parts.engine)
        val given = engine.calls.indexOfFirst { it.startsWith("certificate") }
        assertEquals("certificate 1", engine.calls[given])
        val loaded = engine.calls.indexOfFirst { it.startsWith("load") }
        assertTrue(loaded == -1 || given < loaded)
        parts.accounts.signIn(SignInRequest(other.address, "brandon", "pw")) as SignInOutcome.Done
        assertNull(engine.certificate)
        assertTrue(parts.accounts.switchTo(k.id) is SwitchOutcome.Done)
        assertEquals(1, engine.certificate!!.chain.size)
        assertEquals("certificate 1", engine.calls.last { it.startsWith("certificate") })
    }
}

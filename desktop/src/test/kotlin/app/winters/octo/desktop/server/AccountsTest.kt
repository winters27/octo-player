package app.winters.octo.desktop.server

import app.winters.octo.data.HeaderDraft
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.secrets.SecretStore
import app.winters.octo.desktop.secrets.SecretStoreException
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.secrets.secretAccount
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.SubsonicException
import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// A password store that keeps secrets like the real ones do, and can be
// made to refuse.
class FakeSecrets(var refuse: Boolean = false) : SecretStore {
    val kept = HashMap<String, String>()
    override val label = "Fake store"

    override fun read(account: String) = kept[account]

    override fun write(account: String, secret: String) {
        if (refuse) throw SecretStoreException("locked")
        kept[account] = secret
    }

    override fun delete(account: String) {
        kept.remove(account)
    }
}

class AccountsTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = FakeServer()
    private val secrets = FakeSecrets()
    private val settingsFile get() = File(folder.root, "settings.json")
    private val settings by lazy { SettingsStore(settingsFile) }
    private val accounts by lazy { Accounts(settings, secrets, OkHttpClient()) }

    @After fun stop() = server.close()

    private fun octoServer() {
        server.answer("ping", type = "octo")
        server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[{"name":"songLyrics","versions":[1,2]},{"name":"octoAcquisitions","versions":[1]}]""", type = "octo")
    }

    @Test
    fun testingAConnectionFindsTheServerAndItsExtensions() = runTest {
        octoServer()
        val tested = accounts.test(server.address, "winters", "pw") as TestOutcome.Reached
        assertEquals(listOf("octoAcquisitions:1", "songLyrics:1", "songLyrics:2"), tested.facts.extensionKeys)
        assertTrue(tested.facts.summary(), tested.facts.summary().contains("including Octo's"))
        assertNull("testing keeps nothing", settings.current.server)
        assertTrue(secrets.kept.isEmpty())
    }

    @Test
    fun signInUsesATokenSoThePasswordNeverTravels() = runTest {
        octoServer()
        accounts.signIn(server.address, "winters", "correct horse")
        server.calls.forEach { call ->
            assertNotNull(call.url.queryParameter("t"))
            assertNull(call.url.queryParameter("p"))
            assertFalse(call.url.toString().contains("horse"))
        }
    }

    @Test
    fun thePasswordGoesToTheStoreAndNowhereElse() = runTest {
        octoServer()
        val done = accounts.signIn(server.address, "winters", "correct horse") as SignInOutcome.Done
        assertTrue(done.remembered)
        assertTrue(done.connection.acquires)
        assertTrue(done.connection.isOcto)
        val saved = settings.current.server!!
        assertEquals("correct horse", secrets.kept[secretAccount("winters", saved.address)])
        assertFalse(settingsFile.readText().contains("correct horse"))
        assertTrue("the settings page says so", accounts.remembersSignIn)
    }

    @Test
    fun theNextRunSignsInFromTheStore() = runTest {
        octoServer()
        accounts.signIn(server.address, "winters", "correct horse")
        val later = Accounts(SettingsStore(settingsFile), secrets, OkHttpClient())
        val restored = later.restore()
        assertNotNull(restored)
        assertEquals("winters", restored!!.client.username)
        assertTrue(restored.supports("songLyrics", 2))
    }

    @Test
    fun withoutTheStoredPasswordThereIsNothingToRestore() = runTest {
        octoServer()
        accounts.signIn(server.address, "winters", "correct horse")
        secrets.kept.clear()
        assertNull(Accounts(SettingsStore(settingsFile), secrets, OkHttpClient()).restore())
    }

    @Test
    fun aStoreThatRefusesStillSignsInAndSaysSo() = runTest {
        octoServer()
        secrets.refuse = true
        val done = accounts.signIn(server.address, "winters", "pw") as SignInOutcome.Done
        assertFalse(done.remembered)
        assertTrue(done.note!!.contains("couldn't save the password"))
        assertNotNull("usable for this run", accounts.restore())
    }

    @Test
    fun aSystemWithNoStoreKeepsThePasswordOnlyForThisRun() = runTest {
        octoServer()
        val memoryOnly = Accounts(settings, SessionOnlySecrets(), OkHttpClient())
        val done = memoryOnly.signIn(server.address, "winters", "pw") as SignInOutcome.Done
        assertFalse(done.remembered)
        assertTrue(done.note!!.contains("no password store"))
        assertNull(Accounts(SettingsStore(settingsFile), SessionOnlySecrets(), OkHttpClient()).restore())
    }

    @Test
    fun signingOutForgetsThePasswordAndKeepsTheServerListed() = runTest {
        octoServer()
        accounts.signIn(server.address, "winters", "pw")
        accounts.signOut()
        assertTrue(secrets.kept.isEmpty())
        assertNull(settings.current.server)
        assertNull(accounts.restore())
        assertTrue(accounts.servers.single().signedOut)
        assertEquals("the sign-in page starts from it", "winters", accounts.last!!.username)
    }

    @Test
    fun signingInAsSomeoneElseKeepsTheFirstAccountToo() = runTest {
        octoServer()
        accounts.signIn(server.address, "winters", "pw")
        accounts.signIn(server.address, "brandon", "pw2")
        val address = settings.current.server!!.address
        assertEquals(setOf(secretAccount("winters", address), secretAccount("brandon", address)), secrets.kept.keys)
        assertEquals(listOf("winters", "brandon"), accounts.servers.map { it.username })
        assertEquals("brandon", accounts.active!!.username)
    }

    @Test
    fun wrongPasswordsAndBadAddressesAreSaidPlainly() = runTest {
        server.fail("ping", 40, "Wrong username or password")
        assertEquals("Wrong username or password.", (accounts.signIn(server.address, "winters", "nope") as SignInOutcome.Failed).message)
        assertEquals("That doesn't look like a server address.", (accounts.test("http://", "winters", "pw") as TestOutcome.Failed).message)
        assertNull(settings.current.server)
    }

    @Test
    fun aPlainSubsonicServerIsNotAskedForExtensions() = runTest {
        server.answer("ping", openSubsonic = false)
        val tested = accounts.test(server.address, "winters", "pw", AuthMode.LegacyPassword) as TestOutcome.Reached
        assertTrue(tested.facts.extensions.isEmpty())
        assertEquals(listOf("ping"), server.endpoints())
        assertTrue(tested.facts.summary().contains("plain Subsonic"))
    }

    // Accounts on a client set up the way the app's is, with the server's
    // headers and trusted certificates.
    private fun wired(store: SettingsStore = settings, keep: SecretStore = secrets): Accounts {
        val security = ServerSecurity(store)
        return Accounts(store, keep, security.install(OkHttpClient.Builder()).build(), security)
    }

    @Test
    fun withRememberOffThePasswordIsKeptOnlyForThisRun() = runTest {
        octoServer()
        val done = accounts.signIn(SignInRequest(server.address, "winters", "correct horse", rememberPassword = false)) as SignInOutcome.Done
        assertFalse(done.remembered)
        assertNull(done.note)
        assertTrue("nothing in the store", secrets.kept.isEmpty())
        assertFalse(settingsFile.readText().contains("correct horse"))
        assertFalse("the choice is remembered", settings.current.server!!.rememberSignIn)
        assertNotNull("usable for this run", accounts.restore())
        assertNull("gone next run", Accounts(SettingsStore(settingsFile), secrets, OkHttpClient()).restore())
        assertFalse("the settings page says so", accounts.remembersSignIn)
    }

    @Test
    fun turningRememberOffTakesAnOldCopyOutOfTheStore() = runTest {
        octoServer()
        accounts.signIn(server.address, "winters", "pw")
        accounts.signIn(SignInRequest(server.address, "winters", "pw", rememberPassword = false))
        assertTrue(secrets.kept.isEmpty())
    }

    @Test
    fun headerValuesGoToTheStoreAndWithEveryRequest() = runTest {
        octoServer()
        val done = wired().signIn(SignInRequest(server.address, "winters", "pw", headers = listOf(HeaderDraft("X-Token", "top-secret")))) as SignInOutcome.Done
        server.calls.forEach { assertEquals("top-secret", it.headers["X-Token"]) }
        val address = settings.current.server!!.address
        assertEquals(listOf("X-Token"), settings.current.server!!.headerNames)
        assertFalse(settingsFile.readText().contains("top-secret"))
        assertTrue(secrets.kept[headersAccount(address)]!!.contains("top-secret"))
        assertEquals(mapOf("X-Token" to "top-secret"), done.connection.headers)

        // The next run reads them back, and the shared client sends them.
        server.calls.clear()
        val later = wired(SettingsStore(settingsFile))
        val restored = later.restore()!!
        assertEquals(mapOf("X-Token" to "top-secret"), restored.headers)
        restored.client.ping()
        assertEquals("top-secret", server.calls.single().headers["X-Token"])

        // A saved header left empty keeps its value.
        server.calls.clear()
        later.test(SignInRequest(server.address, "winters", "pw", headers = listOf(HeaderDraft("X-Token", "", saved = true))))
        assertEquals("top-secret", server.calls.first().headers["X-Token"])
    }

    @Test
    fun aHalfFilledHeaderIsPointedOut() = runTest {
        octoServer()
        val failed = accounts.test(SignInRequest(server.address, "winters", "pw", headers = listOf(HeaderDraft("X Token", "v")))) as TestOutcome.Failed
        assertEquals("Each header needs a name with no spaces and a plain text value.", failed.message)
        assertTrue(server.calls.isEmpty())
        // A row left empty is ignored.
        assertTrue(accounts.test(SignInRequest(server.address, "winters", "pw", headers = listOf(HeaderDraft("", "")))) is TestOutcome.Reached)
    }

    @Test
    fun anApiKeySignsInAndTheServerNamesWhoseItIs() = runTest {
        octoServer()
        server.answer("tokenInfo", """"tokenInfo":{"username":"brandon"}""")
        val done = accounts.signIn(SignInRequest(server.address, "", " k3y ", mode = AuthMode.ApiKey)) as SignInOutcome.Done
        assertEquals("brandon", settings.current.server!!.username)
        assertEquals(AuthMode.ApiKey, settings.current.server!!.authMode)
        assertEquals("k3y", secrets.kept[secretAccount("brandon", settings.current.server!!.address)])
        server.calls.forEach {
            assertEquals("k3y", it.url.queryParameter("apiKey"))
            assertNull(it.url.queryParameter("u"))
        }
        assertEquals("brandon", done.connection.client.username)
    }

    @Test
    fun aServerThatCannotCheckTokensGetsThePasswordAtHome() = runTest {
        octoServer()
        server.answerBy("ping") { request ->
            if (request.url.queryParameter("t") != null) server.failed(41, "Token authentication not supported") else server.ok()
        }
        val done = accounts.signIn(server.address, "winters", "pw") as SignInOutcome.Done
        assertEquals("remembered for this server", AuthMode.LegacyPassword, settings.current.server!!.authMode)
        assertEquals(AuthMode.LegacyPassword, done.connection.client.authMode)
        assertEquals("enc:7077", server.calls.last().url.queryParameter("p"))
        val tested = accounts.test(server.address, "winters", "pw") as TestOutcome.Reached
        assertEquals(AuthMode.LegacyPassword, tested.mode)
    }

    @Test
    fun anHttpsHomeAddressThatCannotConnectSuggestsHttp() = runTest {
        val failed = accounts.test("https://127.0.0.1:1", "winters", "pw") as TestOutcome.Failed
        assertTrue(failed.message, failed.message.endsWith("Switch to http:// and try again."))
    }

    @Test
    fun theHomeAddressIsUsedWhenTheMainOneCannotBeReached() = runTest {
        octoServer()
        val done = accounts.signIn(SignInRequest("http://127.0.0.1:1", "winters", "pw", home = server.address)) as SignInOutcome.Done
        assertEquals(server.address, settings.current.server!!.home)
        assertEquals("the main address is still the server's name", "http://127.0.0.1:1/", done.connection.client.primaryUrl.toString())
        // The home address answers, so calls go there once it is checked.
        val deadline = System.currentTimeMillis() + 5_000
        while (done.connection.client.baseUrl.toString() != server.address && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(server.address, done.connection.client.baseUrl.toString())
        accounts.signOut()
    }

    @Test
    fun aBadHomeAddressIsSaidPlainly() = runTest {
        octoServer()
        val failed = accounts.test(SignInRequest(server.address, "winters", "pw", home = "http://")) as TestOutcome.Failed
        assertEquals("The home network address doesn't look like a server address.", failed.message)
    }

    @Test
    fun aBusyServerIsNotBlamedOnThePort() {
        val busy = SubsonicException.NotSubsonic("HTTP 502 from getAlbum", 502).userMessage()
        assertTrue(busy, busy.startsWith("The server isn't answering right now"))
        val wrong = SubsonicException.NotSubsonic("HTTP 404 from ping", 404).userMessage()
        assertTrue(wrong, wrong.endsWith("Check the port."))
    }
}

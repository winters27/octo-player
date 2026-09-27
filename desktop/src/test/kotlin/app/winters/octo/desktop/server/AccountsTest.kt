package app.winters.octo.desktop.server

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.secrets.SecretStore
import app.winters.octo.desktop.secrets.SecretStoreException
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.secrets.secretAccount
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.subsonic.AuthMode
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
import java.io.File

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
    fun signingOutForgetsThePasswordAndTheServer() = runTest {
        octoServer()
        accounts.signIn(server.address, "winters", "pw")
        accounts.signOut()
        assertTrue(secrets.kept.isEmpty())
        assertNull(settings.current.server)
        assertNull(accounts.restore())
    }

    @Test
    fun signingInElsewhereRemovesTheOldPassword() = runTest {
        octoServer()
        accounts.signIn(server.address, "winters", "pw")
        accounts.signIn(server.address, "brandon", "pw2")
        assertEquals(setOf(secretAccount("brandon", settings.current.server!!.address)), secrets.kept.keys)
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
}

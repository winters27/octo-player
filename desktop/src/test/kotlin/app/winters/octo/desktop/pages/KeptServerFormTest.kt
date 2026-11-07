package app.winters.octo.desktop.pages

import app.winters.octo.connection.GatewayWords
import app.winters.octo.data.HeaderDraft
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.secrets.secretAccount
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.FakeSecrets
import app.winters.octo.desktop.server.ServerSecurity
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.server.SignInRequest
import app.winters.octo.desktop.server.SwitchOutcome
import app.winters.octo.desktop.server.headersAccount
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.subsonic.AuthMode
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// A form filled in for a kept server signs in to that server again only
// while it is still that server's host and username, so whatever else is
// typed never replaces it; and a switch to a server that needs https opens
// a sheet that signs in to it again, so it becomes the one in use.
class KeptServerFormTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = FakeServer()
    private val other = FakeServer()

    @After
    fun stop() {
        server.close()
        other.close()
    }

    private val kept = SavedServer("http://music.example.com/", "winters", id = "w1")

    private fun filled() = SignInForm(kept).also { it.keptId = kept.id }

    // Red when signsInAgainTo stops naming the kept server for its own host
    // and username, an address moved to https included.
    @Test
    fun theFormSignsInToTheKeptServerAgainAtItsHttpsAddress() {
        val form = filled()
        assertEquals("w1", form.signsInAgainTo)
        form.toggleScheme()
        assertEquals("https://music.example.com/", form.url.toString())
        assertEquals("w1", form.signsInAgainTo)
    }

    // Red when another username or host still signs in in place of the
    // kept server, or when typing its host back does not bring it back.
    @Test
    fun anotherHostOrUsernameIsAServerOfItsOwn() {
        val form = filled()
        form.username = "guest"
        assertNull(form.signsInAgainTo)
        form.username = "winters"
        assertEquals("w1", form.signsInAgainTo)
        form.typeAddress("other.example.com")
        assertNull(form.signsInAgainTo)
        form.typeAddress("http://music.example.com")
        assertEquals("w1", form.signsInAgainTo)
    }

    // Typing https:// in front of the address key by key passes through
    // other hosts on the way and still ends at the kept server. Red when a
    // host typed on the way lets go of it.
    @Test
    fun typingTheSchemeInFrontKeyByKeyKeepsTheKeptServer() {
        val form = filled()
        val scheme = "https://"
        for (keys in 1..scheme.length) form.typeAddress(scheme.take(keys) + "music.example.com")
        assertEquals("https://music.example.com/", form.url.toString())
        assertEquals("w1", form.signsInAgainTo)
    }

    // A kept server signed in with an API key, signed in to again from its
    // form with a key of another account, stays as it was beside a server
    // of its own for that account. Red when signInAgain with sameAccount
    // still replaces the kept server with the key's account.
    @Test
    fun anApiKeyOfAnotherAccountNeverReplacesTheKeptServer() = runTest {
        server.answer("ping", type = "octo")
        server.answerBy("tokenInfo") { request ->
            val owner = if (request.url.queryParameter("apiKey") == "k1") "brandon" else "guest"
            server.ok(""""tokenInfo":{"username":"$owner"}""")
        }
        val settings = SettingsStore(File(folder.root, "settings.json"))
        val security = ServerSecurity(settings)
        val secrets = FakeSecrets()
        val accounts = Accounts(settings, secrets, security.install(OkHttpClient.Builder()).build(), security)
        val first = (accounts.signIn(SignInRequest(server.address, "", "k1", mode = AuthMode.ApiKey, headers = listOf(HeaderDraft("X-Token", "t")))) as SignInOutcome.Done).connection.server
        assertEquals("brandon", first.username)
        val form = SignInForm(first).also { it.keptId = first.id }
        form.apiKey = "k2"
        assertEquals(first.id, form.signsInAgainTo)
        val again = accounts.signInAgain(first.id, form.request(), sameAccount = true) as SignInOutcome.Done
        assertEquals("guest", again.connection.server.username)
        assertEquals(listOf(first.id, again.connection.server.id), accounts.servers.map { it.id })
        assertEquals(first, accounts.find(first.id))
        assertEquals("k1", secrets.kept[secretAccount("brandon", first.address)])
        assertNotNull(secrets.kept[headersAccount(first)])
    }

    // Red when the switch opens the edit sheet (saved, not switched to) or
    // the secure sheet saves without signing in again.
    @Test
    fun aSwitchThatNeedsHttpsOpensASheetThatSwitches() = runTest {
        server.answer("ping", type = "octo")
        other.answer("ping", type = "octo")
        val settings = SettingsStore(File(folder.root, "settings.json"))
        val security = ServerSecurity(settings)
        val accounts = Accounts(settings, FakeSecrets(), security.install(OkHttpClient.Builder()).build(), security)
        accounts.signIn(SignInRequest(server.address, "winters", "pw")) as SignInOutcome.Done
        val second = (accounts.add(SignInRequest(other.address, "guest", "pw2")) as SignInOutcome.Saved).server
        val kind = sheetFor(SwitchOutcome.NeedsPassword(second, GatewayWords.NEEDS_HTTPS))
        assertEquals(SheetKind.Secure, kind)
        assertEquals(SheetKind.SignIn, sheetFor(SwitchOutcome.NeedsPassword(second)))
        val form = SignInForm(second, keepsSecret = true)
        form.label = "Away"
        val done = saveSheet(accounts, kind, second, form, accounts.ticket()) as SignInOutcome.Done
        assertEquals(second.id, done.connection.server.id)
        assertEquals(second.id, settings.current.activeServer)
        assertEquals("Away", accounts.find(second.id)!!.label)
    }
}

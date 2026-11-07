package app.winters.octo.desktop.pages

import app.winters.octo.connection.GatewayWords
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.FakeSecrets
import app.winters.octo.desktop.server.ServerSecurity
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.server.SignInRequest
import app.winters.octo.desktop.server.SwitchOutcome
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.desktop.settings.SettingsStore
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
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
    // kept server, or when typing its host back brings it back.
    @Test
    fun anotherHostOrUsernameIsAServerOfItsOwn() {
        val form = filled()
        form.username = "guest"
        assertNull(form.signsInAgainTo)
        form.username = "winters"
        assertEquals("w1", form.signsInAgainTo)
        form.typeAddress("other.example.com")
        assertNull(form.keptId)
        form.typeAddress("http://music.example.com")
        assertNull(form.signsInAgainTo)
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

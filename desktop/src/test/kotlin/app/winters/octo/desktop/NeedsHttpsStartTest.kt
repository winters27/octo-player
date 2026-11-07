package app.winters.octo.desktop

import app.winters.octo.connection.GatewayWords
import app.winters.octo.desktop.secrets.secretAccount
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.Connection
import app.winters.octo.desktop.server.FakeSecrets
import app.winters.octo.desktop.server.SwitchOutcome
import app.winters.octo.desktop.server.headersAccount
import app.winters.octo.desktop.server.statusLine
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.lyrics.OnlineLyrics
import app.winters.octo.server.Reach
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.SubsonicClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// A server an older version kept with a raw sign-in page password on a
// plain http address outside the home network: never signed in to at
// start or by a switch, its sign-in page says why, and as the one in use
// it is asked nothing and its row says it needs https.
class NeedsHttpsStartTest {
    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val address = "http://music.invalid/"
    private val secrets = FakeSecrets()
    private lateinit var settings: SettingsStore

    @After
    fun stop() = scope.cancel()

    private fun kept(): Accounts {
        settings = SettingsStore(File(temp.root, "settings.json"))
        settings.update { it.copy(servers = listOf(SavedServer(address, "winters", headerNames = listOf("Authorization"), id = "w1")), activeServer = "w1") }
        secrets.kept[secretAccount("winters", address)] = "pw"
        secrets.kept[headersAccount(settings.current.servers.single())] = """[{"name":"Authorization","value":"Basic YTpi"}]"""
        return Accounts(settings, secrets, OkHttpClient())
    }

    // Red when restore skips the needs-https rule (it would sign in) or
    // leaves the server in use, or when AppState does not fill the sign-in
    // page with it.
    @Test
    fun aServerThatNeedsHttpsIsNotSignedInToAtStartAndItsFormSaysWhy() {
        val accounts = kept()
        assertNull(accounts.restore())
        assertNull("no longer the one in use", settings.current.activeServer)
        assertEquals("its password kept", "pw", secrets.kept[secretAccount("winters", address)])
        val http = OkHttpClient()
        val app = AppState(settings, accounts, http, scope, DesktopOs.Windows, lyricsLibrary = OnlineLyrics(http, "http://127.0.0.1:9/".toHttpUrl()), restored = null)
        assertNull(app.connection)
        assertEquals("w1", app.signInForm.keptId)
        assertEquals(false to GatewayWords.NEEDS_HTTPS, app.signInForm.result)
        assertNull("asked once", accounts.takeStartAsk())
    }

    // A switch to such a server opens its sign-in form saying why, as on
    // the phones. Red when switchTo answers Failed.
    @Test
    fun aSwitchToAServerThatNeedsHttpsOpensItsForm() = runTest {
        val accounts = kept()
        settings.update { it.copy(activeServer = null) }
        val asked = accounts.switchTo("w1") as SwitchOutcome.NeedsPassword
        assertEquals("w1", asked.server.id)
        assertEquals(GatewayWords.NEEDS_HTTPS, asked.note)
    }

    // Red when check asks the server in use before the needs-https
    // rule (it would try music.invalid and say out of reach), or when the
    // in-use row hides the words.
    @Test
    fun theServerInUseThatNeedsHttpsIsNotAskedAndItsRowSaysSo() = runTest {
        val accounts = kept()
        val saved = accounts.find("w1")!!
        val inUse = Connection(SubsonicClient(address.toHttpUrl(), Credentials("winters", "pw"), OkHttpClient()), saved, mapOf("Authorization" to "Basic YTpi"))
        val check = accounts.check("w1", inUse)
        assertEquals(Reach.NeedsHttps, check.reach)
        val line = statusLine(saved, check, inUse = true)
        assertTrue(line, line.endsWith(GatewayWords.ROW_NEEDS_HTTPS))
    }
}

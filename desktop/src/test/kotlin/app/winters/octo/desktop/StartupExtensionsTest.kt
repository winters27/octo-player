package app.winters.octo.desktop

import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.Connection
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.server.SignInRequest
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.lyrics.OnlineLyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.swing.SwingUtilities

// Opening Octo signed in asks the server again what it offers, so an Octo
// updated since the last sign-in shows what it gained at once.
class StartupExtensionsTest {
    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun stop() = scope.cancel()

    private fun lists(server: FakeServer, vararg extensions: String) {
        val listed = extensions.joinToString(",") { """{"name":"${it.substringBefore(':')}","versions":[${it.substringAfter(':')}]}""" }
        server.answer("ping", type = "octo")
        server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[$listed]""", type = "octo")
        server.answer("getAlbumList2", """"albumList2":{"album":[]}""")
        server.answer("getArtists", """"artists":{"index":[]}""")
        server.answer("search3", """"searchResult3":{"song":[]}""")
        server.answer("getPlaylists", """"playlists":{"playlist":[]}""")
    }

    // The window opened on the server kept from the last run, once its
    // second look at the server is done, and the connection it opened with.
    private fun open(server: FakeServer, settings: SettingsStore, http: OkHttpClient, accounts: Accounts): Pair<AppState, Connection> {
        lateinit var app: AppState
        lateinit var first: Connection
        SwingUtilities.invokeAndWait {
            app = AppState(settings, accounts, http, scope, DesktopOs.Windows, SilentPlayer(), OnlineLyrics(http, server.address.toHttpUrl()), restored = accounts.restore(), listeningRoot = File(folder.root, "listening"), browse = {})
            first = app.connection!!
        }
        val asked = app.extensionsAsked
        assertNotNull("the restored server is asked", asked)
        runBlocking { asked!!.join() }
        return app to first
    }

    @Test
    fun aServerUpdatedSinceTheSignInShowsWhatItGained() {
        FakeServer().use { server ->
            lists(server, "octoAcquisitions:1")
            val settings = SettingsStore(File(folder.root, "settings.json"))
            val http = OkHttpClient()
            val accounts = Accounts(settings, SessionOnlySecrets(), http)
            runBlocking { accounts.signIn(SignInRequest(server.address, "winters", "pw")) } as SignInOutcome.Done
            // Octo on the server was updated while the app was closed.
            lists(server, "octoAcquisitions:1", "octoPreview:2")
            val (app, first) = open(server, settings, http, accounts)
            assertFalse("it opened on the saved list", first.supports("octoPreview", 2))
            assertTrue("the pages read the server's new list", app.connection!!.supports("octoPreview", 2))
            assertTrue("and the next opening knows it already", accounts.active!!.extensions.contains("octoPreview:2"))
        }
    }

    @Test
    fun aServerWithNothingNewKeepsItsConnection() {
        FakeServer().use { server ->
            lists(server, "octoAcquisitions:1")
            val settings = SettingsStore(File(folder.root, "settings.json"))
            val http = OkHttpClient()
            val accounts = Accounts(settings, SessionOnlySecrets(), http)
            runBlocking { accounts.signIn(SignInRequest(server.address, "winters", "pw")) } as SignInOutcome.Done
            val (app, first) = open(server, settings, http, accounts)
            assertSame("nothing is read again", first, app.connection)
        }
    }
}

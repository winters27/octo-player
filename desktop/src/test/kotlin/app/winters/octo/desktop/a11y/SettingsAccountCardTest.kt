package app.winters.octo.desktop.a11y

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import app.winters.octo.design.ArrowKeys
import app.winters.octo.design.FocusVisibility
import app.winters.octo.design.LocalTyping
import app.winters.octo.design.ProvideWindowLook
import app.winters.octo.design.TypingState
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.FakeSecrets
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.server.SignInRequest
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.songJson
import app.winters.octo.desktop.ui.Shell
import app.winters.octo.lyrics.OnlineLyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

// The account card at the top of Settings' list, across a switch made from
// Settings: its covers come from the server in use, read without Home ever
// opening there, and never show the server it came from.
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class SettingsAccountCardTest {
    @get:Rule val temp = TemporaryFolder()

    private val home = FakeServer()
    private val work = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var app: AppState
    private lateinit var scene: ImageComposeScene
    private lateinit var workId: String

    @After
    fun stop() {
        if (::scene.isInitialized) runCatching { SwingUtilities.invokeAndWait { scene.close() } }
        scope.cancel()
        home.close()
        work.close()
    }

    private fun album(id: String) = """{"id":"$id","name":"$id","artist":"Artist","coverArt":"$id","songCount":1}"""

    // A server whose recently played albums are `played` and recently
    // added `added`; `hold`, when given, keeps both lists waiting.
    private fun library(server: FakeServer, played: List<String>, added: List<String>, hold: CountDownLatch? = null) {
        server.answer("ping", type = "octo")
        server.answer("getArtists", """"artists":{}""")
        server.answer("getStarred2", """"starred2":{}""")
        server.answer("search3", """"searchResult3":{"song":[${songJson("s-${played.first()}", "One")}]}""")
        server.answer("getPlaylists", """"playlists":{}""")
        server.answerBy("getAlbumList2") { request ->
            val ids = when (request.url.queryParameter("type")) {
                "recent" -> played
                "newest" -> added
                else -> emptyList()
            }
            if (ids.isNotEmpty()) hold?.await(10, TimeUnit.SECONDS)
            server.ok(""""albumList2":{"album":[${ids.joinToString(",") { album(it) }}]}""", type = "octo")
        }
    }

    private fun start() {
        val settings = SettingsStore(File(temp.root, "settings.json"), 0)
        val http = OkHttpClient()
        val accounts = Accounts(settings, FakeSecrets(), http)
        val first = runBlocking { accounts.signIn(home.address, "winters", "pw") } as SignInOutcome.Done
        workId = (runBlocking { accounts.add(SignInRequest(work.address, "brandon", "pw")) } as SignInOutcome.Saved).server.id
        SwingUtilities.invokeAndWait {
            app = AppState(settings, accounts, http, scope, DesktopOs.Windows, SilentPlayer(), OnlineLyrics(http, home.address.toHttpUrl()), restored = first.connection, listeningRoot = temp.root)
        }
        scene = ImageComposeScene(1440, 900, Density(1f)) {
            ProvideWindowLook(reduceMotion = true, focus = FocusVisibility(keyboard = false), arrows = ArrowKeys()) {
                CompositionLocalProvider(LocalTyping provides TypingState()) { Shell(app, null) {} }
            }
        }
        render()
    }

    private fun render() {
        repeat(3) {
            SwingUtilities.invokeAndWait { scene.render() }
            Thread.sleep(15)
        }
    }

    private fun tiles(): List<String> {
        val all = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.TestTag)?.takeIf { it.startsWith("account-cover:") }?.let { all += it.removePrefix("account-cover:") }
            node.children.forEach(::walk)
        }
        SwingUtilities.invokeAndWait { scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) } }
        return all
    }

    // Draws until `what` holds, giving up after ten seconds.
    private fun until(what: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!what() && System.currentTimeMillis() < end) render()
        assertTrue("waited too long", what())
    }

    private fun onUi(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    @Test(timeout = 60_000)
    fun theCoversFillAfterASwitchMadeInSettingsWithoutVisitingHome() {
        library(home, played = listOf("h-p1", "h-p2"), added = listOf("h-a1", "h-a2", "h-a3"))
        library(work, played = listOf("w-p1"), added = listOf("w-a1", "w-a2"))
        start()
        onUi { app.openSettings() }
        until { tiles() == listOf("h-p1", "h-p2", "h-a1", "h-a2") }
        onUi { app.switchTo(workId, Page.Settings) }
        until { app.connection?.server?.id == workId }
        assertEquals(Page.Settings, app.navigator.current.page)
        // One played album, then the recently added, then empty glass.
        until { tiles() == listOf("w-p1", "w-a1", "w-a2", "none") }
        assertEquals("Home was never shown on the new server", Page.Settings, app.navigator.current.page)
    }

    @Test(timeout = 60_000)
    fun aFreshServerShowsNoneOfTheOldCovers() {
        val answer = CountDownLatch(1)
        val shelves = CountDownLatch(1)
        library(home, played = listOf("h-p1"), added = listOf("h-a1"))
        library(work, played = listOf("w-p1"), added = emptyList(), hold = shelves)
        start()
        work.answerBy("ping") {
            answer.await(10, TimeUnit.SECONDS)
            work.ok(type = "octo")
        }
        onUi { app.openSettings() }
        until { tiles() == listOf("h-p1", "h-a1", "none", "none") }
        onUi { app.switchTo(workId, Page.Settings) }
        until { app.switching != null }
        render()
        assertEquals("while switching, empty glass", listOf("none", "none", "none", "none"), tiles())
        answer.countDown()
        until { app.connection?.server?.id == workId }
        render()
        assertFalse("none of the old server's covers", tiles().any { it.startsWith("h-") })
        shelves.countDown()
        until { tiles() == listOf("w-p1", "none", "none", "none") }
    }

    // What the card says, as a screen reader hears it.
    private fun cardWords(): String? {
        var found: String? = null
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.takeIf { it.startsWith("winters, ") }?.let { found = it }
            node.children.forEach(::walk)
        }
        SwingUtilities.invokeAndWait { scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) } }
        return found
    }

    // A server that answers but no longer takes the saved password is named
    // as such on the card, not called out of reach.
    @Test(timeout = 60_000)
    fun aRefusedPasswordIsNamedNotCalledOutOfReach() {
        library(home, played = listOf("h-p1"), added = listOf("h-a1"))
        library(work, played = listOf("w-p1"), added = emptyList())
        start()
        home.answerBy("ping") { home.failed(40, "Wrong username or password") }
        onUi { app.openSettings() }
        until { cardWords()?.endsWith("Didn't take the saved password") == true }
        assertFalse(cardWords()!!.contains("Can't reach"))
    }
}

package app.winters.octo.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import app.winters.octo.design.LocalTyping
import app.winters.octo.design.TypingState
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.pages.addServer
import app.winters.octo.desktop.pages.askToRemove
import app.winters.octo.desktop.pages.changePassword
import app.winters.octo.desktop.pages.editServer
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.server.SignInRequest
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.songJson
import app.winters.octo.lyrics.OnlineLyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.swing.SwingUtilities

// Pictures of Settings > Servers against pretend servers: with one server,
// with three, adding one, editing one, a new password, removing one, its
// menu, a switch on its way and done, and the sign-in page offering the
// others. Only when asked: OCTO_SHOTS=1 ./gradlew :desktop:test --tests '*ScreenShotsTest*'.
// The player is the silent one, so nothing is ever heard.
class ServersScreenShotsTest {
    @get:Rule val folder = TemporaryFolder()

    private fun library(server: FakeServer, type: String, songs: Int, version: String) {
        val list = (1..songs).map { songJson("s$it", "Song $it", album = "Album ${it % 7}", albumId = "a${it % 7}") }
        server.answerBy("ping") { """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"$type","serverVersion":"$version","openSubsonic":true}}""" }
        server.answer("getOpenSubsonicExtensions", if (type == "octo") """"openSubsonicExtensions":[{"name":"songLyrics","versions":[1]},{"name":"octoAcquisitions","versions":[1]}]""" else """"openSubsonicExtensions":[]""", type = type)
        server.answer("getAlbumList2", """"albumList2":{"album":[${(0 until 7).joinToString(",") { """{"id":"a$it","name":"Album $it","artist":"Artist"}""" }}]}""")
        server.answer("getArtists", """"artists":{"index":[{"name":"A","artist":[{"id":"r1","name":"Artist","albumCount":7}]}]}""")
        // A page at a time, as the library is read.
        server.answerBy("search3") { request ->
            val offset = request.url.queryParameter("songOffset")?.toInt() ?: 0
            val count = request.url.queryParameter("songCount")?.toInt() ?: 20
            server.ok(""""searchResult3":{"song":[${list.drop(offset).take(count).joinToString(",")}]}""", type = type)
        }
        server.answer("getPlaylists", """"playlists":{"playlist":[{"id":"p1","name":"Late night","songCount":12},{"id":"p2","name":"Running","songCount":40}]}""")
        server.answer("getUser", """"user":{"username":"winters","adminRole":true,"settingsRole":true}""", type = type)
        server.answer("getScanStatus", """"scanStatus":{"scanning":false,"count":$songs,"lastScan":"${java.time.Instant.now().minusSeconds(3 * 3600)}"}""", type = type)
    }

    @Test
    fun drawTheServersSection() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        val out = File("build/shots").apply { mkdirs() }
        FakeServer().use { home ->
            FakeServer().use { work ->
                FakeServer().use { friend ->
                    library(home, "octo", 1_204, "0.9.3")
                    library(work, "navidrome", 312, "0.58.0")
                    library(friend, "navidrome", 80, "0.57.1")
                    val settings = SettingsStore(File(folder.root, "settings.json"))
                    settings.update { it.copy(lyrics = it.lyrics.copy(online = false)) }
                    val http = OkHttpClient()
                    val accounts = Accounts(settings, SessionOnlySecrets(), http)
                    val player = SilentPlayer()
                    lateinit var app: AppState
                    val signedIn = runBlocking { accounts.signIn(SignInRequest(home.address, "winters", "pw")) } as SignInOutcome.Done
                    accounts.rename(signedIn.connection.server.id, "Home")
                    SwingUtilities.invokeAndWait {
                        app = AppState(settings, accounts, http, CoroutineScope(SupervisorJob() + Dispatchers.Main), DesktopOs.Windows, player, OnlineLyrics(http, home.address.toHttpUrl()), restored = accounts.restore(), listeningRoot = File(folder.root, "listening"))
                        app.navigator.go(Page.Settings)
                    }
                    fun scene(width: Int, height: Int) = ImageComposeScene(width, height, Density(1f)) {
                        CompositionLocalProvider(LocalTyping provides TypingState()) { Shell(app, null) {} }
                    }
                    val scene = scene(1440, 900)
                    fun shot(name: String?, settleMs: Long = 1_500, on: ImageComposeScene = scene) {
                        val begin = System.currentTimeMillis()
                        var t = 0L
                        while (System.currentTimeMillis() < begin + settleMs) {
                            SwingUtilities.invokeAndWait { on.render(t) }
                            Thread.sleep(30)
                            t = (System.currentTimeMillis() - begin) * 1_000_000
                        }
                        val image = on.render(t)
                        if (name != null) File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                    }
                    shot("servers-one", 3_000)

                    // Two more: work, and a friend's server signed out of.
                    val workId = (runBlocking { accounts.add(SignInRequest(work.address, "brandon", "pw")) } as SignInOutcome.Saved).server.id
                    accounts.rename(workId, "Work")
                    val friendId = (runBlocking { accounts.add(SignInRequest(friend.address, "winters", "pw")) } as SignInOutcome.Saved).server.id
                    accounts.rename(friendId, "Sam's server")
                    runBlocking { accounts.signOut(friendId) }
                    shot("servers-three", 3_000)
                    val wide = scene(1980, 900)
                    shot("servers-three-wide", 2_000, wide)
                    wide.close()

                    // Its menu, opened from the dots.
                    tap(scene, need(scene.node("More for Work")))
                    shot("servers-menu")
                    SwingUtilities.invokeAndWait { app.popups.close() }

                    SwingUtilities.invokeAndWait { addServer(app) }
                    shot("servers-add")
                    SwingUtilities.invokeAndWait { app.popups.close() }
                    SwingUtilities.invokeAndWait { editServer(app, accounts.find(workId)!!) }
                    shot("servers-edit")
                    SwingUtilities.invokeAndWait { app.popups.close() }
                    SwingUtilities.invokeAndWait { changePassword(app) }
                    shot("servers-password")
                    // The form says what is wrong once it is tried.
                    tap(scene, need(scene.node("Change password", last = true)))
                    shot("servers-password-empty")
                    SwingUtilities.invokeAndWait { app.popups.close() }
                    SwingUtilities.invokeAndWait { askToRemove(app, accounts.find(friendId)!!) }
                    shot("servers-remove")
                    SwingUtilities.invokeAndWait { app.popups.close() }

                    // Music playing on Home, then a switch to Work, which
                    // takes a moment to answer.
                    SwingUtilities.invokeAndWait { app.library?.index?.songs?.let { app.play(it, 3) } }
                    work.answerBy("ping") {
                        Thread.sleep(2_500)
                        """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.58.0","openSubsonic":true}}"""
                    }
                    SwingUtilities.invokeAndWait { app.switchTo(workId, Page.Settings) }
                    shot("servers-switching", 1_000)
                    shot("servers-switched", 4_000)

                    // Signed out of Work: the sign-in page offers the others.
                    SwingUtilities.invokeAndWait { app.signOut() }
                    shot("signin-other-servers", 2_500)
                    scene.close()
                }
            }
        }
    }

    private fun find(node: SemanticsNode, text: String, found: MutableList<SemanticsNode>) {
        val words = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
        val described = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        if (words == text || described == text) found += node
        node.children.forEach { find(it, text, found) }
    }

    private fun ImageComposeScene.node(text: String, last: Boolean = false): SemanticsNode? {
        val found = mutableListOf<SemanticsNode>()
        semanticsOwners.forEach { find(it.unmergedRootSemanticsNode, text, found) }
        return if (last) found.lastOrNull() else found.firstOrNull()
    }

    private fun need(node: SemanticsNode?): SemanticsNode = node ?: throw AssertionError("not on screen")

    private fun tap(scene: ImageComposeScene, node: SemanticsNode) {
        val spot = node.boundsInRoot.center
        listOf(PointerEventType.Move, PointerEventType.Press, PointerEventType.Release).forEach { type ->
            SwingUtilities.invokeAndWait {
                scene.sendPointerEvent(type, spot, buttons = PointerButtons(isPrimaryPressed = type == PointerEventType.Press), button = PointerButton.Primary)
                scene.render()
            }
        }
    }
}

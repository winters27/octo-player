package app.winters.octo.desktop.update

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import app.winters.octo.design.LocalTyping
import app.winters.octo.design.TypingState
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.ui.Shell
import app.winters.octo.update.PlayerApp
import app.winters.octo.update.PlayerUpdater
import app.winters.octo.update.PlayerVersion
import app.winters.octo.update.ReleaseFeed
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Settings > About with an update ready, and the dot beside Settings in
// the sidebar, wide and folded to the rail. Only when asked:
// OCTO_SHOTS=1 ./gradlew :desktop:test --tests '*UpdateShotsTest*'.
// Saved as build/shots/update-*.png. The releases come from a pretend
// GitHub on this machine; nothing is installed.
class UpdateShotsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun drawAnUpdateReady() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        val out = File("build/shots").apply { mkdirs() }
        FakeReleases().use { github ->
            FakeServer().use { server ->
                github.publish("1.2.0", "- Octo keeps itself up to date, from its releases on GitHub.\n- Lyrics find their place faster after a seek.\n- The queue remembers where it was after a restart.")
                server.answer("ping", type = "octo")
                server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[]""", type = "octo")
                server.answer("getAlbumList2", """"albumList2":{"album":[]}""")
                server.answer("getArtists", """"artists":{"index":[]}""")
                server.answer("search3", """"searchResult3":{}""")
                server.answer("getStarred2", """"starred2":{}""")
                server.answer("getPlaylists", """"playlists":{"playlist":[]}""")

                val settings = SettingsStore(File(folder.root, "settings.json"))
                settings.update { it.copy(lyrics = it.lyrics.copy(online = false)) }
                val http = OkHttpClient()
                val accounts = Accounts(settings, SessionOnlySecrets(), http)
                val running = PlayerVersion.parse("1.1.0")!!
                val downloads = folder.newFolder("updates")
                val updater = PlayerUpdater(
                    PlayerApp.Desktop, running,
                    ReleaseFeed(http, File(downloads, "releases.json"), "Octo test", apiBase = github.api),
                    http, downloads, listOf(github.publicKey),
                    pick = { installerFor(it, DesktopOs.Windows, "x64", null) },
                )
                val updates = DesktopUpdates(settings, UpdaterAvailability.On(running), DesktopOs.Windows, downloads, updater, start = { error("nothing starts in a picture") })
                runBlocking { updates.check() }
                check(updates.ready != null)

                lateinit var app: AppState
                SwingUtilities.invokeAndWait {
                    app = AppState(settings, accounts, http, CoroutineScope(SupervisorJob() + Dispatchers.Main), DesktopOs.Windows, updates = updates)
                }
                val done = runBlocking { accounts.signIn(server.address, "winters", "pw") } as SignInOutcome.Done
                SwingUtilities.invokeAndWait { app.signedIn(done.connection) }
                val scene = ImageComposeScene(1440, 900, Density(1f)) {
                    CompositionLocalProvider(LocalTyping provides TypingState()) { Shell(app, null) {} }
                }
                fun draw(name: String?, settleMs: Long = 1_500) {
                    val begin = System.currentTimeMillis()
                    var t = 0L
                    while (System.currentTimeMillis() < begin + settleMs) {
                        SwingUtilities.invokeAndWait { scene.render(t).close() }
                        Thread.sleep(30)
                        t = (System.currentTimeMillis() - begin) * 1_000_000
                    }
                    val image = scene.render(t)
                    if (name != null) File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
                SwingUtilities.invokeAndWait { app.navigator.go(Page.Settings) }
                draw(null, 800)
                // To the foot of the page, where About is.
                repeat(200) { SwingUtilities.invokeAndWait { scene.sendPointerEvent(PointerEventType.Scroll, Offset(900f, 500f), scrollDelta = Offset(0f, 1f)) } }
                SwingUtilities.invokeAndWait { scene.sendPointerEvent(PointerEventType.Move, Offset(1400f, 880f)) }
                draw("update-ready-about")
                SwingUtilities.invokeAndWait { app.updateFrame { it.copy(sidebarRail = true) } }
                draw("update-ready-rail")
                scene.close()
            }
        }
    }
}

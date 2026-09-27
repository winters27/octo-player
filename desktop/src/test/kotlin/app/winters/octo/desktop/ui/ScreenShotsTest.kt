package app.winters.octo.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.SidePanel
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.songJson
import app.winters.octo.design.LocalTyping
import app.winters.octo.design.TypingState
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
import java.io.File
import javax.swing.SwingUtilities

// Draws the whole window off screen against a pretend server and saves
// pictures of the main pages, for looking at the layout without a display.
// It only runs when asked: OCTO_SHOTS=1 ./gradlew :desktop:test
class ScreenShotsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun drawTheMainPages() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        val out = File("build/shots").apply { mkdirs() }
        FakeServer().use { server ->
            val songs = (1..14).joinToString(",") { i ->
                songJson("s$i", listOf("Karma Police", "Airbag", "Lucky", "No Surprises", "Let Down", "Paranoid Android", "Subterranean Homesick Alien", "Exit Music", "Electioneering", "Climbing Up the Walls", "Fitter Happier", "The Tourist", "Everything In Its Right Place", "Idioteque")[i - 1], artist = if (i % 3 == 0) "Portishead" else "Radiohead", album = if (i < 8) "OK Computer" else "Kid A", albumId = if (i < 8) "a1" else "a2", duration = 180 + i * 7)
            }
            val albums = (1..12).joinToString(",") { """{"id":"a$it","name":"Album number $it","artist":"Radiohead","artistId":"r1","year":${1990 + it},"songCount":10}""" }
            server.answer("ping", type = "octo")
            server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[{"name":"songLyrics","versions":[1]},{"name":"octoAcquisitions","versions":[1]}]""", type = "octo")
            server.answer("getAlbumList2", """"albumList2":{"album":[$albums]}""")
            server.answer("getArtists", """"artists":{"index":[{"name":"R","artist":[{"id":"r1","name":"Radiohead","albumCount":9},{"id":"r2","name":"Portishead","albumCount":3}]}]}""")
            server.answer("search3", """"searchResult3":{"song":[$songs],"album":[{"id":"a1","name":"OK Computer","artist":"Radiohead"}],"artist":[{"id":"r1","name":"Radiohead"}]}""")
            server.answer("getPlaylists", """"playlists":{"playlist":[{"id":"p1","name":"Late night","songCount":12},{"id":"p2","name":"Running","songCount":40}]}""")
            server.answer("getAlbum", """"album":{"id":"a1","name":"OK Computer","artist":"Radiohead","artistId":"r1","year":1997,"songCount":7,"song":[$songs]}""")
            server.answer("getInternetRadioStations", """"internetRadioStations":{"internetRadioStation":[{"id":"st1","name":"Discover Weekly"},{"id":"st2","name":"Rock mix"}]}""")
            server.answer("getLyricsBySongId", """"lyricsList":{"structuredLyrics":[{"lang":"en","synced":true,"line":[{"start":0,"value":"Karma police"},{"start":4000,"value":"Arrest this man"},{"start":8000,"value":"He talks in maths"}]}]}""")

            val settings = SettingsStore(File(folder.root, "settings.json"))
            val http = OkHttpClient()
            val accounts = Accounts(settings, SessionOnlySecrets(), http)
            lateinit var app: AppState
            SwingUtilities.invokeAndWait { app = AppState(settings, accounts, http, CoroutineScope(SupervisorJob() + Dispatchers.Main), DesktopOs.Windows) }
            val scene = ImageComposeScene(1440, 900, Density(1f)) {
                CompositionLocalProvider(LocalTyping provides TypingState()) { Shell(app, null) {} }
            }
            fun shot(name: String, settleMs: Long = 1_500) {
                val end = System.currentTimeMillis() + settleMs
                var t = 0L
                while (System.currentTimeMillis() < end) {
                    SwingUtilities.invokeAndWait { scene.render(t) }
                    t += 16_000_000
                    Thread.sleep(30)
                }
                val image = scene.render(t)
                File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
            shot("signin", 500)
            val done = runBlocking { accounts.signIn(server.address, "winters", "pw") } as SignInOutcome.Done
            SwingUtilities.invokeAndWait { app.signedIn(done.connection) }
            shot("home", 3_000)
            SwingUtilities.invokeAndWait {
                val list = app.library?.index?.songs.orEmpty()
                app.play(list, 0)
                app.toggleSidePanel(SidePanel.Queue)
                app.navigator.go(Page.Songs)
            }
            shot("songs")
            SwingUtilities.invokeAndWait {
                app.toggleSidePanel(SidePanel.Lyrics)
                app.navigator.go(Page.Album("a1"))
            }
            shot("album")
            SwingUtilities.invokeAndWait {
                app.toggleSidePanel(SidePanel.Lyrics)
                app.navigator.go(Page.Albums)
            }
            shot("albums")
            SwingUtilities.invokeAndWait {
                app.navigator.go(Page.Search)
                app.search?.type("radiohead")
            }
            shot("search", 2_000)
            SwingUtilities.invokeAndWait {
                app.navigator.go(Page.Songs)
                app.popups.showAt(androidx.compose.ui.unit.IntOffset(700, 300)) { close -> SongMenu(app, app.library!!.index!!.songs.take(1), close) }
            }
            shot("menu")
            SwingUtilities.invokeAndWait {
                app.popups.close()
                app.navigator.go(Page.Settings)
            }
            shot("settings")
            scene.close()
        }
    }
}

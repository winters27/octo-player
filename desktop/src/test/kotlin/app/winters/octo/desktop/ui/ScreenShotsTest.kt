package app.winters.octo.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.audio.EnginePlayer
import app.winters.octo.desktop.audio.LocalOrServer
import app.winters.octo.desktop.audio.NativeAudioEngine
import app.winters.octo.desktop.audio.ServerSongs
import app.winters.octo.desktop.audio.writeSine
import app.winters.octo.desktop.lyrics.openLyricsMenu
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.SidePanel
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.CertificateQuestion
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
import app.winters.octo.lyrics.OnlineLyrics
import okhttp3.HttpUrl.Companion.toHttpUrl
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
// It only runs when asked: OCTO_SHOTS=1 ./gradlew :desktop:test. The
// player is the real audio engine on its silent device.
class ScreenShotsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun drawTheMainPages() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        val out = File("build/shots").apply { mkdirs() }
        FakeServer().use { server ->
            val songs = (1..14).joinToString(",") { i ->
                songJson("s$i", listOf("Karma Police", "Airbag", "Lucky", "No Surprises", "Let Down", "Paranoid Android", "Subterranean Homesick Alien", "Exit Music", "Electioneering", "Climbing Up the Walls", "Fitter Happier", "The Tourist", "Everything In Its Right Place", "Idioteque")[i - 1], artist = if (i % 3 == 0) "Portishead" else "Radiohead", album = if (i < 8) "OK Computer" else "Kid A", albumId = if (i < 8) "a1" else "a2", duration = 180 + i * 7).dropLast(1) +
                    ""","suffix":"flac","contentType":"audio/flac","bitDepth":24,"samplingRate":96000,"size":${48_000_000 + i * 1_000_000},"playCount":${i * 3},"genre":"Alternative","year":1997,"track":$i,"discNumber":1,"bpm":${70 + i},"created":"2026-08-0${1 + i % 9}T10:00:00Z","played":"2026-09-27T21:${10 + i}:00Z","replayGain":{"trackGain":-7.4,"trackPeak":0.998,"albumGain":-8.1,"albumPeak":1.0}}"""
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
            // The phone's queue, saved on the server, for Home's pick-up card.
            server.answer("getPlayQueue", """"playQueue":{"entry":[${songs}],"current":"s3","position":61000,"changed":"2026-09-28T10:00:00Z","changedBy":"Pixel 9"}""")
            server.answer("getLyricsBySongId", """"lyricsList":{"structuredLyrics":[{"lang":"en","synced":true,"line":[{"start":0,"value":"Karma police"},{"start":4000,"value":"Arrest this man"},{"start":8000,"value":"He talks in maths"}]}]}""")

            // A made-up cover, and a quiet tone for every song, so the engine
            // really plays (on its silent device) and the lyrics move.
            server.file("getCoverArt", madeUpCover())
            val tone = File(folder.root, "tone.wav").also { writeSine(it, seconds = 30) }
            server.file("stream", tone.readBytes())

            val settings = SettingsStore(File(folder.root, "settings.json"))
            // Never the real online lyrics library.
            settings.update { it.copy(lyrics = it.lyrics.copy(online = false)) }
            val http = OkHttpClient()
            val accounts = Accounts(settings, SessionOnlySecrets(), http)
            lateinit var app: AppState
            val player = EnginePlayer(NativeAudioEngine.open(silent = true), LocalOrServer(ServerSongs { app.connection?.client }))
            SwingUtilities.invokeAndWait {
                app = AppState(settings, accounts, http, CoroutineScope(SupervisorJob() + Dispatchers.Main), DesktopOs.Windows, player, OnlineLyrics(http, server.address.toHttpUrl()))
            }
            val scene = ImageComposeScene(1440, 900, Density(1f)) {
                CompositionLocalProvider(LocalTyping provides TypingState()) { Shell(app, null) {} }
            }
            fun shot(name: String, settleMs: Long = 1_500) {
                val begin = System.currentTimeMillis()
                val end = begin + settleMs
                // The scene's clock follows real time, so animations finish
                // however long a frame takes to draw off screen.
                var t = 0L
                while (System.currentTimeMillis() < end) {
                    SwingUtilities.invokeAndWait { scene.render(t) }
                    Thread.sleep(30)
                    t = (System.currentTimeMillis() - begin) * 1_000_000
                }
                val image = scene.render(t)
                File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
            // The sign-in, filled in; then asking about a certificate; then
            // with Advanced open.
            SwingUtilities.invokeAndWait {
                app.signInForm.typeAddress("192.168.1.20:4533")
                app.signInForm.username = "winters"
                app.signInForm.password = "pw"
            }
            shot("signin", 2_000)
            SwingUtilities.invokeAndWait {
                app.signInForm.typeAddress("music.example.com")
                app.signInForm.question = CertificateQuestion("music.example.com", "d693f076d6d65fcb023dd052e3637561a6772c9cb3c51f9799d3b94e3c655443")
            }
            shot("signin-certificate")
            SwingUtilities.invokeAndWait {
                app.popups.close()
                app.signInForm.result = null
                app.signInForm.advancedOpen = true
                app.signInForm.home = "192.168.1.20:4533"
                app.signInForm.addHeader()
                app.signInForm.setHeaderName(0, "X-Access-Token")
                app.signInForm.setHeaderValue(0, "secret")
            }
            shot("signin-advanced")
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
            // A song that would not play: the notice line, with details, and its row marked.
            SwingUtilities.invokeAndWait {
                val failed = app.library!!.index!!.songs[1]
                app.failedSongs[failed.id] = "That song isn't on the server any more."
                app.notice = "Skipped ${failed.title}. That song isn't on the server any more."
                app.noticeDetail = "HTTP 404 from the server for the song's address"
            }
            shot("failure")
            SwingUtilities.invokeAndWait {
                app.notice = null
                app.noticeDetail = null
            }
            SwingUtilities.invokeAndWait { app.showSidePanel(SidePanel.Info) }
            shot("info")
            SwingUtilities.invokeAndWait { app.sleep.start(30) }
            shot("sleep")
            SwingUtilities.invokeAndWait { app.sleep.cancel() }
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
                app.updateFrame { it.copy(sidebarRail = true) }
                app.navigator.go(Page.RecentlyAdded)
            }
            shot("rail")
            SwingUtilities.invokeAndWait { app.updateFrame { it.copy(sidebarRail = false) } }
            SwingUtilities.invokeAndWait {
                app.navigator.go(Page.Search)
                app.search?.type("radiohead")
            }
            shot("search", 2_000)
            SwingUtilities.invokeAndWait {
                app.navigator.go(Page.Songs)
                app.openSearch()
                app.search?.type("radiohead")
            }
            shot("omnibox", 2_000)
            SwingUtilities.invokeAndWait { app.search?.type(">sle") }
            shot("commands")
            SwingUtilities.invokeAndWait {
                app.search?.type("")
                app.omnibox.open = false
            }
            SwingUtilities.invokeAndWait {
                app.navigator.go(Page.Songs)
                app.popups.showAt(androidx.compose.ui.unit.IntOffset(700, 300)) { close -> SongMenu(app, app.library!!.index!!.songs.take(1), close) }
            }
            shot("menu")
            SwingUtilities.invokeAndWait {
                app.popups.showAt(androidx.compose.ui.unit.IntOffset(700, 300)) { close -> ArtistMenu(app, "r1", "Radiohead", null, close) }
            }
            shot("menu-artist")
            SwingUtilities.invokeAndWait {
                app.popups.close()
                app.navigator.go(Page.Settings)
            }
            shot("settings")
            SwingUtilities.invokeAndWait { app.navigator.go(Page.Sound) }
            shot("sound")
            SwingUtilities.invokeAndWait {
                app.navigator.go(Page.Songs)
                app.toggleSidePanel(SidePanel.Lyrics)
            }
            shot("lyrics", 2_500)
            SwingUtilities.invokeAndWait {
                openLyricsMenu(app, app.lyrics.state.value.song!!, androidx.compose.ui.unit.IntRect(1380, 60, 1412, 92))
            }
            shot("lyrics-menu")
            SwingUtilities.invokeAndWait {
                app.popups.close()
                app.fullPlayer = true
            }
            shot("player", 4_000)
            SwingUtilities.invokeAndWait { app.togglePlayerPanel(SidePanel.Queue) }
            shot("player-queue")
            scene.close()
            player.close()
        }
    }

    // A cover of soft coloured shapes, as a PNG.
    private fun madeUpCover(): ByteArray {
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(300, 300)
        val canvas = surface.canvas
        canvas.clear(0xFF1B2A4A.toInt())
        val paint = org.jetbrains.skia.Paint()
        paint.color = 0xFFE0703A.toInt()
        canvas.drawCircle(90f, 100f, 90f, paint)
        paint.color = 0xFF3AA6A0.toInt()
        canvas.drawCircle(220f, 200f, 110f, paint)
        paint.color = 0xFFF2D06B.toInt()
        canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(40f, 210f, 120f, 60f), paint)
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }
}

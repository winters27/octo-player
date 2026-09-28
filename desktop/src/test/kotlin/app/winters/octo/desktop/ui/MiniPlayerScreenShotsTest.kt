package app.winters.octo.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import app.winters.octo.design.LocalTyping
import app.winters.octo.design.TypingState
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.audio.EnginePlayer
import app.winters.octo.desktop.audio.LocalOrServer
import app.winters.octo.desktop.audio.NativeAudioEngine
import app.winters.octo.desktop.audio.ServerSongs
import app.winters.octo.desktop.audio.writeSine
import app.winters.octo.desktop.hotkeys.FakeHotkeys
import app.winters.octo.desktop.hotkeys.HotkeyAction
import app.winters.octo.desktop.hotkeys.KeyCombo
import app.winters.octo.desktop.library.LocalCovers
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.settings.AppPlaces
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.songJson
import app.winters.octo.desktop.system.LocalSystem
import app.winters.octo.desktop.system.MINI_HEIGHT
import app.winters.octo.desktop.system.MINI_PANEL_OPEN_HEIGHT
import app.winters.octo.desktop.system.MINI_PANEL_WIDTH
import app.winters.octo.desktop.system.MINI_SQUARE_HEIGHT
import app.winters.octo.desktop.system.MINI_SQUARE_WIDTH
import app.winters.octo.desktop.system.MINI_WIDTH
import app.winters.octo.desktop.system.MiniActions
import app.winters.octo.desktop.system.MiniPanel
import app.winters.octo.desktop.system.MiniPlayerView
import app.winters.octo.desktop.system.SystemIntegration
import app.winters.octo.lyrics.OnlineLyrics
import java.io.File
import javax.swing.SwingUtilities
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

// Pictures of the mini player in each of its shapes, and of its, Discord's
// and the global shortcuts' rows in Settings. Only when asked:
// OCTO_SHOTS=1 ./gradlew :desktop:test --tests '*ScreenShotsTest*'.
// Saved as build/shots/mini-*.png and settings-system-extras.png,
// settings-shortcuts.png. Nothing here reaches Discord or claims a key: the
// shortcuts are claimed from a pretend system, and Discord is never started.
class MiniPlayerScreenShotsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun drawTheMiniPlayer() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        val out = File("build/shots").apply { mkdirs() }
        // A build that carries a Discord application, so its rows show.
        System.setProperty("octo.discordAppId", "1234567890123456789")
        FakeServer().use { server ->
            val titles = listOf("Teardrop", "Angel", "Inertia Creeps", "Risingson", "Black Milk", "Dissolved Girl", "Group Four")
            val songs = titles.mapIndexed { i, title ->
                songJson("s${i + 1}", title, artist = "Massive Attack", album = "Mezzanine", albumId = "a1", duration = 300 + i * 17).dropLast(1) +
                    ""","coverArt":"al-a${1 + i % 3}","track":${i + 1},"suffix":"flac","path":"Massive Attack/Mezzanine/$title.flac"}"""
            }.joinToString(",")
            server.answer("ping", type = "octo")
            server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[{"name":"songLyrics","versions":[1]}]""", type = "octo")
            server.answer("getAlbumList2", """"albumList2":{"album":[{"id":"a1","name":"Mezzanine","artist":"Massive Attack","coverArt":"al-a1"}]}""")
            server.answer("getArtists", """"artists":{"index":[]}""")
            server.answer("search3", """"searchResult3":{"song":[$songs]}""")
            server.answer("getStarred2", """"starred2":{}""")
            server.answer("getPlaylists", """"playlists":{"playlist":[]}""")
            server.answer(
                "getLyricsBySongId",
                """"lyricsList":{"structuredLyrics":[{"lang":"en","synced":true,"line":[{"start":0,"value":"Love, love is a verb"},{"start":3000,"value":"Love is a doing word"},{"start":6000,"value":"Fearless on my breath"},{"start":9000,"value":"Gentle impulsion"},{"start":12000,"value":"Shakes me, makes me lighter"}]}]}""",
            )
            server.fileBy("getCoverArt") { cover(it.url.queryParameter("id").orEmpty()) }
            val tone = File(folder.root, "tone.wav").also { writeSine(it, seconds = 30, level = 0.0) }
            server.file("stream", tone.readBytes())

            val settings = SettingsStore(File(folder.root, "settings.json"))
            settings.update { it.copy(lyrics = it.lyrics.copy(online = false)) }
            val http = OkHttpClient()
            val accounts = Accounts(settings, SessionOnlySecrets(), http)
            lateinit var app: AppState
            val player = EnginePlayer(NativeAudioEngine.open(silent = true), LocalOrServer(ServerSongs { app.connection?.client }))
            SwingUtilities.invokeAndWait {
                app = AppState(settings, accounts, http, CoroutineScope(SupervisorJob() + Dispatchers.Main), DesktopOs.Windows, player, OnlineLyrics(http, server.address.toHttpUrl()), listeningRoot = File(folder.root, "listening"))
            }
            val done = runBlocking { accounts.signIn(server.address, "winters", "pw") } as SignInOutcome.Done
            SwingUtilities.invokeAndWait { app.signedIn(done.connection) }
            // Another app has Ctrl+Alt+Up, as a graphics driver might.
            val keys = FakeHotkeys(othersHave = setOf(KeyCombo.parse("Ctrl+Alt+Up")!!))
            lateinit var system: SystemIntegration
            SwingUtilities.invokeAndWait {
                system = SystemIntegration(app, AppPlaces(folder.newFolder("config"), folder.newFolder("cache")), DesktopOs.Windows, null, hotkeys = keys)
            }
            val actions = MiniActions(pin = {}, resize = { _, _ -> }, showPanel = {}, close = {})

            fun draw(scene: ImageComposeScene, name: String?, settleMs: Long = 1_500, pointer: Offset? = null) {
                pointer?.let { at -> SwingUtilities.invokeAndWait { scene.sendPointerEvent(PointerEventType.Move, at) } }
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
            fun mini(name: String, width: Float, height: Float, panel: MiniPanel? = null, onTop: Boolean = true, settleMs: Long = 2_000) {
                val scene = ImageComposeScene(width.toInt(), height.toInt(), Density(1f)) {
                    Provided(app, system) { MiniPlayerView(app, panel, onTop, actions) {} }
                }
                draw(scene, name, settleMs)
                scene.close()
            }

            mini("mini-bar-idle", MINI_WIDTH, MINI_HEIGHT)
            SwingUtilities.invokeAndWait {
                val list = app.library!!.index!!.songs.sortedBy { it.track }
                app.play(list, 0)
            }
            mini("mini-bar", MINI_WIDTH, MINI_HEIGHT, settleMs = 3_000)
            mini("mini-bar-wide", 560f, MINI_HEIGHT)
            mini("mini-cover", MINI_SQUARE_WIDTH, MINI_SQUARE_HEIGHT)
            mini("mini-cover-wide", 440f, 600f, onTop = false)
            mini("mini-lyrics", MINI_PANEL_WIDTH, MINI_PANEL_OPEN_HEIGHT, MiniPanel.Lyrics, settleMs = 3_000)
            mini("mini-queue", MINI_PANEL_WIDTH, MINI_PANEL_OPEN_HEIGHT, MiniPanel.Queue)
            // With the window's ambience off: plain dark glass.
            SwingUtilities.invokeAndWait { app.settings.update { it.copy(appearance = it.appearance.copy(ambientGlow = false)) } }
            mini("mini-bar-plain", MINI_WIDTH, MINI_HEIGHT)
            SwingUtilities.invokeAndWait { app.settings.update { it.copy(appearance = it.appearance.copy(ambientGlow = true)) } }

            // Settings: System's new groups, then Keyboard's global
            // shortcuts turned on, one refused by the system and one
            // listening for new keys.
            val scene = ImageComposeScene(1440, 900, Density(1f)) {
                Provided(app, system) { Shell(app, null) {} }
            }
            // A press and release on the list beside the page, then the
            // wheel over the page, a frame drawn after each step.
            fun tap(x: Float, y: Float) {
                listOf(PointerEventType.Move, PointerEventType.Press, PointerEventType.Release).forEach { type ->
                    SwingUtilities.invokeAndWait {
                        scene.sendPointerEvent(type, Offset(x, y), buttons = PointerButtons(isPrimaryPressed = type == PointerEventType.Press), button = PointerButton.Primary)
                        scene.render()
                    }
                }
            }
            fun wheel(clicks: Int) {
                repeat(clicks) {
                    SwingUtilities.invokeAndWait { scene.sendPointerEvent(PointerEventType.Scroll, Offset(900f, 500f), scrollDelta = Offset(0f, 1f)) }
                }
                SwingUtilities.invokeAndWait { scene.sendPointerEvent(PointerEventType.Move, Offset(1400f, 880f)) }
            }
            SwingUtilities.invokeAndWait { app.navigator.go(Page.Settings) }
            draw(scene, null, 800)
            tap(400f, SYSTEM_Y)
            draw(scene, null, 1_200)
            wheel(3)
            draw(scene, "settings-system-extras", 1_500)
            SwingUtilities.invokeAndWait {
                app.settings.update { it.copy(discord = it.discord.copy(on = true), hotkeys = it.hotkeys.copy(on = true)) }
                system.shortcuts.start()
            }
            draw(scene, "settings-system-extras-on", 1_500)
            tap(400f, KEYBOARD_Y)
            draw(scene, null, 1_200)
            wheel(40)
            SwingUtilities.invokeAndWait { system.shortcuts.record(HotkeyAction.Like) }
            draw(scene, "settings-shortcuts", 1_500)
            scene.close()
            system.shortcuts.close()
            player.close()
        }
        System.clearProperty("octo.discordAppId")
    }

    private companion object {
        // Where System and Keyboard are in the list beside the page, in the
        // 1440 by 900 window; found by looking at the pictures.
        const val SYSTEM_Y = 315f
        const val KEYBOARD_Y = 353f
    }

    @Composable
    private fun Provided(app: AppState, system: SystemIntegration, content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalTyping provides TypingState(),
            LocalSystem provides system,
            LocalCovers provides app.connection?.client,
            content = content,
        )
    }

    // A cover of two soft shapes, in colours of its own for each id.
    private fun cover(id: String): ByteArray {
        val palettes = listOf(
            listOf(0xFF1B2A4A, 0xFFE0703A, 0xFFF2D06B),
            listOf(0xFF2F5D3A, 0xFFB8C4C9, 0xFF6B3A7A),
            listOf(0xFF3AA6A0, 0xFF1B2A4A, 0xFFC0463F),
        ).map { list -> list.map { it.toInt() } }
        val colours = palettes[(id.hashCode() and 0x7fffffff) % palettes.size]
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(300, 300)
        val canvas = surface.canvas
        canvas.clear(colours[0])
        val paint = org.jetbrains.skia.Paint()
        paint.color = colours[1]
        canvas.drawCircle(110f, 120f, 90f, paint)
        paint.color = colours[2]
        canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(150f, 170f, 120f, 90f), paint)
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }
}

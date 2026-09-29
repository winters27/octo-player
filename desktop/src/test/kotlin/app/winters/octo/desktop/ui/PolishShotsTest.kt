package app.winters.octo.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import app.winters.octo.design.LocalTyping
import app.winters.octo.design.TypingState
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.SidePanel
import app.winters.octo.desktop.audio.EnginePlayer
import app.winters.octo.desktop.audio.LocalOrServer
import app.winters.octo.desktop.audio.NativeAudioEngine
import app.winters.octo.desktop.audio.ServerSongs
import app.winters.octo.desktop.audio.writeSine
import app.winters.octo.desktop.hotkeys.FakeHotkeys
import app.winters.octo.desktop.library.LocalCovers
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.settings.AppPlaces
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.system.LocalSystem
import app.winters.octo.desktop.system.MINI_HEIGHT
import app.winters.octo.desktop.system.MINI_SQUARE_HEIGHT
import app.winters.octo.desktop.system.MINI_SQUARE_WIDTH
import app.winters.octo.desktop.system.MINI_WIDTH
import app.winters.octo.desktop.system.MiniActions
import app.winters.octo.desktop.system.MiniPlayerView
import app.winters.octo.desktop.system.SystemIntegration
import app.winters.octo.livelists.LiveList
import app.winters.octo.lyrics.OnlineLyrics
import app.winters.octo.query.FilterPresets
import app.winters.octo.query.LibraryQuery
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

// The polish pass's pictures: the main pages at every window size the
// desktop has to look right at, then the song lists at 1, 100, 2,500 and
// 20,000 songs, then the empty and offline states. Only when asked:
// OCTO_SHOTS=1 ./gradlew :desktop:test --tests '*PolishShotsTest*'.
// Saved under build/shots/polish/<size>/<page>.png, data/<songs>-<page>-<size>.png
// and empty/<state>-<size>.png. OCTO_POLISH_SIZES and OCTO_POLISH_PAGES
// (lists split by commas) draw only those, for a quick look after a change.
class PolishShotsTest {
    @get:Rule val folder = TemporaryFolder()

    // A window size, in screen pixels, and the scale the screen runs at.
    enum class Size(val label: String, val width: Int, val height: Int, val density: Float) {
        Min("min", 960, 600, 1f),
        Small("small", 960, 640, 1f),
        Hd("1080p", 1920, 1080, 1f),
        Qhd("1440p", 2560, 1440, 1f),
        Ultrawide("ultrawide", 3440, 1440, 1f),
        K4At150("4k-150", 3840, 2160, 1.5f),
        K4At200("4k-200", 3840, 2160, 2f),
    }

    private val sizes = picked("OCTO_POLISH_SIZES", Size.entries) { it.label }
    private val pages: Set<String>? = System.getenv("OCTO_POLISH_PAGES")?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()

    private fun wants(page: String) = pages == null || page in pages

    @Test
    fun drawEveryPageAtEverySize() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        Rig(folder, PolishData.library(100)).use { rig ->
            val app = rig.app
            if (wants("signin")) {
                sizes.forEach { size ->
                    SwingUtilities.invokeAndWait {
                        app.signInForm.typeAddress("music.example.com")
                        app.signInForm.username = "winters"
                    }
                    rig.scene(size) { scene -> rig.shot(scene, "${size.label}/signin", 1_500) }
                }
            }
            rig.signIn()
            rig.playLong()
            val steps = matrixSteps(rig)
            for (size in sizes) {
                rig.scene(size) { scene ->
                    rig.shot(scene, null, 1_500)
                    for ((name, setUp) in steps) {
                        if (!wants(name)) continue
                        rig.reset(scene)
                        // Every page but those that play their own shows the long song.
                        if (rig.app.player.state.value.current?.song?.id != PolishData.LONG_SONG) rig.playLong()
                        setUp(scene)
                        rig.shot(scene, "${size.label}/$name", 900)
                    }
                    rig.reset(scene)
                }
            }
            if (wants("mini")) {
                for (density in listOf(1f, 1.5f, 2f)) {
                    rig.mini("mini/bar-x$density", MINI_WIDTH, MINI_HEIGHT, density)
                    rig.mini("mini/square-x$density", MINI_SQUARE_WIDTH, MINI_SQUARE_HEIGHT, density)
                }
                rig.mini("mini/bar-cjk", MINI_WIDTH, MINI_HEIGHT, 1f) { rig.play(PolishData.CJK_SONG) }
            }
        }
    }

    // Each page, set up from a clean window: its name and how to get there.
    private fun matrixSteps(rig: Rig): List<Pair<String, (ImageComposeScene) -> Unit>> {
        val app = rig.app
        fun go(page: Page) = SwingUtilities.invokeAndWait { app.navigator.go(page) }
        return listOf(
            "home" to { _ -> go(Page.Home) },
            "songs" to { _ -> go(Page.Songs) },
            "songs-scrolled" to { scene ->
                go(Page.Songs)
                rig.shot(scene, null, 600)
                rig.wheel(scene, 12)
            },
            // Played, hovered, picked and ringed rows, on a plain album.
            "rows-states" to { scene ->
                go(Page.Album(PolishData.PLAIN_ALBUM))
                rig.shot(scene, null, 900)
                rig.play(PolishData.PLAIN_ALBUM, 1)
                rig.clickText(scene, "Karma Police")
                rig.clickText(scene, "Fitter Happier", ctrl = true)
                rig.hoverText(scene, "Let Down")
            },
            // A cut title under the pointer long enough for its tooltip.
            "cut-title" to { scene ->
                go(Page.Album(PolishData.LONG_ALBUM))
                rig.shot(scene, null, 900)
                rig.hoverText(scene, "They Are Night Zombies!! They Are Neighbors!! They Have Come Back from the Dead!! Ahhhh!")
                rig.shot(scene, null, 900)
            },
            "album" to { _ -> go(Page.Album(PolishData.PLAIN_ALBUM)) },
            "album-discs" to { _ -> go(Page.Album(PolishData.DISCS_ALBUM)) },
            "album-long" to { _ -> go(Page.Album(PolishData.LONG_ALBUM)) },
            "album-scripts" to { _ -> go(Page.Album("al-script-3")) },
            "album-bare" to { _ -> go(Page.Album("al-rec")) },
            "artist" to { _ -> go(Page.Artist("ar-f-0", "Artist 1")) },
            "artist-long" to { _ -> go(Page.Artist("ar-sufjan", "Sufjan Stevens")) },
            "albums" to { _ -> go(Page.Albums) },
            "artists" to { _ -> go(Page.Artists) },
            "playlist-big" to { _ -> go(Page.Playlist(PolishData.BIG_PLAYLIST)) },
            "playlist" to { _ -> go(Page.Playlist(PolishData.SMALL_PLAYLIST)) },
            "livelist" to { _ -> go(Page.LiveList(rig.liveList.id)) },
            "health" to { _ -> go(Page.LibraryHealth) },
            "search" to { _ ->
                SwingUtilities.invokeAndWait {
                    app.navigator.go(Page.Songs)
                    app.openSearch()
                    app.search?.type("holo")
                }
            },
            "search-page" to { _ ->
                SwingUtilities.invokeAndWait {
                    app.navigator.go(Page.Search)
                    app.search?.type("ho")
                }
            },
            "queue" to { _ ->
                SwingUtilities.invokeAndWait {
                    app.navigator.go(Page.Songs)
                    app.showSidePanel(SidePanel.Queue)
                }
            },
            "lyrics" to { _ ->
                SwingUtilities.invokeAndWait {
                    app.navigator.go(Page.Songs)
                    app.showSidePanel(SidePanel.Lyrics)
                }
            },
            "info" to { _ ->
                SwingUtilities.invokeAndWait {
                    app.navigator.go(Page.Album(PolishData.LONG_ALBUM))
                    app.showSidePanel(SidePanel.Info)
                }
            },
            "player" to { _ -> SwingUtilities.invokeAndWait { app.fullPlayer = true } },
            "player-cjk" to { scene ->
                rig.play(PolishData.CJK_SONG)
                SwingUtilities.invokeAndWait { app.fullPlayer = true }
            },
            "settings" to { _ -> go(Page.Settings) },
            "sound" to { _ -> go(Page.Sound) },
            "menu" to { scene ->
                go(Page.Songs)
                SwingUtilities.invokeAndWait {
                    val song = app.library!!.index!!.songs.first { it.id == PolishData.LONG_SONG }
                    app.popups.showAt(IntOffset(scene.pxWidth() * 45 / 100, scene.pxHeight() * 25 / 100)) { close -> SongMenu(app, listOf(song), close) }
                }
            },
            "rail" to { _ ->
                SwingUtilities.invokeAndWait {
                    app.updateFrame { it.copy(sidebarRail = true) }
                    app.navigator.go(Page.Songs)
                }
            },
            "notice" to { _ ->
                SwingUtilities.invokeAndWait {
                    app.navigator.go(Page.Songs)
                    app.notice = "Skipped Come On! Feel the Illinoise! (Part I: The World's Columbian Exposition, Part II: Carl Sandburg Visits Me in a Dream). That song isn't on the server any more."
                    app.noticeDetail = "HTTP 404 from the server for the song's address"
                }
            },
        )
    }

    @Test
    fun drawTheLibrarySizes() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        val counts = picked("OCTO_POLISH_COUNTS", listOf(1, 100, 2_500, 20_000)) { "$it" }
        val here = sizes.filter { it == Size.Hd || it == Size.Small }
        for (count in counts) {
            Rig(folder, PolishData.library(count)).use { rig ->
                rig.signIn(settleMs = if (count > 5_000) 8_000 else 3_000)
                val app = rig.app
                rig.playLong()
                for (size in here) {
                    rig.scene(size) { scene ->
                        rig.shot(scene, null, 1_500)
                        for (page in listOf("home", "songs", "albums", "artists", "playlist")) {
                            if (!wants(page)) continue
                            rig.reset(scene)
                            SwingUtilities.invokeAndWait {
                                app.navigator.go(
                                    when (page) {
                                        "home" -> Page.Home
                                        "songs" -> Page.Songs
                                        "albums" -> Page.Albums
                                        "artists" -> Page.Artists
                                        else -> Page.Playlist(PolishData.BIG_PLAYLIST)
                                    },
                                )
                            }
                            rig.shot(scene, "data/$count-$page-${size.label}", 1_200)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun drawTheEmptyStates() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        val here = sizes.filter { it == Size.Hd || it == Size.Small }
        // No library at all, nothing playing.
        Rig(folder, PolishData.library(0)).use { rig ->
            rig.signIn()
            for (size in here) rig.scene(size) { scene ->
                for (page in listOf(Page.Home, Page.Songs, Page.Albums, Page.Artists, Page.Favourites, Page.LibraryHealth, Page.Genres)) {
                    rig.reset(scene)
                    SwingUtilities.invokeAndWait { rig.app.navigator.go(page) }
                    rig.shot(scene, "empty/no-library-${page.javaClass.simpleName.lowercase()}-${size.label}", 1_200)
                }
                rig.reset(scene)
                SwingUtilities.invokeAndWait { rig.app.showSidePanel(SidePanel.Queue) }
                rig.shot(scene, "empty/no-queue-${size.label}", 900)
                SwingUtilities.invokeAndWait { rig.app.showSidePanel(SidePanel.Lyrics) }
                rig.shot(scene, "empty/no-lyrics-${size.label}", 900)
                rig.reset(scene)
            }
        }
        Rig(folder, PolishData.library(100)).use { rig ->
            rig.signIn()
            val app = rig.app
            for (size in here) rig.scene(size) { scene ->
                rig.reset(scene)
                SwingUtilities.invokeAndWait { app.navigator.go(Page.Home) }
                rig.shot(scene, "empty/nothing-playing-${size.label}", 1_200)
                SwingUtilities.invokeAndWait { app.navigator.go(Page.Playlist(PolishData.EMPTY_PLAYLIST)) }
                rig.shot(scene, "empty/empty-playlist-${size.label}", 1_200)
                SwingUtilities.invokeAndWait {
                    app.navigator.go(Page.Songs)
                    app.openSearch()
                    app.search?.type("zzqx")
                }
                rig.shot(scene, "empty/no-results-${size.label}", 1_500)
                rig.reset(scene)
                SwingUtilities.invokeAndWait {
                    app.navigator.go(Page.Search)
                    app.search?.type("zzqx")
                }
                rig.shot(scene, "empty/no-results-page-${size.label}", 1_500)
                SwingUtilities.invokeAndWait {
                    app.navigator.go(Page.Songs)
                    app.navigator.keepFilter(app.navigator.current, LibraryQuery(listOf(FilterPresets.NeverPlayed), text = "zzqx"))
                }
                rig.shot(scene, "empty/no-matches-${size.label}", 1_200)
                rig.reset(scene)
            }
            // The server gone: pages not yet loaded, then the whole library.
            rig.server.close()
            for (size in here) rig.scene(size) { scene ->
                rig.reset(scene)
                SwingUtilities.invokeAndWait { app.navigator.go(Page.Album(PolishData.DISCS_ALBUM)) }
                rig.shot(scene, "empty/offline-album-${size.label}", 2_500)
                SwingUtilities.invokeAndWait { app.navigator.go(Page.Playlist(PolishData.SMALL_PLAYLIST)) }
                rig.shot(scene, "empty/offline-playlist-${size.label}", 2_500)
                SwingUtilities.invokeAndWait { app.navigator.go(Page.Favourites) }
                rig.shot(scene, "empty/offline-favourites-${size.label}", 2_500)
                SwingUtilities.invokeAndWait { app.navigator.go(Page.Home) }
                rig.shot(scene, "empty/offline-home-${size.label}", 2_500)
            }
        }
        // A server that cannot list its songs.
        FakeServer().use { server ->
            Rig(folder, PolishData.library(100), server).use { rig ->
                server.answerBy("search3") { server.failed(0, "The server is busy") }
                server.answerBy("getAlbumList2") { server.failed(0, "The server is busy") }
                rig.signIn()
                for (size in here) rig.scene(size) { scene ->
                    rig.reset(scene)
                    SwingUtilities.invokeAndWait { rig.app.navigator.go(Page.Songs) }
                    rig.shot(scene, "empty/library-failed-${size.label}", 1_500)
                }
            }
        }
    }

    // One app against a pretend server serving `library`, with the real
    // engine on its silent device.
    internal class Rig(folder: TemporaryFolder, library: FakeLibrary, given: FakeServer? = null) : AutoCloseable {
        val server = given ?: FakeServer()
        private val out = File("build/shots/polish").apply { mkdirs() }
        private val settings = SettingsStore(File(folder.newFolder(), "settings.json"))
        private val http = OkHttpClient()
        private val accounts = Accounts(settings, SessionOnlySecrets(), http)
        lateinit var app: AppState
        private val player: EnginePlayer
        private val songs = library.songs
        lateinit var liveList: LiveList
        private val root = folder.newFolder()

        init {
            val tone = File(root, "tone.wav").also { writeSine(it, seconds = 30, level = 0.0) }
            PolishData.serve(server, library, tone.readBytes())
            settings.update { it.copy(lyrics = it.lyrics.copy(online = false)) }
            player = EnginePlayer(NativeAudioEngine.open(silent = true), LocalOrServer(ServerSongs { app.connection?.client }))
            SwingUtilities.invokeAndWait {
                app = AppState(settings, accounts, http, CoroutineScope(SupervisorJob() + Dispatchers.Main), DesktopOs.Windows, player, OnlineLyrics(http, server.address.toHttpUrl()), listeningRoot = File(root, "listening"))
            }
        }

        fun signIn(settleMs: Long = 3_000) {
            val done = runBlocking { accounts.signIn(server.address, "winters", "pw") } as SignInOutcome.Done
            SwingUtilities.invokeAndWait {
                app.signedIn(done.connection)
                liveList = app.liveLists.save(LiveList.new("Songs from everywhere, in every script, for a long drive", LibraryQuery(listOf(FilterPresets.genre("World"))), System.currentTimeMillis()))
                app.failedSongs[PolishData.MISSING_FILE] = "That song isn't on the server any more."
            }
            // Until the library is read.
            val end = System.currentTimeMillis() + 60_000
            while (app.library?.index == null && songs.isNotEmpty() && System.currentTimeMillis() < end) Thread.sleep(50)
            Thread.sleep(settleMs / 10)
        }

        // Plays the song with the longest title, the player's hardest case.
        fun playLong() = play(PolishData.LONG_SONG)

        // Plays one song by id, or an album from a place in it.
        fun play(id: String, from: Int = 0) {
            SwingUtilities.invokeAndWait {
                val index = app.library?.index ?: return@invokeAndWait
                val album = index.songs.filter { it.albumId == id }.sortedWith(compareBy({ it.discNumber }, { it.track }))
                if (album.isNotEmpty()) app.play(album, from) else index.songs.firstOrNull { it.id == id }?.let { app.play(listOf(it)) }
            }
        }

        fun scene(size: Size, draw: (ImageComposeScene) -> Unit) {
            val scene = ImageComposeScene(size.width, size.height, Density(size.density)) {
                CompositionLocalProvider(LocalTyping provides TypingState()) { Shell(app, null) {} }
            }
            try {
                draw(scene)
            } finally {
                scene.close()
            }
        }

        // Back to a plain window between pages.
        fun reset(scene: ImageComposeScene) {
            SwingUtilities.invokeAndWait {
                app.popups.close()
                app.search?.type("")
                app.omnibox.open = false
                app.fullPlayer = false
                app.showSidePanel(null)
                app.notice = null
                app.noticeDetail = null
                app.updateFrame { it.copy(sidebarRail = false) }
                scene.sendPointerEvent(PointerEventType.Move, Offset(scene.pxWidth() / 2f, 10f))
            }
        }

        // Draws for a while, the scene's clock following real time, and
        // saves the picture; with no name, only draws.
        fun shot(scene: ImageComposeScene, name: String?, settleMs: Long) {
            val begin = System.currentTimeMillis()
            var t = 0L
            while (System.currentTimeMillis() < begin + settleMs) {
                SwingUtilities.invokeAndWait { scene.render(t).close() }
                Thread.sleep(30)
                t = (System.currentTimeMillis() - begin) * 1_000_000
            }
            val image = scene.render(t)
            if (name != null) File(out, "$name.png").apply { parentFile.mkdirs() }.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            image.close()
        }

        fun wheel(scene: ImageComposeScene, clicks: Int) {
            repeat(clicks) {
                SwingUtilities.invokeAndWait { scene.sendPointerEvent(PointerEventType.Scroll, Offset(scene.pxWidth() / 2f, scene.pxHeight() / 2f), scrollDelta = Offset(0f, 1f)) }
            }
            SwingUtilities.invokeAndWait { scene.sendPointerEvent(PointerEventType.Move, Offset(scene.pxWidth() / 2f, 10f)) }
        }

        private fun centreOf(scene: ImageComposeScene, text: String): Offset? {
            var found: Offset? = null
            SwingUtilities.invokeAndWait {
                found = scene.semanticsOwners.firstNotNullOfOrNull { findText(it.unmergedRootSemanticsNode, text) }?.boundsInRoot?.center
            }
            return found
        }

        fun hoverText(scene: ImageComposeScene, text: String) {
            val at = centreOf(scene, text) ?: return
            SwingUtilities.invokeAndWait {
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.render()
            }
        }

        fun clickText(scene: ImageComposeScene, text: String, ctrl: Boolean = false) {
            val at = centreOf(scene, text) ?: return
            val keys = PointerKeyboardModifiers(isCtrlPressed = ctrl)
            listOf(PointerEventType.Move, PointerEventType.Press, PointerEventType.Release).forEach { type ->
                SwingUtilities.invokeAndWait {
                    scene.sendPointerEvent(type, at, buttons = PointerButtons(isPrimaryPressed = type == PointerEventType.Press), keyboardModifiers = keys, button = PointerButton.Primary)
                    scene.render()
                }
            }
            // Past the double click's time, so the next click is its own.
            Thread.sleep(DOUBLE_CLICK_MS + 50)
        }

        // The mini player at a size, on a screen at `density`.
        fun mini(name: String, width: Float, height: Float, density: Float, before: () -> Unit = {}) {
            before()
            val system = runOnUi { SystemIntegration(app, AppPlaces(File(root, "config").apply { mkdirs() }, File(root, "cache").apply { mkdirs() }), DesktopOs.Windows, null, hotkeys = FakeHotkeys()) }
            val actions = MiniActions(pin = {}, resize = { _, _ -> }, showPanel = {}, close = {})
            val scene = ImageComposeScene((width * density).toInt(), (height * density).toInt(), Density(density)) {
                MiniProvided(app, system) { MiniPlayerView(app, null, true, actions) {} }
            }
            shot(scene, name, 1_500)
            scene.close()
            system.shortcuts.close()
        }

        override fun close() {
            player.close()
            server.close()
        }
    }

    private fun <T> picked(variable: String, all: List<T>, label: (T) -> String): List<T> {
        val names = System.getenv(variable)?.split(",")?.map(String::trim)?.filter(String::isNotEmpty) ?: return all
        return all.filter { label(it) in names }
    }
}

@Composable
private fun MiniProvided(app: AppState, system: SystemIntegration, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalTyping provides TypingState(),
        LocalSystem provides system,
        LocalCovers provides app.connection?.client,
        content = content,
    )
}

private fun <T> runOnUi(make: () -> T): T {
    var made: T? = null
    SwingUtilities.invokeAndWait { made = make() }
    @Suppress("UNCHECKED_CAST")
    return made as T
}

private fun ImageComposeScene.pxWidth(): Int = constraints.maxWidth

private fun ImageComposeScene.pxHeight(): Int = constraints.maxHeight

// The node showing exactly this text.
private fun findText(node: SemanticsNode, text: String): SemanticsNode? {
    if (node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } == text) return node
    return node.children.firstNotNullOfOrNull { findText(it, text) }
}

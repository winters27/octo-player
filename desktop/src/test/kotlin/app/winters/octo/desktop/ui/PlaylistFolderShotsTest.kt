package app.winters.octo.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.songJson
import app.winters.octo.design.LocalTyping
import app.winters.octo.design.TypingState
import app.winters.octo.lyrics.OnlineLyrics
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Pictures of playlist folders and the Library upkeep group in the sidebar,
// for the look of every state without a display: folders closed and open,
// the upkeep group closed with its note and open, a drag of playlists held
// over a folder, the folder menu and its delete question, Move to folder,
// the chooser in its groups, the rail's folder pop-up, and a playlist that
// holds a song found online. Only when asked: OCTO_SHOTS=1.
class PlaylistFolderShotsTest {
    @get:Rule val folder = TemporaryFolder()

    private val road = "pfRoadTripsAAAAAAAAAAA"
    private val summer = "pfSummerAAAAAAAAAAAAAA"
    private val work = "pfWorkAAAAAAAAAAAAAAAA"

    @Test
    fun drawFoldersAndUpkeep() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        val out = File("build/shots").apply { mkdirs() }
        FakeServer().use { server ->
            val at = "2026-11-14T10:00:00.000Z"
            fun row(id: String, name: String, songs: Int, extra: String = "") =
                """{"id":"$id","name":"$name","owner":"winters","songCount":$songs,"duration":${songs * 210},"coverArt":"pl-$id","changed":"$at"$extra}"""
            server.answer(
                "getPlaylists",
                """"playlists":{"playlist":[""" + listOf(
                    row("p1", "Late night", 12),
                    row("p2", "Running", 40),
                    row("p3", "Beach days", 18),
                    row("p4", "Focus", 25),
                    row("p5", "Dinner with friends", 31),
                    row("p6", "Found this week", 9),
                    row("r1", "Review", 3, ""","octoNotice":"review","octoUpkeep":true,"octoUpkeepKind":"notice""""),
                    row("d1", "Duplicates", 1, ""","octoNotice":"duplicates","octoUpkeep":true,"octoUpkeepKind":"notice""""),
                    row("a1", "🛠 Fix tags", 0, ""","octoUpkeep":true,"octoUpkeepKind":"action""""),
                    """{"id":"og1","name":"New Releases","songCount":24,"coverArt":"og1","readonly":true,"octoList":"newReleases"}""",
                ).joinToString(",") + "]}",
            )
            val folders = """"playlistFolders":{"changed":"$at","folder":[""" +
                """{"id":"$road","name":"Road trips","created":"$at"},""" +
                """{"id":"$summer","name":"Summer","parent":"$road","created":"$at"},""" +
                """{"id":"$work","name":"Work","created":"$at"}],""" +
                """"place":[{"playlist":"p1","folder":"$road"},{"playlist":"p3","folder":"$summer"},{"playlist":"p4","folder":"$work"}]}"""
            server.answer("getPlaylistFolders", folders)
            // A drop answers the folders as they were, so no failure line stays on screen.
            server.answer("setPlaylistFolder", folders)
            val songs = (1..6).joinToString(",") { songJson("s$it", "Song $it", artist = "Radiohead", album = "OK Computer", albumId = "a1", duration = 200 + it) }
            val found = songJson("ext-deezer-song-9", "Streetlight", artist = "Night Drive", album = "Lights", albumId = "ext-deezer-album-3", duration = 241).dropLast(1) + ""","isExternal":true}"""
            server.answer("getPlaylist", """"playlist":{"id":"p6","name":"Found this week","owner":"winters","songCount":7,"changed":"$at","entry":[$songs,$found]}""")
            server.fileBy("getCoverArt") { cover(it.url.queryParameter("id").orEmpty()) }

            val settings = SettingsStore(File(folder.root, "settings.json"))
            settings.update { it.copy(lyrics = it.lyrics.copy(online = false)) }
            val http = OkHttpClient()
            lateinit var app: AppState
            SwingUtilities.invokeAndWait {
                app = AppState(
                    settings,
                    Accounts(settings, SessionOnlySecrets(), http),
                    http,
                    CoroutineScope(SupervisorJob() + Dispatchers.Main),
                    DesktopOs.Windows,
                    SilentPlayer(),
                    OnlineLyrics(http, server.address.toHttpUrl()),
                    restored = server.connection(listOf("octoPlaylistSongs:1", "octoPlaylistFolders:1")),
                )
            }
            // Tall enough that the whole sidebar shows under the library's groups.
            val scene = ImageComposeScene(1440, 1400, Density(1f)) {
                CompositionLocalProvider(LocalTyping provides TypingState()) { Shell(app, null) {} }
            }

            fun shot(name: String?, settleMs: Long = 1_200) {
                val begin = System.currentTimeMillis()
                var t = 0L
                while (System.currentTimeMillis() < begin + settleMs) {
                    SwingUtilities.invokeAndWait { scene.render(t) }
                    Thread.sleep(30)
                    t = (System.currentTimeMillis() - begin) * 1_000_000
                }
                val image = scene.render(t)
                if (name != null) File(out, "folders-$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
            fun centerOf(words: String): Offset? {
                var spot: Offset? = null
                SwingUtilities.invokeAndWait {
                    spot = scene.semanticsOwners.firstNotNullOfOrNull { find(it.unmergedRootSemanticsNode, words) }?.boundsInRoot?.center
                }
                return spot
            }

            SwingUtilities.invokeAndWait { app.navigator.go(Page.Playlist("p6")) }
            shot(null, 2_500)
            shot("sidebar-closed")
            SwingUtilities.invokeAndWait { app.updateFrame { it.copy(openFolders = setOf(road, summer, work), upkeepOpen = true) } }
            shot("sidebar-open")

            // Late night picked, then held over Work, before it is let go.
            val from = centerOf("Running")
            val onto = centerOf("Work, playlist folder, 1 playlist")
            if (from != null && onto != null) {
                SwingUtilities.invokeAndWait {
                    scene.sendPointerEvent(PointerEventType.Enter, from)
                    scene.sendPointerEvent(PointerEventType.Press, from, buttons = PointerButtons(isPrimaryPressed = true))
                }
                for (step in 1..12) {
                    val p = Offset(from.x + (onto.x - from.x) * step / 12f, from.y + (onto.y - from.y) * step / 12f)
                    SwingUtilities.invokeAndWait { scene.sendPointerEvent(PointerEventType.Move, p, buttons = PointerButtons(isPrimaryPressed = true)) }
                    shot(null, 60)
                }
                shot("drag-onto-folder", 400)
                SwingUtilities.invokeAndWait { scene.sendPointerEvent(PointerEventType.Release, onto) }
                shot(null, 300)
            }

            SwingUtilities.invokeAndWait { app.popups.showAt(IntOffset(200, 260)) { close -> FolderMenu(app, road, close) } }
            shot("folder-menu")
            centerOf("Delete folder")?.let { spot ->
                SwingUtilities.invokeAndWait {
                    scene.sendPointerEvent(PointerEventType.Enter, spot)
                    scene.sendPointerEvent(PointerEventType.Press, spot, buttons = PointerButtons(isPrimaryPressed = true))
                    scene.render()
                    scene.sendPointerEvent(PointerEventType.Release, spot)
                }
            }
            shot("folder-delete")
            SwingUtilities.invokeAndWait {
                app.popups.close()
                app.popups.showAt(IntOffset(200, 300)) { close -> MoveToFolderMenu(app, app.playlists.filter { it.id == "p1" }, close) { close() } }
            }
            shot("move-to-folder")
            SwingUtilities.invokeAndWait {
                app.popups.close()
                app.popups.showAt(IntOffset(560, 200), width = null) { close ->
                    PlaylistChooser(app, { emptyList() }, close, forFind = true) { close() }
                }
            }
            shot("chooser-find")
            SwingUtilities.invokeAndWait {
                app.popups.close()
                app.popups.showAt(IntOffset(560, 200), width = null) { close ->
                    PlaylistChooser(app, { emptyList() }, close, forFind = false) { close() }
                }
            }
            shot("chooser-library")
            SwingUtilities.invokeAndWait { app.popups.close() }
            shot("playlist-with-find", 1_000)
            SwingUtilities.invokeAndWait {
                app.updateFrame { it.copy(sidebarRail = true) }
            }
            shot(null, 800)
            SwingUtilities.invokeAndWait { app.popups.showAt(IntOffset(70, 300)) { close -> FolderContents(app, road, close) } }
            shot("rail-folder-popup")
            SwingUtilities.invokeAndWait { app.popups.close() }
            scene.close()
        }
    }

    // The node showing exactly this text, or described by it.
    private fun find(node: SemanticsNode, text: String): SemanticsNode? {
        if (node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } == text) return node
        if (node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() == text) return node
        return node.children.firstNotNullOfOrNull { find(it, text) }
    }

    // A plain made-up cover in one of a few colors.
    private fun cover(id: String): ByteArray {
        val palette = listOf(0xFF1B2A4A, 0xFFE0703A, 0xFF3AA6A0, 0xFFF2D06B, 0xFF6B3A7A, 0xFF2F5D3A).map { it.toInt() }
        val seed = id.hashCode() and 0x7fffffff
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(300, 300)
        surface.canvas.clear(palette[seed % palette.size])
        val paint = org.jetbrains.skia.Paint().apply { color = palette[(seed / 7 + 1) % palette.size] }
        surface.canvas.drawCircle(150f, 150f, 90f + seed % 40, paint)
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }
}

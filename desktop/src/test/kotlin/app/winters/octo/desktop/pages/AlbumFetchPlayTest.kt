package app.winters.octo.desktop.pages

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.audio.ServerSongs
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.player.DesktopPlayer
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.search.FetchPhase
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.songJson
import app.winters.octo.lyrics.OnlineLyrics
import app.winters.octo.playback.QueueSource
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// An album found online, every song asked for with "Add all", then a song
// whose check has come on played from its row, on the real Album page
// against a pretend Octo server. Brandon: once a song had its check,
// pressing play on it did nothing. It plays, and from the library's copy.
@OptIn(ExperimentalComposeUiApi::class)
class AlbumFetchPlayTest {
    @get:Rule val temp = TemporaryFolder()

    private val server = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val silent = SilentPlayer()

    private val plays = mutableListOf<Pair<List<String>, Int>>()
    private val player = object : DesktopPlayer by silent {
        override fun play(songs: List<Song>, start: Int, shuffle: Boolean, source: QueueSource) {
            plays += songs.map { it.id } to start
            silent.play(songs, start, shuffle, source)
        }
    }

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("waited too long", what())
    }

    private fun find(node: SemanticsNode, test: (SemanticsNode) -> Boolean): SemanticsNode? {
        if (test(node)) return node
        return node.children.firstNotNullOfOrNull { find(it, test) }
    }

    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }

    private fun outside(id: String, title: String) =
        songJson(id, title, artist = "Kavinsky", album = "OutRun", albumId = "ext-album").dropLast(1) + ""","isExternal":true}"""

    @Test
    fun aSongBroughtInPlaysFromItsRow() {
        var landed = false
        server.answer("getAlbumList2", """"albumList2":{"album":[]}""")
        server.answer("getArtists", """"artists":{"index":[]}""")
        server.answer("getPlaylists", """"playlists":{"playlist":[]}""")
        // The library holds the songs under their own ids once they landed.
        server.answerBy("search3") {
            server.ok(""""searchResult3":{"song":[${if (landed) songJson("nd1", "Nightcall", "Kavinsky", "OutRun", "al-nd") + "," + songJson("nd2", "Rampage", "Kavinsky", "OutRun", "al-nd") else ""}]}""")
        }
        server.answer("getAlbum", """"album":{"id":"ext-album","name":"OutRun","artist":"Kavinsky","songCount":2,"duration":400,"song":[${outside("x1", "Nightcall")},${outside("x2", "Rampage")}]}""")
        server.answer("star")
        server.answerBy("getAcquisitions") {
            landed = true
            server.ok(""""acquisitions":{"acquisition":[{"id":"x1","title":"Nightcall","state":"done","libraryId":"nd1"},{"id":"x2","title":"Rampage","state":"done","libraryId":"nd2"}]}""")
        }
        val settings = SettingsStore(File(temp.root, "settings.json"), 0)
        val http = OkHttpClient()
        val app = AppState(
            settings,
            Accounts(settings, SessionOnlySecrets(), http),
            http,
            scope,
            DesktopOs.Windows,
            player,
            OnlineLyrics(http, server.address.toHttpUrl()),
            restored = server.connection(listOf("octoAcquisitions:1")),
        )
        waitFor { app.library?.index != null }
        val scene = ImageComposeScene(1200, 900, Density(1f)) { AlbumPage(app, Visit(0, Page.Album("ext-album"), null), "ext-album") }
        try {
            fun settle() = repeat(3) { scene.render(); Thread.sleep(30) }
            fun click(spot: Offset) {
                scene.sendPointerEvent(PointerEventType.Move, spot)
                scene.render()
                scene.sendPointerEvent(PointerEventType.Press, spot, buttons = PointerButtons(isPrimaryPressed = true))
                scene.sendPointerEvent(PointerEventType.Release, spot)
                scene.render()
            }
            waitFor { settle(); scene.semanticsOwners.any { owner -> find(owner.unmergedRootSemanticsNode) { text(it) == "Add all 2" } != null } }
            click(scene.semanticsOwners.firstNotNullOf { owner -> find(owner.unmergedRootSemanticsNode) { text(it) == "Add all 2" } }.boundsInRoot.center)
            waitFor { app.fetches!!.phases.value.values.all { it == FetchPhase.Done } && app.fetches!!.phases.value.size == 2 }
            waitFor { app.library?.index?.songs?.size == 2 }
            settle()
            // Hover the second song's row, then press its play button.
            val row = scene.semanticsOwners.firstNotNullOf { owner -> find(owner.unmergedRootSemanticsNode) { text(it) == "Rampage" } }
            scene.sendPointerEvent(PointerEventType.Enter, row.boundsInRoot.center)
            scene.sendPointerEvent(PointerEventType.Move, row.boundsInRoot.center)
            settle()
            val play = scene.semanticsOwners.firstNotNullOf { owner ->
                find(owner.unmergedRootSemanticsNode) { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Play from here") == true }
            }
            click(play.boundsInRoot.center)
            settle()
        } finally {
            scene.close()
        }
        assertEquals(listOf(listOf("x1", "x2") to 1), plays)
        val current = silent.state.value.current!!.song
        assertEquals("x2", current.id)
        // It plays from the file the server fetched, not from where it was found.
        val address = ServerSongs(client = { app.connection?.client }, landed = { app.fetches?.landedId(it) }).addressOf(current)!!
        assertEquals("nd2", address.source.toHttpUrl().queryParameter("id"))
        val notLanded = Song("x9", "Odd Look", isExternal = true)
        assertEquals("x9", ServerSongs(client = { app.connection?.client }, landed = { app.fetches?.landedId(it) }).addressOf(notLanded)!!.source.toHttpUrl().queryParameter("id"))
    }
}

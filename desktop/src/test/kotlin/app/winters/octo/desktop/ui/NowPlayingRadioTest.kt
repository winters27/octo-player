package app.winters.octo.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import app.winters.octo.design.PopupLayer
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.player.DesktopPlayer
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.songJson
import app.winters.octo.lyrics.OnlineLyrics
import app.winters.octo.playback.QueueSource
import app.winters.octo.subsonic.Song
import dev.chrisbanes.haze.rememberHazeState
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

// Brandon: start a radio from the More menu of the bar at the bottom.
@OptIn(ExperimentalComposeUiApi::class)
class NowPlayingRadioTest {
    @get:Rule val temp = TemporaryFolder()

    private val server = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val silent = SilentPlayer()
    private val plays = mutableListOf<List<String>>()
    private val queued = mutableListOf<List<String>>()
    private val player = object : DesktopPlayer by silent {
        override fun play(songs: List<Song>, start: Int, shuffle: Boolean, source: QueueSource) {
            plays += songs.map { it.id }
            silent.play(songs, start, shuffle, source)
        }

        override fun addToQueue(songs: List<Song>, source: QueueSource) {
            queued += songs.map { it.id }
            silent.addToQueue(songs, source)
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

    private fun ImageComposeScene.node(test: (SemanticsNode) -> Boolean): SemanticsNode? =
        semanticsOwners.firstNotNullOfOrNull { find(it.unmergedRootSemanticsNode, test) }

    private fun ImageComposeScene.click(spot: Offset) {
        sendPointerEvent(PointerEventType.Move, spot)
        sendPointerEvent(PointerEventType.Press, spot, buttons = PointerButtons(isPrimaryPressed = true))
        sendPointerEvent(PointerEventType.Release, spot)
        repeat(3) { render() }
    }

    @Test
    fun startRadioPlaysTheSongThenSongsLikeIt() {
        server.answer("getAlbumList2", """"albumList2":{"album":[]}""")
        server.answer("getArtists", """"artists":{"index":[]}""")
        server.answer("getPlaylists", """"playlists":{"playlist":[]}""")
        server.answer("search3", """"searchResult3":{"song":[]}""")
        server.answer("getSimilarSongs2", """"similarSongs2":{"song":[${songJson("b", "Odd Look")},${songJson("c", "Roadgame")}]}""")
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
            restored = server.connection(),
        )
        silent.play(listOf(Song("a", "Nightcall", artist = "Kavinsky")), 0, false, QueueSource.You)
        val scene = ImageComposeScene(1200, 900, Density(1f)) {
            val haze = rememberHazeState()
            Box(Modifier.fillMaxSize()) {
                PlayerBar(app, haze)
                PopupLayer(app.popups, haze)
            }
        }
        try {
            repeat(3) { scene.render() }
            val more = scene.node { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() == "More" }!!
            scene.click(more.boundsInRoot.center)
            val radio = scene.node { it.config.getOrNull(SemanticsProperties.Text)?.joinToString { t -> t.text } == "Start radio" }!!
            scene.click(radio.boundsInRoot.center)
        } finally {
            scene.close()
        }
        assertEquals(listOf(listOf("a")), plays)
        waitFor { queued.isNotEmpty() }
        assertEquals(listOf(listOf("b", "c")), queued)
    }
}

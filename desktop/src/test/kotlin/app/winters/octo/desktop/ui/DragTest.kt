package app.winters.octo.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.library.SongColumn
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DragTest {
    @get:Rule val folder = TemporaryFolder()

    private val songs = (1..8).map { Song("s$it", "Song $it", artist = "Artist", duration = 200) }

    // ---- The drag's own rules ----

    @Test
    fun songsDropOnlyOnATarget() {
        val drag = DragState()
        var got = emptyList<Song>()
        drag.place("queue", DropTarget(Rect(0f, 0f, 100f, 100f), "Add to the queue") { got = it })
        drag.start(songs.take(2), Offset(500f, 500f))
        assertTrue(drag.active)
        assertNull("nothing under the pointer", drag.over)
        drag.move(Offset(50f, 50f))
        assertEquals("queue", drag.over?.first)
        assertTrue(drag.drop())
        assertEquals(listOf("s1", "s2"), got.map { it.id })
        assertFalse(drag.active)
    }

    @Test
    fun lettingGoElsewhereDropsNothing() {
        val drag = DragState()
        var dropped = false
        drag.place("queue", DropTarget(Rect(0f, 0f, 100f, 100f), "Add") { dropped = true })
        drag.start(songs.take(1), Offset(500f, 500f))
        assertFalse(drag.drop())
        assertFalse(dropped)
        drag.start(emptyList(), Offset.Zero)
        assertFalse("no songs, no drag", drag.active)
    }

    // ---- Dragging a real table row ----

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun aRowDraggedOntoATargetDropsItsSong() {
        val settings = SettingsStore(File(folder.root, "settings.json"), 0)
        val http = OkHttpClient()
        val app = AppState(settings, Accounts(settings, SessionOnlySecrets(), http), http, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), DesktopOs.Windows, SilentPlayer())
        val drag = DragState()
        var dropped = emptyList<Song>()
        val scene = ImageComposeScene(900, 600, Density(1f)) {
            CompositionLocalProvider(LocalDrag provides drag) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(200.dp).fillMaxHeight().dropTarget("list", "Add to Late night") { dropped = it })
                    SongTable(app, songs, listOf(SongColumn.Title, SongColumn.Length), rememberLazyListState(), Modifier.fillMaxSize(), id = "drag")
                }
            }
        }
        var t = 0L
        fun frame() = scene.render(t).also { t += 16_000_000 }
        repeat(3) { frame() }
        val press = PointerButtons(isPrimaryPressed = true)
        // A row of the table, then a drag well over to the target.
        val start = Offset(500f, 160f)
        scene.sendPointerEvent(PointerEventType.Move, start)
        scene.sendPointerEvent(PointerEventType.Press, start, buttons = press)
        frame()
        listOf(480f, 400f, 300f, 200f, 120f, 100f).forEach { x ->
            scene.sendPointerEvent(PointerEventType.Move, Offset(x, 170f), buttons = press)
            frame()
        }
        assertTrue("a drag is under way", drag.active)
        assertEquals("list", drag.over?.first)
        scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 170f))
        frame()
        assertEquals(1, dropped.size)
        assertFalse(drag.active)
        scene.close()
    }
}

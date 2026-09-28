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
import app.winters.octo.desktop.library.rowsOf
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.subsonic.Song
import java.io.File
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

    @Test
    fun theHigherLayerThenTheSmallerTargetWins() {
        val drag = DragState()
        drag.place("page-row", DropTarget(Rect(0f, 0f, 800f, 40f), "Move here") {})
        drag.place("player", DropTarget(Rect(100f, 0f, 700f, 80f), "Add to the queue", layer = 1) {})
        drag.place("panel", DropTarget(Rect(1000f, 0f, 1300f, 900f), "Add to the queue") {})
        drag.place("queue-row", DropTarget(Rect(1000f, 100f, 1300f, 140f), "Add here") {})
        drag.start(songs.take(1), Offset(200f, 20f))
        assertEquals("the floating player covers the row", "player", drag.over?.first)
        drag.move(Offset(50f, 20f))
        assertEquals("page-row", drag.over?.first)
        drag.move(Offset(1100f, 110f))
        assertEquals("the row inside the panel", "queue-row", drag.over?.first)
        assertFalse("upper half", drag.below)
        drag.move(Offset(1100f, 130f))
        assertTrue("lower half", drag.below)
        drag.move(Offset(1100f, 500f))
        assertEquals("panel", drag.over?.first)
    }

    @Test
    fun aDropSeesWhereTheSongsCameFromThenForgetsIt() {
        val drag = DragState()
        var seen: SongPlace? = null
        drag.place("row", DropTarget(Rect(0f, 0f, 100f, 40f), "Move here") { seen = drag.from })
        drag.start(songs.take(1), Offset(50f, 10f), SongPlace.Playlist("p1", listOf(3)))
        assertTrue(drag.drop())
        assertEquals(SongPlace.Playlist("p1", listOf(3)), seen)
        assertNull(drag.from)
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

    private fun newApp(): AppState {
        val settings = SettingsStore(File(folder.root, "settings.json"), 0)
        val http = OkHttpClient()
        return AppState(settings, Accounts(settings, SessionOnlySecrets(), http), http, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), DesktopOs.Windows, SilentPlayer())
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun aRowDraggedOntoAnotherInTheSameListLandsBelowIt() {
        val app = newApp()
        val drag = DragState()
        var landed: Triple<SongPlace?, Int, Boolean>? = null
        val moves = RowDrop("Move here", takes = { it is SongPlace.Playlist }) { _, from, row, below -> landed = Triple(from, row.position, below) }
        val scene = ImageComposeScene(900, 600, Density(1f)) {
            CompositionLocalProvider(LocalDrag provides drag) {
                SongTable(
                    app, songs, listOf(SongColumn.Title, SongColumn.Length), rememberLazyListState(), Modifier.fillMaxSize(),
                    id = "moves",
                    place = { picked -> SongPlace.Playlist("p1", picked.map { it.position }) },
                    rowDrop = moves,
                )
            }
        }
        var t = 0L
        fun frame() = scene.render(t).also { t += 16_000_000 }
        repeat(3) { frame() }
        // Start on the second row (under the columns heading) and find the
        // fourth row's target once the drag is under way.
        val first = Offset(300f, 100f)
        val press = PointerButtons(isPrimaryPressed = true)
        scene.sendPointerEvent(PointerEventType.Move, first)
        scene.sendPointerEvent(PointerEventType.Press, first, buttons = press)
        frame()
        scene.sendPointerEvent(PointerEventType.Move, first + Offset(0f, 30f), buttons = press)
        frame()
        assertTrue("a drag is under way", drag.active)
        val key = rowsOf(songs)[3].key
        val target = drag.boundsOf("moves:$key") ?: error("the fourth row takes the drop")
        val to = Offset(300f, target.bottom - 4f)
        scene.sendPointerEvent(PointerEventType.Move, to, buttons = press)
        frame()
        assertEquals("Move here", drag.over?.second?.action)
        scene.sendPointerEvent(PointerEventType.Release, to)
        frame()
        assertEquals(Triple<SongPlace?, Int, Boolean>(SongPlace.Playlist("p1", listOf(1)), 3, true), landed)
        scene.close()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun songsDroppedOnAQueueRowGoAboveItAndOnThePlayingOneGoNext() {
        val app = newApp()
        app.play(songs.take(4))
        val drag = DragState()
        val scene = ImageComposeScene(400, 700, Density(1f)) {
            CompositionLocalProvider(LocalDrag provides drag) { QueueList(app, Modifier.fillMaxSize()) }
        }
        var t = 0L
        fun frame() = scene.render(t).also { t += 16_000_000 }
        repeat(3) { frame() }
        val extra = listOf(Song("x1", "Dropped", duration = 100))
        fun upcoming() = app.player.state.value.upcoming.map { it.song.id }
        assertEquals(listOf("s2", "s3", "s4"), upcoming())

        // Onto the upper half of the third song: it goes before it.
        drag.start(extra, Offset.Zero)
        frame()
        val third = app.player.state.value.upcoming[1].key
        val row = drag.boundsOf("queue:$third") ?: error("a song to come takes the drop")
        drag.move(Offset(row.center.x, row.top + 3f))
        frame()
        assertEquals("Add here", drag.over?.second?.action)
        assertTrue(drag.drop())
        frame()
        assertEquals(listOf("s2", "x1", "s3", "s4"), upcoming())

        // Onto the song playing: it plays next.
        drag.start(listOf(Song("x2", "Next", duration = 100)), Offset.Zero)
        frame()
        val playing = app.player.state.value.current!!.key
        val now = drag.boundsOf("queue:$playing") ?: error("the song playing takes the drop")
        drag.move(now.center)
        frame()
        assertEquals("Play next", drag.over?.second?.action)
        drag.drop()
        frame()
        assertEquals(listOf("x2", "s2", "x1", "s3", "s4"), upcoming())
        scene.close()
    }
}

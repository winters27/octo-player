package app.winters.octo.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import app.winters.octo.design.Corner
import app.winters.octo.design.DesktopType
import app.winters.octo.design.Glaze
import app.winters.octo.design.GlazeLight
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconSize
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.subsonic.Song
import kotlin.math.roundToInt

// A place songs can be dropped: where it is in the window, what it says it
// will do ("Add to Late night"), and what it does with them.
class DropTarget(val bounds: Rect, val action: String, val onDrop: (List<Song>) -> Unit)

// Songs being dragged across the window, from a song table to the queue or a
// playlist in the sidebar. The drag starts once the pointer has moved a
// little with a row held down; letting go over a target drops the songs
// there, anywhere else it does nothing.
@Stable
class DragState {
    var songs by mutableStateOf<List<Song>>(emptyList())
        private set

    // Where the pointer is, in the window.
    var at by mutableStateOf(Offset.Zero)
        private set

    private val targets = mutableStateMapOf<String, DropTarget>()

    val active: Boolean get() = songs.isNotEmpty()

    // The target under the pointer, if any.
    val over: Pair<String, DropTarget>? get() = if (!active) null else targets.entries.lastOrNull { it.value.bounds.contains(at) }?.toPair()

    fun start(songs: List<Song>, at: Offset) {
        if (songs.isEmpty()) return
        this.songs = songs
        this.at = at
    }

    fun move(to: Offset) {
        if (active) at = to
    }

    // Lets go: the songs go to the target under the pointer. Answers whether
    // they were dropped somewhere.
    fun drop(): Boolean {
        val target = over?.second
        val carried = songs
        songs = emptyList()
        if (target == null) return false
        target.onDrop(carried)
        return true
    }

    fun cancel() {
        songs = emptyList()
    }

    fun place(id: String, target: DropTarget) {
        targets[id] = target
    }

    fun forget(id: String) {
        targets.remove(id)
    }
}

val LocalDrag = staticCompositionLocalOf { DragState() }

// Makes this a place songs can be dropped. `lit` tells it whether songs are
// being held over it now, for it to show.
fun Modifier.dropTarget(id: String, action: String, onDrop: (List<Song>) -> Unit): Modifier = composed {
    val drag = LocalDrag.current
    DisposableEffect(id) { onDispose { drag.forget(id) } }
    onGloballyPositioned { drag.place(id, DropTarget(it.boundsInWindow(), action, onDrop)) }
}

// Whether songs are being held over the target named `id`.
@Composable
fun isDropOver(id: String): Boolean = LocalDrag.current.over?.first == id

// What follows the pointer while songs are dragged: how many, or the one
// song's title, and what letting go here would do.
@Composable
fun DragLabel(drag: DragState) {
    if (!drag.active) return
    val songs = drag.songs
    val over = drag.over?.second
    val what = if (songs.size == 1) songs.single().title else "${songs.size} songs"
    Box(Modifier.offset { IntOffset(drag.at.x.roundToInt() + LabelOffset, drag.at.y.roundToInt() + LabelOffset) }) {
        Glaze(shape = Corner.ControlShape, light = GlazeLight.Lifted) {
            Row(Modifier.padding(horizontal = Space.L, vertical = Space.S), verticalAlignment = Alignment.CenterVertically) {
                Glyph(if (over != null) OctoIcons.AddToPlaylist else OctoIcons.Songs, Modifier.padding(end = Space.M), size = IconSize.Table, tint = if (over != null) OctoColors.TextPrimary else OctoColors.TextSecondary)
                Txt(if (over != null) "${over.action}: $what" else what, DesktopType.emphasis)
            }
        }
    }
}

// The label sits just below and right of the pointer, in pixels.
private const val LabelOffset = 14

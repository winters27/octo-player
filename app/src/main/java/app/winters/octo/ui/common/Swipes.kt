package app.winters.octo.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs

// Which way a drag is meant to go. It is decided once, from the first clear
// movement, and holds for the rest of the drag.
enum class DragAxis { Undecided, Horizontal, Vertical }

// The finger has moved (dx, dy) since it went down. Until it has gone
// `slop` in any direction nothing is decided; then the larger of the two
// wins. A tie counts as upright, so pulling the player down to close it
// keeps the benefit of the doubt.
fun dragAxis(dx: Float, dy: Float, slop: Float): DragAxis = when {
    dx * dx + dy * dy < slop * slop -> DragAxis.Undecided
    abs(dx) > abs(dy) -> DragAxis.Horizontal
    else -> DragAxis.Vertical
}

enum class SwipeSkip { Next, Previous, Stay }

// Where a sideways swipe over a song ends up once the finger lifts. Far
// enough, or flicked fast enough the way it was already going, it skips:
// left to the next song, right to the one before. Otherwise it stays.
fun swipeSkip(offset: Float, velocity: Float, distance: Float, fling: Float): SwipeSkip = when {
    offset <= -distance || (offset < 0f && velocity <= -fling) -> SwipeSkip.Next
    offset >= distance || (offset > 0f && velocity >= fling) -> SwipeSkip.Previous
    else -> SwipeSkip.Stay
}

// Whether an upright swipe ends by opening what is above it: pulled up far
// enough, or flicked up fast enough while already going up.
fun swipeUp(offset: Float, velocity: Float, distance: Float, fling: Float): Boolean =
    offset <= -distance || (offset < 0f && velocity <= -fling)

// What to do with a drag along one axis: follow it as it moves, and settle
// it when the finger lifts, with how far it went and how fast it was going.
// A drag taken over by something else ends as if it went nowhere.
class AxisDrag(val onMove: (offset: Float) -> Unit, val onEnd: (offset: Float, velocity: Float) -> Unit)

// Drags that pick their axis from the first clear movement. A sideways one
// goes to `horizontal`; an upright one goes to `vertical`, or, without it,
// is left alone for whatever is around (like the player's pull to close).
// Once taken, the drag is consumed, so a tap under it does not fire.
suspend fun PointerInputScope.detectAxisDrags(horizontal: AxisDrag, vertical: AxisDrag? = null) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var moved = Offset.Zero
        var axis = DragAxis.Undecided
        while (axis == DragAxis.Undecided) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            // Lifted before moving far: a tap, for whatever is under it.
            if (!change.pressed || change.isConsumed) return@awaitEachGesture
            moved += change.positionChange()
            tracker.addPosition(change.uptimeMillis, change.position)
            axis = dragAxis(moved.x, moved.y, slop)
            if (axis == DragAxis.Vertical && vertical == null) return@awaitEachGesture
            if (axis != DragAxis.Undecided) change.consume()
        }
        val sideways = axis == DragAxis.Horizontal
        val handler = if (sideways) horizontal else vertical ?: return@awaitEachGesture
        var offset = if (sideways) moved.x else moved.y
        handler.onMove(offset)
        val follow: (PointerInputChange) -> Unit = { change ->
            tracker.addPosition(change.uptimeMillis, change.position)
            offset += if (sideways) change.positionChange().x else change.positionChange().y
            change.consume()
            handler.onMove(offset)
        }
        val finished = if (sideways) horizontalDrag(down.id, follow) else verticalDrag(down.id, follow)
        if (finished) {
            val velocity = tracker.calculateVelocity()
            handler.onEnd(offset, if (sideways) velocity.x else velocity.y)
        } else {
            handler.onEnd(0f, 0f)
        }
    }
}

package app.winters.octo.ui.common

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.roundToIntRect

// The rectangle a menu opens beside, from what was pressed and where the
// finger was. A control smaller than half the screen is the rectangle
// itself. A wide row is narrowed to the finger, keeping the row's top and
// bottom, so the menu opens under or over the row near where it was held
// rather than at the screen's edge.
fun menuAnchor(bounds: IntRect, finger: IntOffset, windowWidth: Int): IntRect =
    if (bounds.width * 2 <= windowWidth) bounds else IntRect(finger.x, bounds.top, finger.x, bounds.bottom)

// The last thing pressed anywhere in the app, so a menu or a small glass
// card can open beside it: a long-pressed row, or the button tapped.
class PressSpot {
    private var spot: IntRect? = null
    private var at = 0L

    internal fun pressed(anchor: IntRect) {
        spot = anchor
        at = SystemClock.uptimeMillis()
    }

    // What was pressed in the last two seconds, if anything. Older than
    // that, what opens was not asked for by a finger, so it goes in the
    // middle of the screen.
    fun recent(): IntRect? = spot.takeIf { SystemClock.uptimeMillis() - at < PRESS_MS }
}

// Long enough for a long press and a slow tap, short enough that something
// opened later does not open beside an old press.
private const val PRESS_MS = 2_000L

val LocalPressSpot = staticCompositionLocalOf<PressSpot> { error("No press spot") }

// Marks what a press lands on, so what it opens pops up beside it. On the
// whole screen (`wholeArea`) it keeps just the finger's point, as the spot
// for anything not marked itself. A marked control inside it wins, since it
// hears the press after its parents.
fun Modifier.pressSpot(spot: PressSpot, wholeArea: Boolean = false): Modifier = this.then(PressSpotElement(spot, wholeArea))

// The spot pressed just before `visible` turned true, kept while it stays
// open and as it closes, so a card does not move while it fades away.
@Composable
fun rememberOpenedBeside(visible: Boolean): IntRect? {
    val spot = LocalPressSpot.current
    val held = remember { OpenedBeside() }
    if (visible && !held.open) held.anchor = spot.recent()
    held.open = visible
    return held.anchor
}

private class OpenedBeside {
    var open = false
    var anchor: IntRect? = null
}

private data class PressSpotElement(val spot: PressSpot, val wholeArea: Boolean) : ModifierNodeElement<PressSpotNode>() {
    override fun create() = PressSpotNode(spot, wholeArea)
    override fun update(node: PressSpotNode) {
        node.spot = spot
        node.wholeArea = wholeArea
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "pressSpot"
    }
}

// Keeps its bounds, and hands the spot on as a finger lands, before any
// click or long press runs.
private class PressSpotNode(var spot: PressSpot, var wholeArea: Boolean) :
    Modifier.Node(),
    GlobalPositionAwareModifierNode,
    PointerInputModifierNode {
    private var bounds = Rect.Zero
    private var windowWidth = 0

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        bounds = coordinates.boundsInWindow()
        windowWidth = coordinates.findRootCoordinates().size.width
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Initial) return
        val down = pointerEvent.changes.firstOrNull { it.changedToDown() } ?: return
        val finger = IntOffset((this.bounds.left + down.position.x).toInt(), (this.bounds.top + down.position.y).toInt())
        val anchor = if (wholeArea) {
            IntRect(finger, finger)
        } else {
            menuAnchor(this.bounds.roundToIntRect(), finger, windowWidth)
        }
        spot.pressed(anchor)
    }

    override fun onCancelPointerInput() = Unit
}

package app.winters.octo.desktop.window

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import app.winters.octo.design.Glyph
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import java.awt.Cursor
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window

val TitleBarHeight = 40.dp

// Room left of the title bar's controls for the macOS traffic lights.
val MacLightsRoom = 76.dp

// The window drawn by the app, for Windows and Linux: its own title bar,
// dragging, edges to resize by, and a maximize of its own. A window
// without the system's frame covers the taskbar when the system maximizes
// it, so here it fills the screen's usable part instead.
@Stable
class Frame(private val window: Window, private val state: WindowState) {
    var maximized by mutableStateOf(false)
        private set

    // Where the window was before it filled the screen.
    private var restoreTo: Rectangle? = null

    fun toggleMaximized() = if (maximized) restore() else maximize()

    fun maximize() {
        if (maximized) return
        restoreTo = window.bounds
        window.bounds = usableArea()
        maximized = true
    }

    fun restore() {
        if (!maximized) return
        restoreTo?.let { window.bounds = it }
        maximized = false
    }

    fun minimize() {
        state.isMinimized = true
    }

    // The bounds to keep when the window closes: the size it returns to.
    fun lastFloatingBounds(): Rectangle = if (maximized) restoreTo ?: window.bounds else window.bounds

    // The screen the window is mostly on, less the taskbar or panels.
    private fun usableArea(): Rectangle {
        val config = window.graphicsConfiguration ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val bounds = config.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        return Rectangle(bounds.x + insets.left, bounds.y + insets.top, bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom)
    }

    // Moves the window with the pointer, from where a drag started. A drag
    // on a filled window first brings it back to its size under the pointer.
    fun dragBy(startPointer: java.awt.Point, startWindow: java.awt.Point) {
        val now = MouseInfo.getPointerInfo()?.location ?: return
        window.setLocation(startWindow.x + now.x - startPointer.x, startWindow.y + now.y - startPointer.y)
    }

    fun startDrag(): Pair<java.awt.Point, java.awt.Point>? {
        val pointer = MouseInfo.getPointerInfo()?.location ?: return null
        if (maximized) {
            val wide = window.width
            val back = restoreTo ?: Rectangle(window.x, window.y, 1280, 800)
            maximized = false
            // Keep the pointer at the same share across the title bar.
            val share = (pointer.x - window.x).toDouble() / wide
            window.bounds = Rectangle((pointer.x - back.width * share).toInt(), window.y, back.width, back.height)
        }
        return pointer to window.location
    }

    // Resizes from an edge or corner while dragged: `dx`/`dy` say which
    // sides move (-1 the left or top, 1 the right or bottom).
    fun resize(from: Rectangle, startPointer: java.awt.Point, dx: Int, dy: Int) {
        val now = MouseInfo.getPointerInfo()?.location ?: return
        val min = window.minimumSize
        var x = from.x
        var y = from.y
        var w = from.width
        var h = from.height
        val mx = now.x - startPointer.x
        val my = now.y - startPointer.y
        if (dx > 0) w = maxOf(min.width, from.width + mx)
        if (dx < 0) {
            w = maxOf(min.width, from.width - mx)
            x = from.x + from.width - w
        }
        if (dy > 0) h = maxOf(min.height, from.height + my)
        if (dy < 0) {
            h = maxOf(min.height, from.height - my)
            y = from.y + from.height - h
        }
        window.bounds = Rectangle(x, y, w, h)
    }

    val bounds: Rectangle get() = window.bounds

    // Whether the system says it is maximized, for a window with the system frame.
    val systemMaximized: Boolean get() = state.placement == WindowPlacement.Maximized
}

// The draggable part of the title bar: drag to move, double click to fill
// the screen or come back.
fun Modifier.dragsWindow(frame: Frame): Modifier = pointerInput(frame) {
    var lastDown = 0L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val now = System.currentTimeMillis()
        if (now - lastDown < 400) {
            frame.toggleMaximized()
            lastDown = 0L
            return@awaitEachGesture
        }
        lastDown = now
        var start: Pair<java.awt.Point, java.awt.Point>? = null
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (change.positionChange() != androidx.compose.ui.geometry.Offset.Zero) {
                if (start == null) start = frame.startDrag()
                start?.let { frame.dragBy(it.first, it.second) }
                change.consume()
            }
        }
    }
}

// The window's own buttons: minimize, maximize or restore, and close.
@Composable
fun RowScope.WindowButtons(frame: Frame, onClose: () -> Unit) {
    WindowButton(OctoIcons.Minimize, "Minimize") { frame.minimize() }
    WindowButton(if (frame.maximized) OctoIcons.Restore else OctoIcons.Maximize, if (frame.maximized) "Restore" else "Maximize") { frame.toggleMaximized() }
    WindowButton(OctoIcons.Close, "Close", danger = true, onClick = onClose)
}

@Composable
private fun WindowButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .width(46.dp)
            .fillMaxHeight()
            .hoverable(interaction)
            .background(
                when {
                    hovered && danger -> Color(0xFFC42B1C)
                    hovered -> Color.White.copy(alpha = 0.08f)
                    else -> Color.Transparent
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(icon, size = 16.dp, tint = if (hovered) Color.White else OctoColors.TextSecondary)
    }
}

// Invisible strips along the window's edges and corners that resize it.
@Composable
fun BoxScope.ResizeEdges(frame: Frame, thickness: Dp = 5.dp) {
    if (frame.maximized) return
    val corner = thickness * 3
    @Composable
    fun edge(modifier: Modifier, dx: Int, dy: Int, cursor: Int) {
        Box(
            modifier
                .pointerHoverIcon(PointerIcon(Cursor(cursor)))
                .pointerInput(frame, dx, dy) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val from = frame.bounds
                        val pointer = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            frame.resize(from, pointer, dx, dy)
                            change.consume()
                        }
                    }
                },
        )
    }
    edge(Modifier.align(Alignment.CenterStart).width(thickness).fillMaxHeight(), -1, 0, Cursor.W_RESIZE_CURSOR)
    edge(Modifier.align(Alignment.CenterEnd).width(thickness).fillMaxHeight(), 1, 0, Cursor.E_RESIZE_CURSOR)
    edge(Modifier.align(Alignment.TopCenter).height(thickness).fillMaxWidth(), 0, -1, Cursor.N_RESIZE_CURSOR)
    edge(Modifier.align(Alignment.BottomCenter).height(thickness).fillMaxWidth(), 0, 1, Cursor.S_RESIZE_CURSOR)
    edge(Modifier.align(Alignment.TopStart).size(corner), -1, -1, Cursor.NW_RESIZE_CURSOR)
    edge(Modifier.align(Alignment.TopEnd).size(corner), 1, -1, Cursor.NE_RESIZE_CURSOR)
    edge(Modifier.align(Alignment.BottomStart).size(corner), -1, 1, Cursor.SW_RESIZE_CURSOR)
    edge(Modifier.align(Alignment.BottomEnd).size(corner), 1, 1, Cursor.SE_RESIZE_CURSOR)
}

// A bar of the title bar's height.
@Composable
fun TitleStrip(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().height(TitleBarHeight), verticalAlignment = Alignment.CenterVertically, content = content)
}

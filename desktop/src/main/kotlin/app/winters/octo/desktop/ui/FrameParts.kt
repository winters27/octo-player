package app.winters.octo.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import app.winters.octo.design.ChromeEdge
import app.winters.octo.design.FrameSize
import app.winters.octo.design.Space
import java.awt.Cursor

// The hairline where the frame meets the page, down or across.
@Composable
fun Seam(vertical: Boolean, modifier: Modifier = Modifier) {
    Box(
        if (vertical) modifier.fillMaxHeight().width(FrameSize.Hairline).background(ChromeEdge)
        else modifier.fillMaxWidth().height(FrameSize.Hairline).background(ChromeEdge),
    )
}

// A strip to drag a panel's edge by, with the sideways cursor. `onDrag`
// hears each move in dp; `onDone` hears the end, to save the width.
@Composable
fun ResizeHandle(onDrag: (Dp) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    Box(
        modifier
            .fillMaxHeight()
            .width(Space.S)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = onDone, onDragCancel = onDone) { change, amount ->
                    change.consume()
                    onDrag(with(density) { amount.toDp() })
                }
            },
    )
}

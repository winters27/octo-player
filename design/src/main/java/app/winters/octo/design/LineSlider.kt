package app.winters.octo.design

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

// A line that glows along the part already filled, for song progress and
// volume. The line thickens and a thumb appears while it is dragged. By
// default the value is only sent when the finger lifts, so scrubbing a song
// does not stutter the audio; `live` sends it all along, for volume.
@Composable
fun LineSlider(
    fraction: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    color: Color = Color.White,
    trackColor: Color = Color.White.copy(alpha = 0.24f),
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val seek by rememberUpdatedState(onSeek)
    val shown = if (dragging) dragFraction else fraction.coerceIn(0f, 1f)

    val thickness by animateDpAsState(if (dragging) 8.dp else 3.5.dp, spring(0.5f, 200f), label = "track")
    val thumb by animateDpAsState(if (dragging) 9.dp else 0.dp, spring(0.5f, 200f), label = "thumb")
    val glowBlur by animateFloatAsState(if (dragging) 24f else 12f, spring(1f, 200f), label = "glow blur")
    val glowAlpha by animateFloatAsState(if (dragging) 0.8f else 0.4f, spring(1f, 200f), label = "glow")

    val glowPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { strokeCap = android.graphics.Paint.Cap.ROUND } }

    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(Unit) {
                detectTapGestures { seek((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        dragFraction = (it.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        seek(dragFraction)
                        dragging = false
                    },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    if (live) seek(dragFraction)
                }
            }
            .drawBehind {
                val y = size.height / 2
                val stroke = thickness.toPx()
                val end = size.width * shown
                drawLine(trackColor, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
                if (end > 0f) {
                    // Three soft passes of the same line make the glow: wide and
                    // faint, then medium, then a tight bright core.
                    drawIntoCanvas { canvas ->
                        for (spread in floatArrayOf(2.5f, 1f, 0.3f)) {
                            glowPaint.color = color.copy(alpha = glowAlpha / 3f).toArgb()
                            glowPaint.strokeWidth = stroke
                            glowPaint.maskFilter = BlurMaskFilter(glowBlur * spread, BlurMaskFilter.Blur.NORMAL)
                            canvas.nativeCanvas.drawLine(0f, y, end, y, glowPaint)
                        }
                    }
                    drawLine(color, Offset(0f, y), Offset(end, y), stroke, StrokeCap.Round)
                }
                if (thumb > 0.dp) drawCircle(color, thumb.toPx(), Offset(end, y))
            },
    )
}

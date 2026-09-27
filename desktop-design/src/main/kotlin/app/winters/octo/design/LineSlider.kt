package app.winters.octo.design

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap

// A line that glows along the part already filled, for song progress and
// volume, as on the phone. The line thickens and a thumb shows under the
// pointer. By default the value is only sent when the drag ends, so
// scrubbing a song does not stutter it; `live` sends it all along, for
// volume. `wheelStep`, when set, lets the mouse wheel move it by that much.
// `onRelease` hears when a change is done (a click, a drag let go, a wheel
// notch). `hoverLabel`, when set, shows what a click would pick, in a small
// pill above the pointer: the time to seek to, for a song.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LineSlider(
    fraction: () -> Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    wheelStep: Float? = null,
    color: Color = Color.White,
    trackColor: Color = Color.White.copy(alpha = 0.22f),
    onRelease: () -> Unit = {},
    hoverLabel: ((Float) -> String)? = null,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    // Where the pointer is over the line, from 0 to 1, or below 0 when off it.
    var pointerFraction by remember { mutableFloatStateOf(-1f) }
    val seek by rememberUpdatedState(onSeek)
    val release by rememberUpdatedState(onRelease)
    val current by rememberUpdatedState(fraction)
    val label by rememberUpdatedState(hoverLabel)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val measurer = rememberTextMeasurer()

    val thickness by animateDpAsState(if (dragging || hovered) 5.dp else 3.dp, spring(0.6f, 300f), label = "track")
    val thumb = thumbSize(hovered, dragging)
    val glowAlpha by animateFloatAsState(if (dragging) 0.7f else 0.35f, spring(1f, 200f), label = "glow")
    val glowPaint = remember {
        Paint().apply {
            isAntiAlias = true
            mode = PaintMode.STROKE
            strokeCap = PaintStrokeCap.ROUND
        }
    }

    Box(
        modifier
            .height(24.dp)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .onPointerEvent(PointerEventType.Move) { event ->
                pointerFraction = (event.changes.first().position.x / size.width).coerceIn(0f, 1f)
            }
            .onPointerEvent(PointerEventType.Exit) { pointerFraction = -1f }
            .then(
                if (wheelStep != null) {
                    Modifier.onPointerEvent(PointerEventType.Scroll) { event ->
                        val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (delta != 0f) {
                            seek((current() - delta * wheelStep).coerceIn(0f, 1f))
                            release()
                        }
                    }
                } else {
                    Modifier
                },
            )
            .pointerInput(Unit) {
                detectTapGestures {
                    seek((it.x / size.width).coerceIn(0f, 1f))
                    release()
                }
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
                        release()
                    },
                    onDragCancel = {
                        dragging = false
                        release()
                    },
                ) { change, _ ->
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    pointerFraction = dragFraction
                    if (live) seek(dragFraction)
                }
            }
            .drawBehind {
                val y = size.height / 2
                val stroke = thickness.toPx()
                // Read here, while drawing, so a moving value only redraws.
                val end = size.width * (if (dragging) dragFraction else current().coerceIn(0f, 1f))
                drawLine(trackColor, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
                if (end > 0f) {
                    // Soft passes of the same line make the glow: wide and
                    // faint, then a tighter one. Garnish, not a light.
                    drawIntoCanvas { canvas ->
                        for (spread in floatArrayOf(2.5f, 1f)) {
                            glowPaint.color = color.copy(alpha = glowAlpha / 3f).toArgb()
                            glowPaint.strokeWidth = stroke
                            glowPaint.maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, 4f * spread)
                            canvas.skiaCanvas.drawLine(0f, y, end, y, glowPaint)
                        }
                    }
                    drawLine(color, Offset(0f, y), Offset(end, y), stroke, StrokeCap.Round)
                }
                if (thumb > 0.dp) drawCircle(color, thumb.toPx(), Offset(end, y))
                // What a click here would pick, above the pointer.
                val at = if (dragging) dragFraction else pointerFraction
                val words = label
                if (words != null && at >= 0f && (hovered || dragging)) {
                    val text = measurer.measure(words(at), OctoType.caption.copy(fontSize = 11.sp, color = Color.White, fontFeatureSettings = "tnum"))
                    val padX = 7.dp.toPx()
                    val padY = 2.dp.toPx()
                    val w = text.size.width + padX * 2
                    val h = text.size.height + padY * 2
                    val left = (size.width * at - w / 2).coerceIn(-w / 2, size.width - w / 2)
                    val top = y - 10.dp.toPx() - h
                    drawRoundRect(Color.Black.copy(alpha = 0.72f), Offset(left, top), Size(w, h), CornerRadius(h / 2))
                    drawText(text, topLeft = Offset(left + padX, top + padY))
                }
            },
    )
}

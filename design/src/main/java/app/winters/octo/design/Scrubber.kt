package app.winters.octo.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// What the scrubber is doing, which sets how tall it is.
enum class ScrubberState { Rest, Hover, Drag }

// The scrubber's state from the pointer: dragging wins over hovering.
fun scrubberState(hovered: Boolean, dragging: Boolean): ScrubberState = when {
    dragging -> ScrubberState.Drag
    hovered -> ScrubberState.Hover
    else -> ScrubberState.Rest
}

// How tall the bar is in each state.
fun scrubberBarHeight(state: ScrubberState): Dp = when (state) {
    ScrubberState.Rest -> 6.dp
    ScrubberState.Hover -> 12.dp
    ScrubberState.Drag -> 16.dp
}

// The cursor tick at the end of the played part: 4 dp wide and 16 tall,
// half height at rest, full under the pointer, and 1.6 times its size,
// with a shadow, while dragged.
val ScrubberTickWidth: Dp = 4.dp
val ScrubberTickHeight: Dp = 16.dp

fun scrubberTickScale(state: ScrubberState): Float = when (state) {
    ScrubberState.Rest -> 0.5f
    ScrubberState.Hover -> 1f
    ScrubberState.Drag -> 1.6f
}

// Whether the position jumping from `previous` to `now` is the song
// ending or looping back to its start, rather than a seek: from the last
// tenth straight to the first.
fun isTrackWrap(previous: Float, now: Float): Boolean = previous >= 0.9f && now <= 0.1f

// The playback scrubber: a bar that grows under the pointer and grows again
// while dragged, on the overshoot curve, with a white tick at the played
// end. The hit area stays `hitHeight` tall whatever the bar does. The value
// is sent when the drag ends, so scrubbing does not stutter the audio, or
// all along with `live`; `onScrub` hears where a drag is (null when it
// ends), for a time label. When the song ends or loops, the played part
// sweeps off to the right before the new one starts.
@Composable
fun Scrubber(
    fraction: () -> Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    onScrub: ((Float?) -> Unit)? = null,
    hitHeight: Dp = 24.dp,
    color: Color = Color.White,
    trackColor: Color = Color.White.copy(alpha = 0.10f),
    interactionSource: MutableInteractionSource? = null,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val seek by rememberUpdatedState(onSeek)
    val scrub by rememberUpdatedState(onScrub)
    val current by rememberUpdatedState(fraction)
    val hovered by interaction.collectIsHoveredAsState()
    val draggedElsewhere by interaction.collectIsDraggedAsState()
    val held = dragging || draggedElsewhere
    var drag by remember { mutableStateOf<DragInteraction.Start?>(null) }

    val motion = motionScale()
    val state = scrubberState(hovered, held)
    val bar by animateFloatAsState(
        scrubberBarHeight(state).value,
        octoTween(motion, OctoDuration.Scrub, OctoEasing.Overshoot),
        label = "scrubber bar",
    )
    val tick by animateFloatAsState(
        scrubberTickScale(state),
        octoTween(motion, OctoDuration.Scrub, OctoEasing.Overshoot),
        label = "scrubber tick",
    )
    val shade by animateFloatAsState(if (held) 1f else 0f, octoTween(motion, OctoDuration.Card), label = "scrubber shade")

    // The sweep at the end of a song: 0 is none, 1 is gone off the end.
    val sweep = remember { Animatable(0f) }
    var swept by remember { mutableFloatStateOf(0f) }
    val motionNow by rememberUpdatedState(motion)
    LaunchedEffect(Unit) {
        var previous = current().coerceIn(0f, 1f)
        snapshotFlow { current().coerceIn(0f, 1f) }.collect { now ->
            if (!dragging && isTrackWrap(previous, now) && !motionNow.still) {
                swept = previous
                sweep.snapTo(0f)
                sweep.animateTo(1f, octoTween(motionNow, OctoDuration.Scrub, OctoEasing.Sweep))
                sweep.snapTo(0f)
            }
            previous = now
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(hitHeight)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                detectTapGestures { seek((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        dragFraction = (it.x / size.width).coerceIn(0f, 1f)
                        drag = DragInteraction.Start().also(interaction::tryEmit)
                        scrub?.invoke(dragFraction)
                    },
                    onDragEnd = {
                        seek(dragFraction)
                        dragging = false
                        drag?.let { interaction.tryEmit(DragInteraction.Stop(it)) }
                        scrub?.invoke(null)
                    },
                    onDragCancel = {
                        dragging = false
                        drag?.let { interaction.tryEmit(DragInteraction.Cancel(it)) }
                        scrub?.invoke(null)
                    },
                ) { change, _ ->
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    scrub?.invoke(dragFraction)
                    if (live) seek(dragFraction)
                }
            }
            .drawBehind {
                val h = bar.dp.toPx()
                val top = (size.height - h) / 2f
                val corner = CornerRadius(h / 2f)
                // Read here, while drawing, so a moving value only redraws.
                val f = if (dragging) dragFraction else current().coerceIn(0f, 1f)
                // A soft shadow under the bar while it is dragged.
                if (shade > 0f) {
                    for (i in 1..3) {
                        val spread = i * 2.dp.toPx()
                        drawRoundRect(
                            Color.Black.copy(alpha = 0.035f * shade),
                            Offset(-spread / 2f, top + 4.dp.toPx() - spread / 2f),
                            Size(size.width + spread, h + spread),
                            CornerRadius(h / 2f + spread / 2f),
                        )
                    }
                }
                drawRoundRect(trackColor, Offset(0f, top), Size(size.width, h), corner)
                // The played part: a full-width layer slid in from the left
                // and clipped to the bar, so its leading end is square.
                val path = Path().apply { addRoundRect(RoundRect(0f, top, size.width, top + h, corner)) }
                clipPath(path) {
                    val s = sweep.value
                    if (s > 0f) {
                        // The last song's played part sliding off the end.
                        drawRect(color, Offset(size.width * s, top), Size(size.width * swept, h))
                    } else {
                        drawRect(color, Offset(size.width * (f - 1f), top), Size(size.width, h))
                    }
                }
                // The cursor tick.
                if (sweep.value == 0f) {
                    val tw = ScrubberTickWidth.toPx() * (if (tick > 1f) tick else 1f)
                    val th = ScrubberTickHeight.toPx() * tick
                    val x = (size.width * f).coerceIn(tw / 2f, size.width - tw / 2f)
                    if (shade > 0f) {
                        drawRoundRect(
                            Color.Black.copy(alpha = 0.25f * shade),
                            Offset(x - tw / 2f, size.height / 2f - th / 2f + 1.dp.toPx()),
                            Size(tw, th),
                            CornerRadius(tw / 2f),
                        )
                    }
                    drawRoundRect(color, Offset(x - tw / 2f, size.height / 2f - th / 2f), Size(tw, th), CornerRadius(tw / 2f))
                }
            },
    )
}

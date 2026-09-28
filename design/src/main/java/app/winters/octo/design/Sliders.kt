package app.winters.octo.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// How a line slider is drawn. Glow is Octo's own line for song progress
// and volume: the played part lit, a thumb only while dragging. Jewel is
// the settings slider: a recessed track, an accent fill, and a white
// jewel of a thumb with an accent dot in it, a ring and a glow while
// dragged, and the value in a bubble above.
enum class SliderLook { Glow, Jewel }

// A fraction moved to the nearest of `steps` equal steps (with `steps`
// zero or less it is left alone).
fun snapToSteps(fraction: Float, steps: Int): Float {
    val f = fraction.coerceIn(0f, 1f)
    if (steps <= 0) return f
    return (f * steps).roundToInt().toFloat() / steps
}

// Where a jewel slider is in its states, each animated.
@Stable
class JewelLook internal constructor(
    private val thumbState: State<Float>,
    private val dotState: State<Float>,
    private val ringState: State<Float>,
    private val bubbleState: State<Float>,
) {
    // The thumb's diameter, in dp.
    val thumb: Float get() = thumbState.value

    // The accent dot's share of the thumb.
    val dot: Float get() = dotState.value

    // The drag ring and glow, 0 to 1.
    val ring: Float get() = ringState.value

    // The value bubble, 0 to 1.
    val bubble: Float get() = bubbleState.value
}

@Composable
fun rememberJewelLook(hovered: Boolean, dragging: Boolean): JewelLook {
    val motion = motionScale()
    val thumb = animateFloatAsState(if (hovered || dragging) 16f else 14f, octoTween(motion, OctoDuration.Hover), label = "jewel thumb")
    val dot = animateFloatAsState(if (hovered || dragging) 0.42f else 0.35f, octoTween(motion, OctoDuration.Hover), label = "jewel dot")
    val ring = animateFloatAsState(if (dragging) 1f else 0f, octoTween(motion, OctoDuration.Card), label = "jewel ring")
    val bubble = animateFloatAsState(if (dragging) 1f else 0f, octoTween(motion, OctoDuration.Swap), label = "jewel bubble")
    return JewelLook(thumb, dot, ring, bubble)
}

// The jewel slider's fill follows the value in 80 ms, straight, so a value
// set from elsewhere slides rather than jumps. While dragging it follows
// the pointer at once.
@Composable
fun rememberFollowedFraction(fraction: () -> Float, dragging: Boolean): State<Float> {
    val shown = remember { Animatable(fraction().coerceIn(0f, 1f)) }
    val current by rememberUpdatedState(fraction)
    val motion = motionScale()
    LaunchedEffect(dragging) {
        snapshotFlow { current().coerceIn(0f, 1f) }.collect { target ->
            if (dragging) shown.snapTo(target) else shown.animateTo(target, tween(motion.ms(80), easing = LinearEasing))
        }
    }
    return shown.asState()
}

// The value bubble's words.
val BubbleText = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)

// Draws the jewel slider across the whole width, centred on `y`: the
// recessed track, the accent fill to `fraction`, ticks for `steps`, the
// thumb, and while dragging its ring, glow and value bubble.
fun DrawScope.drawJewelSlider(
    y: Float,
    fraction: Float,
    look: JewelLook,
    fill: Color,
    steps: Int,
    label: String?,
    measurer: TextMeasurer?,
) {
    val track = 4.dp.toPx()
    val radius = look.thumb.dp.toPx() / 2f
    // The thumb stays inside the ends of the track.
    val start = radius
    val end = size.width - radius
    val x = start + (end - start) * fraction.coerceIn(0f, 1f)
    val top = y - track / 2f
    val corner = CornerRadius(track / 2f)

    // The track: a faint white deepening downward, with a shade along its
    // top edge so it reads as sunk into the surface.
    drawRoundRect(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.14f)), startY = top, endY = top + track),
        Offset(0f, top),
        Size(size.width, track),
        corner,
    )
    drawRoundRect(Color.Black.copy(alpha = 0.25f), Offset(0f, top), Size(size.width, 1.dp.toPx().coerceAtMost(track / 2f)), corner)

    // The fill, shaded top to bottom.
    if (x > 0f) {
        drawRoundRect(
            Brush.verticalGradient(listOf(fill, mix(fill, Color.Black, 0.22f)), startY = top, endY = top + track),
            Offset(0f, top),
            Size(x, track),
            corner,
        )
    }

    // Ticks at each step, faint on the track and darker on the fill.
    if (steps in 1..40) {
        val tick = 2.dp.toPx()
        for (i in 1 until steps) {
            val tx = start + (end - start) * i / steps
            val color = if (tx <= x) Color.Black.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.30f)
            drawCircle(color, tick / 2f, Offset(tx, y))
        }
    }

    // While dragging: a soft accent glow and a ring round the thumb.
    if (look.ring > 0f) {
        val glow = 18.dp.toPx()
        drawCircle(
            Brush.radialGradient(
                listOf(fill.copy(alpha = 0.35f * look.ring), Color.Transparent),
                center = Offset(x, y),
                radius = radius + glow,
            ),
            radius + glow,
            Offset(x, y),
        )
        val ring = 4.dp.toPx()
        drawCircle(fill.copy(alpha = 0.45f * look.ring), radius + ring / 2f, Offset(x, y), style = Stroke(ring))
    }

    // The thumb: a drop shadow, then white with a highlight toward its top
    // left, then the accent dot.
    drawCircle(Color.Black.copy(alpha = 0.35f), radius + 0.5.dp.toPx(), Offset(x, y + 1.dp.toPx()))
    drawCircle(
        Brush.radialGradient(
            listOf(Color.White, Color(0xFFE4E6E8)),
            center = Offset(x - radius * 0.35f, y - radius * 0.35f),
            radius = radius * 1.6f,
        ),
        radius,
        Offset(x, y),
    )
    drawCircle(fill, radius * look.dot, Offset(x, y))

    // The value bubble, 14 dp above the thumb: black at 85%, with a small
    // arrow pointing down at it.
    if (label != null && measurer != null && look.bubble > 0f) {
        val text = measurer.measure(label, BubbleText)
        val padX = 6.dp.toPx()
        val padY = 3.dp.toPx()
        val w = text.size.width + padX * 2
        val h = text.size.height + padY * 2
        val arrow = 4.dp.toPx()
        val bottom = y - radius - 14.dp.toPx() + arrow
        val left = (x - w / 2f).coerceIn(0f, (size.width - w).coerceAtLeast(0f))
        val bubble = Color.Black.copy(alpha = 0.85f * look.bubble)
        drawRoundRect(bubble, Offset(left, bottom - arrow - h), Size(w, h), CornerRadius(8.dp.toPx()))
        val tip = Path().apply {
            moveTo(x - arrow, bottom - arrow)
            lineTo(x + arrow, bottom - arrow)
            lineTo(x, bottom)
            close()
        }
        drawPath(tip, bubble)
        drawText(text, topLeft = Offset(left + padX, bottom - arrow - h + padY), alpha = look.bubble)
    }
}

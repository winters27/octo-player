package app.winters.octo.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.winters.octo.discovery.DownloadPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

// How the ring moves: one slow turn while waiting, the fill easing between
// the server's answers, a gentle pulse while the song is added, and the
// check drawing itself in as the ring fades.
private const val TURN_MS = 1_600
private const val FILL_MS = 1_200
private const val PULSE_MS = 900
private const val CHECK_MS = 300
private const val GLOW_HOLD_MS = 500L
private const val GLOW_FADE_MS = 700

// How long pages wait after a download lands before they swap its row for
// the library song, so the check has its moment first.
internal const val CHECK_SETTLE_MS = 1_500L

// How much of the circle the waiting arc covers.
private const val WAITING_SWEEP = 90f

// Half the plus inside the ring, as a share of the ring's width.
private const val PLUS_ARM = 0.18f

private val Stroke2 = 2.dp
private val Track = Color.White.copy(alpha = 0.2f)

// A song on its way into the library, drawn as a thin ring around a small
// plus: turning while it waits, filling as it downloads, full and pulsing
// while the song is added, then a check once it is in the library. With
// reduced motion nothing turns, pulses or morphs; only the states change.
@Composable
internal fun DownloadRing(phase: DownloadPhase, calm: Boolean, modifier: Modifier = Modifier) {
    val done = phase == DownloadPhase.Done
    // A song already in the library when this is first drawn just shows
    // the check; one that arrives while it is shown gets the moment.
    val morph = remember { Animatable(if (done) 1f else 0f) }
    val glow = remember { Animatable(0f) }
    LaunchedEffect(done, calm) {
        if (!done) {
            morph.snapTo(0f)
            glow.snapTo(0f)
            return@LaunchedEffect
        }
        if (calm || morph.value == 1f) {
            morph.snapTo(1f)
            glow.snapTo(0f)
            return@LaunchedEffect
        }
        launch {
            glow.animateTo(1f, tween(CHECK_MS))
            delay(GLOW_HOLD_MS)
            glow.animateTo(0f, tween(GLOW_FADE_MS))
        }
        morph.animateTo(1f, tween(CHECK_MS, easing = FastOutSlowInEasing))
    }

    val progress = (phase as? DownloadPhase.Downloading)?.progress
    val waiting = phase == DownloadPhase.Queued || (phase is DownloadPhase.Downloading && progress == null)
    val turn: State<Float>? = if (waiting && !calm) {
        rememberInfiniteTransition(label = "download wait")
            .animateFloat(0f, 360f, infiniteRepeatable(tween(TURN_MS, easing = LinearEasing)), label = "turn")
    } else {
        null
    }
    val pulse: State<Float>? = if (phase == DownloadPhase.Adding && !calm) {
        rememberInfiniteTransition(label = "download adding")
            .animateFloat(1f, 0.45f, infiniteRepeatable(tween(PULSE_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    } else {
        null
    }

    // The fill never goes back, even when an answer does.
    val highest = remember { mutableFloatStateOf(0f) }
    val target = when {
        phase == DownloadPhase.Adding || done -> 1f
        progress != null -> progress.coerceIn(0f, 1f)
        else -> 0f
    }
    val goal = max(target, highest.floatValue)
    SideEffect { highest.floatValue = goal }
    val fill by animateFloatAsState(
        goal,
        if (calm) snap() else tween(FILL_MS, easing = LinearOutSlowInEasing),
        label = "download fill",
    )

    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            val fade = 1f - morph.value
            if (fade <= 0f) return@Canvas
            val stroke = Stroke2.toPx()
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawCircle(Track.copy(alpha = Track.alpha * fade), radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
            if (waiting) {
                drawArc(
                    Color.White.copy(alpha = fade),
                    startAngle = (turn?.value ?: 0f) - 90f,
                    sweepAngle = WAITING_SWEEP,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            } else if (fill > 0f) {
                drawArc(
                    Color.White.copy(alpha = fade * (pulse?.value ?: 1f)),
                    startAngle = -90f,
                    sweepAngle = 360f * fill,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            // The plus the button showed, small inside the ring, so it is
            // still the same action on its way. It fades as the check comes.
            val arm = size.minDimension * PLUS_ARM
            val plus = Color.White.copy(alpha = fade)
            drawLine(plus, center - Offset(arm, 0f), center + Offset(arm, 0f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(plus, center - Offset(0f, arm), center + Offset(0f, arm), strokeWidth = stroke, cap = StrokeCap.Round)
        }
        if (done) DrawnCheck(drawn = { morph.value }, glow = { glow.value }, Modifier.matchParentSize())
    }
}

// The check a download becomes, drawn in from its short stroke to its long
// one. It glows the way GlowIcon does, only while it arrives.
@Composable
private fun DrawnCheck(drawn: () -> Float, glow: () -> Float, modifier: Modifier = Modifier) {
    val path = remember { Path() }
    val part = remember { Path() }
    val measure = remember { PathMeasure() }
    val draw: DrawScope.() -> Unit = {
        path.reset()
        path.moveTo(size.width * 0.24f, size.height * 0.52f)
        path.lineTo(size.width * 0.42f, size.height * 0.70f)
        path.lineTo(size.width * 0.78f, size.height * 0.33f)
        measure.setPath(path, false)
        part.reset()
        measure.getSegment(0f, measure.length * drawn().coerceIn(0f, 1f), part, true)
        drawPath(part, Color.White, style = Stroke(Stroke2.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    Box(modifier) {
        if (glow() > 0f) {
            Canvas(Modifier.matchParentSize().blur(10.dp, BlurredEdgeTreatment.Unbounded).alpha(0.6f * glow()), onDraw = draw)
            Canvas(Modifier.matchParentSize().blur(3.dp, BlurredEdgeTreatment.Unbounded).alpha(0.7f * glow()), onDraw = draw)
        }
        Canvas(Modifier.matchParentSize(), onDraw = draw)
    }
}

// A download that failed: a small circle with an exclamation mark.
@Composable
internal fun DownloadAlert(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke2.toPx()
        val radius = size.minDimension * 0.42f - stroke / 2
        drawCircle(Color.White, radius = radius, style = Stroke(stroke))
        drawLine(
            Color.White,
            start = Offset(center.x, center.y - radius * 0.5f),
            end = Offset(center.x, center.y + radius * 0.1f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(Color.White, radius = stroke * 0.7f, center = Offset(center.x, center.y + radius * 0.48f))
    }
}

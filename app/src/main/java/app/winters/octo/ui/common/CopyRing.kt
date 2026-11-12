package app.winters.octo.ui.common

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.winters.octo.design.LocalReduceMotion
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoDuration
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoMotion
import app.winters.octo.server.CopyRunState
import app.winters.octo.server.CopyStep
import app.winters.octo.ui.nav.BreathRested
import app.winters.octo.ui.nav.PausedLowAlpha
import app.winters.octo.ui.nav.PausedStillAlpha
import app.winters.octo.ui.nav.PausedTrackAlpha
import app.winters.octo.ui.nav.rememberPauseBreath

// A ring around the cover of an album that just came, filling as the copy
// goes; it turns while how far cannot be told yet and breathes while the
// library saves. With no copy running (`run` null) it is drawn still, and
// faint once the copy `stopped`. Motion reduced, it never turns or breathes.
// Only a ring that turns keeps a clock running.
@Composable
internal fun CopyRing(run: CopyRunState?, size: Dp, modifier: Modifier = Modifier, stopped: Boolean = false) {
    val still = LocalReduceMotion.current
    val big = size >= 96.dp
    val stroke = if (big) 3.dp else 2.dp
    val gap = if (big) 6.dp else if (size >= 48.dp) 4.dp else 3.dp
    val fraction = run?.fraction
    val turning = run != null && fraction == null
    val saving = run?.step == CopyStep.Save
    val shown by animateFloatAsState(fraction ?: 0f, OctoMotion.calm(), label = "copy ring")
    val breath = rememberPauseBreath(saving && !still)
    val accent = OctoColors.Accent
    val muted = OctoColors.TextMuted
    val semantics = when {
        run == null -> Modifier
        fraction == null -> Modifier.progressSemantics()
        else -> Modifier.progressSemantics(fraction)
    }
    Box(modifier.size(size).then(semantics), contentAlignment = Alignment.Center) {
        CopyCover(copyCoverOf(run), size - (stroke + gap) * 2, stopped)
        Canvas(Modifier.fillMaxSize()) {
            val width = stroke.toPx()
            val track = (if (stopped) muted else accent).copy(alpha = PausedTrackAlpha)
            ringArc(track, 0f, 360f, width, round = false)
            when {
                run == null -> Unit
                // Motion reduced, the arc that would turn holds at the top.
                turning -> {
                    if (still) ringArc(accent, -90f, TURNING_SWEEP, width)
                }
                else -> {
                    val alpha = when {
                        !saving -> 1f
                        still || breath.floatValue == BreathRested -> PausedStillAlpha
                        else -> PausedLowAlpha + (1f - PausedLowAlpha) * breath.floatValue
                    }
                    ringArc(accent.copy(alpha = alpha), -90f, 360f * shown, width)
                }
            }
        }
        if (turning && !still) TurningArc(accent, stroke)
    }
}

// The arc going round while how far the copy is cannot be told yet.
@Composable
private fun TurningArc(color: Color, stroke: Dp) {
    val turn = rememberInfiniteTransition(label = "copy turn")
    val angle by turn.animateFloat(0f, 360f, infiniteRepeatable(tween(TURN_MS, easing = LinearEasing)), label = "copy angle")
    Canvas(Modifier.fillMaxSize()) { ringArc(color, angle - 90f, TURNING_SWEEP, stroke.toPx()) }
}

// An arc of the ring, inset by half its width so it sits inside the box.
private fun DrawScope.ringArc(color: Color, start: Float, sweep: Float, width: Float, round: Boolean = true) {
    drawArc(
        color,
        start,
        sweep,
        useCenter = false,
        topLeft = Offset(width / 2, width / 2),
        size = Size(size.width - width, size.height - width),
        style = Stroke(width, cap = if (round) StrokeCap.Round else StrokeCap.Butt),
    )
}

// How much of the ring the turning arc covers, in degrees, and one turn's time.
private const val TURNING_SWEEP = 80f
private const val TURN_MS = 1_400

// The cover the ring shows: an album's once albums are being built, none
// before, so the ring starts on its accent note rather than whatever color
// the first song read happens to have.
internal fun copyCoverOf(run: CopyRunState?): String? = run?.takeIf { it.step >= CopyStep.Build }?.artwork

// The cover inside the ring, fading from one album to the next; a quiet
// note in the accent until the first one comes, muted once the copy stopped.
@Composable
private fun CopyCover(ref: String?, size: Dp, stopped: Boolean) {
    Crossfade(ref, animationSpec = tween(OctoDuration.Neutral), label = "copy cover") { art ->
        if (art != null) {
            Artwork(art, size, shape = CircleShape, rim = false)
        } else {
            val ground = if (stopped) OctoColors.BackgroundTertiary else OctoColors.AccentTonal
            val mark = if (stopped) OctoColors.TextMuted else OctoColors.Accent
            Box(Modifier.size(size).clip(CircleShape).background(ground), contentAlignment = Alignment.Center) {
                Icon(painterResource(OctoIcons.Songs), contentDescription = null, tint = mark, modifier = Modifier.size(size * 0.36f))
            }
        }
    }
}

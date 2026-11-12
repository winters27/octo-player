package app.winters.octo.ui.settings

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.winters.octo.design.LocalReduceMotion
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoMotion
import app.winters.octo.design.OctoType
import app.winters.octo.design.Spinner
import app.winters.octo.server.ADD_SHARE
import app.winters.octo.server.BUILD_SHARE
import app.winters.octo.server.COPY_NOTICE_ASK
import app.winters.octo.server.CopyRunState
import app.winters.octo.server.CopyStep
import app.winters.octo.server.READ_SHARE
import app.winters.octo.server.copyCount
import app.winters.octo.server.copyDescription
import app.winters.octo.server.copyDoing
import app.winters.octo.ui.common.InCaption
import app.winters.octo.ui.common.QuietButton
import app.winters.octo.ui.common.QuietButtonInset
import app.winters.octo.ui.nav.BreathRested
import app.winters.octo.ui.nav.PausedLowAlpha
import app.winters.octo.ui.nav.PausedStillAlpha
import app.winters.octo.ui.nav.PausedTrackAlpha
import app.winters.octo.ui.nav.rememberPauseBreath
import app.winters.octo.ui.settings.rows.RowFrame

// A copy under way, in Sync now's place: what it is doing, its count, and
// a bar of how far the whole copy is, there from the first step to the
// last so the row never changes height. A copy of only part of the library
// shows a spinner instead.
@Composable
internal fun CopyProgressRow(run: CopyRunState) {
    val description = copyDescription(run)
    RowFrame(SettingsIndex.SyncNow) {
        Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = description }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(copyDoing(run.step, run.server), style = OctoType.body, color = OctoColors.TextPrimary, modifier = Modifier.weight(1f))
                copyCount(run)?.let {
                    Spacer(Modifier.width(12.dp))
                    Text(it, style = OctoType.bodySmall.copy(fontFeatureSettings = "tnum"), color = OctoColors.TextSecondary, maxLines = 1)
                }
            }
            if (run.whole) CopyBar(run.fraction, run.step, Modifier.padding(top = 10.dp))
        }
        if (!run.whole) Spinner(size = 18.dp, color = OctoColors.Accent)
    }
}

// One quiet line under the copy's row offering a notification while it runs.
@Composable
internal fun CopyNoticeAskRow(onAllow: () -> Unit) {
    RowFrame(null, minHeight = 0.dp) {
        Text(COPY_NOTICE_ASK, style = OctoType.caption, color = OctoColors.TextMuted, modifier = Modifier.weight(1f))
        Box(Modifier.offset(x = QuietButtonInset)) { QuietButton("Allow", enabled = true, style = InCaption, onClick = onAllow) }
    }
}

// How far the whole copy is, as a thin bar cut into its steps' shares over
// a faint track. While how far cannot be told yet a short piece slides
// along it, and over the full bar while the steps after the copy run; the
// fill breathes while the library saves. Motion reduced, the piece holds at
// the start and nothing breathes. Only a sliding piece keeps a clock running.
@Composable
private fun CopyBar(fraction: Float?, step: CopyStep, modifier: Modifier = Modifier) {
    val still = LocalReduceMotion.current
    val settling = step == CopyStep.Settle
    val sliding = fraction == null || settling
    val shown by animateFloatAsState(fraction ?: 0f, OctoMotion.calm(), label = "copy bar")
    val breath = rememberPauseBreath(step == CopyStep.Save && !still)
    val accent = OctoColors.Accent
    val semantics = if (fraction == null) Modifier.progressSemantics() else Modifier.progressSemantics(fraction)
    Box(modifier.fillMaxWidth().height(4.dp).then(semantics)) {
        Canvas(Modifier.fillMaxSize()) {
            barSpan(accent.copy(alpha = PausedTrackAlpha), 0f, 1f)
            val alpha = when {
                settling -> PausedStillAlpha
                step != CopyStep.Save -> 1f
                still || breath.floatValue == BreathRested -> PausedStillAlpha
                else -> PausedLowAlpha + (1f - PausedLowAlpha) * breath.floatValue
            }
            if (fraction != null) barSpan(accent.copy(alpha = alpha), 0f, shown)
            if (sliding && still) barSpan(accent, 0f, SLIDE_PART)
        }
        if (sliding && !still) SlidingPiece(accent)
    }
}

// The piece going along the bar while how far the copy is cannot be told.
@Composable
private fun SlidingPiece(color: Color) {
    val slide = rememberInfiniteTransition(label = "copy slide")
    val at by slide.animateFloat(-SLIDE_PART, 1f, infiniteRepeatable(tween(SLIDE_MS, easing = FastOutSlowInEasing)), label = "copy slide at")
    Canvas(Modifier.fillMaxSize()) { barSpan(color, at, at + SLIDE_PART) }
}

// The bar's steps, as shares of the whole: reading, building, adding, then
// saving, each its own rounded piece.
private val STEP_EDGES = floatArrayOf(0f, READ_SHARE, READ_SHARE + BUILD_SHARE, READ_SHARE + BUILD_SHARE + ADD_SHARE, 1f)

// Draws the bar from `from` to `to` (shares of its width) inside each step's
// piece, leaving a small gap between the pieces.
private fun DrawScope.barSpan(color: Color, from: Float, to: Float) {
    val gap = STEP_GAP.toPx()
    val radius = CornerRadius(size.height / 2)
    for (i in 0 until STEP_EDGES.size - 1) {
        val pieceStart = size.width * STEP_EDGES[i] + if (i > 0) gap / 2 else 0f
        val pieceEnd = size.width * STEP_EDGES[i + 1] - if (i < STEP_EDGES.size - 2) gap / 2 else 0f
        val start = maxOf(pieceStart, size.width * from)
        val end = minOf(pieceEnd, size.width * to)
        if (end > start) drawRoundRect(color, Offset(start, 0f), Size(end - start, size.height), radius)
    }
}

private val STEP_GAP = 2.dp

// How much of the bar the sliding piece covers, and one slide's time.
private const val SLIDE_PART = 0.3f
private const val SLIDE_MS = 1_400

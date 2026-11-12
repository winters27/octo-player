package app.winters.octo.desktop.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconSize
import app.winters.octo.design.LocalReduceMotion
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoDuration
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoMotion
import app.winters.octo.design.OctoType
import app.winters.octo.design.Spinner
import app.winters.octo.design.Txt
import androidx.compose.runtime.collectAsState
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.library.Cover
import app.winters.octo.desktop.library.LibraryState
import app.winters.octo.server.CopyRunState
import app.winters.octo.server.CopyStep
import app.winters.octo.server.READ_FAILED_TITLE
import app.winters.octo.server.READ_STEPS
import app.winters.octo.server.READ_TITLE
import app.winters.octo.server.StepState
import app.winters.octo.server.copyCount
import app.winters.octo.server.copyStoppedAt
import app.winters.octo.server.readDoing
import app.winters.octo.server.readStepLabel
import app.winters.octo.server.stepState

// The read of the library, while nothing of it shows yet: a ring around
// the cover of a song that just came, what the read is doing, and its
// steps with the count of the one under way. `run` null before it starts.
@Composable
fun LibraryReadHero(run: CopyRunState?, modifier: Modifier = Modifier) {
    val step = run?.step ?: CopyStep.Connect
    val server = run?.server.orEmpty()
    Column(modifier.fillMaxWidth().padding(top = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ReadRing(run, 112.dp)
        Spacer(Modifier.height(18.dp))
        Txt(
            READ_TITLE,
            OctoType.headline,
            align = TextAlign.Center,
            modifier = Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "$READ_TITLE. ${readDoing(step, server)}"
            },
        )
        Spacer(Modifier.height(22.dp))
        ReadSteps(run, Modifier.widthIn(max = 360.dp).fillMaxWidth())
    }
}

// The read stopped before the library showed: why, where, and Try again.
@Composable
fun LibraryReadFailed(message: String, step: CopyStep, done: Long, retry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt(READ_FAILED_TITLE, OctoType.headline, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Txt(listOfNotNull(message, copyStoppedAt(step, done)).joinToString(" "), OctoType.bodySmall, OctoColors.TextSecondary, maxLines = 3)
        GlazeCapsule(null, "Try again", retry)
    }
}

// The read under way in one line, for a read again while the library shows:
// "Reading your songs, 48,210 of 200,000".
fun readLine(run: CopyRunState): String {
    val doing = readDoing(run.step, run.server)
    return copyCount(run)?.let { "$doing, $it" } ?: doing
}

// A read again running while the library shows, or null.
@Composable
fun readingAgain(app: AppState): CopyRunState? {
    val store = app.library ?: return null
    val progress by store.progress.collectAsState()
    val state by store.state.collectAsState()
    return progress?.takeIf { state is LibraryState.Ready }
}

// A read again where it was asked for: a small spinner and how far it is.
@Composable
fun ReadAgainLine(run: CopyRunState, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Spinner(size = 16.dp, color = OctoColors.TextSecondary)
        Txt(readLine(run), OctoType.bodySmall.copy(fontFeatureSettings = "tnum"), OctoColors.TextSecondary)
    }
}

// A ring around the cover, filling as the read goes, turning while how far
// cannot be told; held while the library is built. Still with motion reduced.
@Composable
private fun ReadRing(run: CopyRunState?, size: Dp) {
    val still = LocalReduceMotion.current
    val fraction = run?.fraction
    val turning = run != null && run.step < CopyStep.Build && fraction == null
    val shown by animateFloatAsState(fraction ?: 0f, OctoMotion.calm(), label = "read ring")
    val accent = OctoColors.Accent
    val semantics = when {
        run == null -> Modifier
        fraction == null -> Modifier.progressSemantics()
        else -> Modifier.progressSemantics(fraction)
    }
    Box(Modifier.size(size).then(semantics), contentAlignment = Alignment.Center) {
        val inner = size - 18.dp
        Crossfade(run?.artwork, animationSpec = tween(OctoDuration.Neutral), label = "read cover") { cover ->
            if (cover != null) {
                Cover(cover, Modifier.size(inner), shape = CircleShape)
            } else {
                Box(Modifier.size(inner).clip(CircleShape).background(OctoColors.BackgroundTertiary), contentAlignment = Alignment.Center) {
                    Glyph(OctoIcons.Songs, size = inner * 0.36f, tint = OctoColors.TextMuted, exact = true)
                }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val width = 3.dp.toPx()
            val arc = Size(this.size.width - width, this.size.height - width)
            val at = Offset(width / 2, width / 2)
            drawArc(accent.copy(alpha = 0.18f), 0f, 360f, useCenter = false, topLeft = at, size = arc, style = Stroke(width))
            when {
                run == null -> Unit
                // Motion reduced, the arc that would turn holds at the top.
                turning -> {
                    if (still) drawArc(accent, -90f, 80f, useCenter = false, topLeft = at, size = arc, style = Stroke(width, cap = StrokeCap.Round))
                }
                else -> drawArc(
                    accent.copy(alpha = if (run.step >= CopyStep.Build) 0.6f else 1f),
                    -90f,
                    360f * shown,
                    useCenter = false,
                    topLeft = at,
                    size = arc,
                    style = Stroke(width, cap = StrokeCap.Round),
                )
            }
        }
        if (turning && !still) ReadTurningArc()
    }
}

// The arc going round while how far the read is cannot be told yet; only
// this keeps a clock running.
@Composable
private fun ReadTurningArc() {
    val accent = OctoColors.Accent
    val turn = rememberInfiniteTransition(label = "read turn")
    val angle by turn.animateFloat(0f, 360f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "read angle")
    Canvas(Modifier.fillMaxSize()) {
        val width = 3.dp.toPx()
        drawArc(
            accent,
            angle - 90f,
            80f,
            useCenter = false,
            topLeft = Offset(width / 2, width / 2),
            size = Size(size.width - width, size.height - width),
            style = Stroke(width, cap = StrokeCap.Round),
        )
    }
}

// The read's steps, each done, under way or still to come, read as one line.
@Composable
private fun ReadSteps(run: CopyRunState?, modifier: Modifier = Modifier) {
    val at = run?.step ?: CopyStep.Connect
    val server = run?.server.orEmpty()
    Column(modifier.clearAndSetSemantics { contentDescription = run?.let(::readLine) ?: readDoing(at, server) }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (step in READ_STEPS) {
            val state = stepState(step, at)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    when (state) {
                        StepState.Done -> Glyph(OctoIcons.Check, size = IconSize.Table, tint = OctoColors.Accent)
                        StepState.Now -> if (run != null) Spinner(size = 14.dp, color = OctoColors.Accent) else Box(Modifier.size(7.dp).clip(CircleShape).background(OctoColors.Accent))
                        StepState.Later -> {
                            val ring = OctoColors.TextMuted.copy(alpha = 0.5f)
                            Canvas(Modifier.size(9.dp)) { drawCircle(ring, style = Stroke(1.5.dp.toPx())) }
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Txt(
                    readStepLabel(step, server),
                    OctoType.bodySmall,
                    when (state) {
                        StepState.Now -> OctoColors.TextPrimary
                        StepState.Done -> OctoColors.TextSecondary
                        StepState.Later -> OctoColors.TextMuted
                    },
                    modifier = Modifier.weight(1f),
                )
                if (state == StepState.Now && run != null) {
                    copyCount(run)?.let { Txt(it, OctoType.caption.copy(fontFeatureSettings = "tnum"), OctoColors.TextMuted) }
                }
            }
        }
    }
}

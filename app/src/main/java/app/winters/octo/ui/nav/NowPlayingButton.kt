package app.winters.octo.ui.nav

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.winters.octo.R
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.winters.octo.design.GlassPanelDark
import app.winters.octo.design.Glaze
import app.winters.octo.design.OctoColors
import app.winters.octo.design.PauseGlyph
import app.winters.octo.playback.NowPlaying
import app.winters.octo.ui.common.Artwork
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay

// The round button at the end of the bar: the song's artwork behind a play
// or pause sign, with a thin ring showing how far through the song it is.
@Composable
fun NowPlayingButton(
    haze: HazeState,
    now: NowPlaying,
    positionMs: () -> Long,
    onClick: () -> Unit,
    size: Dp = BarHeight,
) {
    var progress by remember { mutableFloatStateOf(0f) }
    val label = stringResource(R.string.now_playing)
    LaunchedEffect(now.trackId, now.isPlaying, now.durationMs) {
        do {
            progress = if (now.durationMs > 0) (positionMs().toFloat() / now.durationMs).coerceIn(0f, 1f) else 0f
            delay(500)
        } while (now.isPlaying)
    }

    GlassPanelDark(
        haze,
        Modifier
            .size(size)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = if (now.isPlaying) "Pause" else "Play",
                onClick = onClick,
            )
            .semantics { contentDescription = label },
    ) {
        Glaze(Modifier.matchParentSize()) {
            if (now.artwork != null) {
                Artwork(now.artwork, size - 12.dp, shape = CircleShape)
                // Darkens the picture so the sign on top stays readable.
                Box(Modifier.size(size - 12.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape))
            }
            if (now.isPlaying) {
                PauseGlyph(OctoColors.TextPrimary)
            } else {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = OctoColors.TextPrimary, modifier = Modifier.size(22.dp))
            }
        }
        if (now.trackId != null) {
            Canvas(Modifier.matchParentSize().padding(3.dp)) {
                drawArc(
                    color = OctoColors.Accent,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
    }
}

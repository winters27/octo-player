package app.winters.octo.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

// A dark film over the theme wash. Floating chrome needs it: artwork passing
// underneath would otherwise wash the panel out. 64% keeps what is behind
// faintly visible; much darker and it stops reading as glass.
private val DarkFilm = Color(red = 8, green = 8, blue = 11).copy(alpha = 0.64f)

private val PanelBlur = HazeBlurStyle {
    backgroundColor(OctoColors.Background)
    blurRadius(backdropBlur(10f))
    noiseFactor(0f)
    colorEffects(emptyList())
    // Where the device has blur turned off the panel goes solid.
    fallbackColorEffect(HazeColorEffect.tint(OctoColors.BackgroundTertiary))
}

// Dark frosted glass for chrome that floats over content, like the tab bar.
fun Modifier.glassPanelDark(backdrop: HazeState, shape: Shape = CircleShape): Modifier = this
    .clip(shape)
    .hazeBlur(input = HazeInput.Backdrop(backdrop), style = PanelBlur)
    .background(OctoColors.BackgroundSecondary)
    .background(DarkFilm)

// A floating dark panel with its soft drop shadow.
@Composable
fun GlassPanelDark(
    backdrop: HazeState,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        // The shadow is cast by a shape 12dp smaller on every side, so it
        // pools under the panel instead of haloing around it.
        Box(
            Modifier
                .matchParentSize()
                .padding(12.dp)
                .dropShadow(
                    shape,
                    Shadow(
                        radius = shadowBlur(24f),
                        color = Color.Black.copy(alpha = 0.45f),
                        offset = DpOffset(0.dp, 8.dp),
                    ),
                ),
        )
        Box(Modifier.matchParentSize().glassPanelDark(backdrop, shape))
        content()
    }
}

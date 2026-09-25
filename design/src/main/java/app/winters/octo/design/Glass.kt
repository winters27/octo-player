package app.winters.octo.design

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

// Dark frosted glass for the bar, dialogs and floating buttons.
val DarkGlass: HazeBlurStyle = HazeBlurStyle {
    backgroundColor(OctoColors.Background)
    colorEffects(
        listOf(
            HazeColorEffect.tint(OctoColors.GlassBase.copy(alpha = 0.6f)),
            HazeColorEffect.tint(Color.White.copy(alpha = 0.05f)),
        ),
    )
    blurRadius(25.dp)
    noiseFactor(0.08f)
    // Drawn instead of blur where the device has blur turned off.
    fallbackColorEffect(
        HazeColorEffect.tint(OctoColors.GlassFallback.copy(alpha = 0.85f)),
    )
}

private val glassEdge = Brush.verticalGradient(
    listOf(Color.White.copy(alpha = 0.25f), Color.White.copy(alpha = 0.03f)),
)

// Frosted panel: blurs whatever is behind it and adds a soft light rim.
fun Modifier.glass(
    state: HazeState,
    shape: Shape = RoundedCornerShape(999.dp),
    style: HazeBlurStyle = DarkGlass,
): Modifier = this
    .clip(shape)
    .hazeBlur(input = HazeInput.Backdrop(state), style = style)
    .border(width = 1.dp, brush = glassEdge, shape = shape)

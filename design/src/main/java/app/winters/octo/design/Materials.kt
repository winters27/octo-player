package app.winters.octo.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

// Floating dark glass: for chrome over artwork or video, where the clear
// glaze would let a bright picture wash out the words on it. A stronger
// grey film, a heavier frost and richer colour.
val DarkGlassFilm = Color(0xFF282828).copy(alpha = 0.5f)
const val DarkGlassFrost = 32f
const val DarkGlassSaturation = 1.4f

// The glaze made into floating dark glass. The rim, specular and pooled
// shadow are the glaze's; only the film and the frost differ.
@Composable
fun DarkGlaze(
    backdrop: HazeState,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    content: @Composable BoxScope.() -> Unit,
) {
    FloatingGlaze(
        backdrop = backdrop,
        modifier = modifier,
        shape = shape,
        film = DarkGlassFilm,
        frost = DarkGlassFrost,
        saturation = DarkGlassSaturation,
        content = content,
    )
}

// The film under the LCD glass, when it has nothing to frost.
val LcdGlassFill = Color(0xFF161618).copy(alpha = 0.72f)

private val LcdBlur by lazy { lcdBlur() }

private fun lcdBlur() = HazeBlurStyle {
    backgroundColor(OctoColors.Background)
    blurRadius(backdropBlur(GlazeFrost * 4f))
    noiseFactor(0f)
    colorEffects(listOf(HazeColorEffect.colorFilter(ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.2f) }))))
    fallbackColorEffect(HazeColorEffect.tint(OctoColors.BackgroundTertiary))
}

// LCD glass, for a compact player: a thick pane with a bright line round
// it, a shine along its top inside edge, weight pooling at its bottom, a
// faint glow inside, and a drop shadow under it. Pass a backdrop when it
// floats over content, so it frosts what is behind it.
@Composable
fun LcdGlass(
    modifier: Modifier = Modifier,
    shape: Shape = OctoShapes.Chrome,
    backdrop: HazeState? = null,
    fill: Color = LcdGlassFill,
    focused: Boolean = LocalWindowFocused.current,
    content: @Composable BoxScope.() -> Unit,
) {
    val blur = LcdBlur
    Box(
        modifier.dropShadow(shape, Shadow(radius = shadowBlur(24f), color = Color.Black.copy(alpha = 0.35f), offset = DpOffset(0.dp, 8.dp))),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .then(if (backdrop != null) Modifier.hazeBlur(input = HazeInput.Backdrop(backdrop), style = blur) else Modifier)
                .background(fill)
                // The weight: a shade rising from the bottom inside edge.
                .innerShadow(shape, Shadow(radius = shadowBlur(6f), color = Color.Black.copy(alpha = 0.35f), offset = DpOffset(0.dp, (-2).dp)))
                .then(
                    if (focused) {
                        Modifier
                            // The shine along the top inside edge.
                            .innerShadow(shape, Shadow(radius = shadowBlur(5f), color = Color.White.copy(alpha = 0.22f), offset = DpOffset(0.dp, 2.dp)))
                            // A faint glow all round inside.
                            .innerShadow(shape, Shadow(radius = shadowBlur(14f), color = Color.White.copy(alpha = 0.08f)))
                    } else {
                        Modifier
                    },
                )
                .border(1.dp, Color.White.copy(alpha = if (focused) 0.18f else 0.08f), shape),
        )
        content()
    }
}

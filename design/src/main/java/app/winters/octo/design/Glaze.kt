package app.winters.octo.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

// How brightly a glazed surface is lit. Every glazed surface is the same
// material; they differ only in these numbers.
@Immutable
class GlazeLight(
    val ring: Float,
    val inner: Float,
    val spec: Float,
    val specSoft: Float,
    val lift: Float,
) {
    companion object {
        // At rest: a cluster, a selected tab, a control nobody is touching.
        val Rest = GlazeLight(ring = 0.10f, inner = 0.07f, spec = 0.34f, specSoft = 0.14f, lift = 0f)

        // A control under the finger.
        val Lifted = GlazeLight(ring = 0.17f, inner = 0.10f, spec = 0.46f, specSoft = 0.19f, lift = 0.07f)
    }
}

// Mixes two colours channel by channel, the way a stylesheet does.
fun mix(from: Color, to: Color, amount: Float) = Color(
    red = from.red + (to.red - from.red) * amount,
    green = from.green + (to.green - from.green) * amount,
    blue = from.blue + (to.blue - from.blue) * amount,
)

// The film: clear glass with the faintest dim, so whatever is behind (the
// page, or a song's colours) shows through in its own colour without
// glaring when it is bright. The bar can lay a trace of the song's colour
// over it.
val GlazeTint = Color.Black.copy(alpha = 0.16f)

// The film for glass over one smooth field of colour, like the player's
// background: none at all. With no detail behind to blur, a dim reads as a
// darker patch and a wash as a brighter one; bare glass is just its rim.
val GlazeClearFilm = Color.Transparent

// The darker pill that marks the chosen item inside a glaze, like the
// selected tab: a plain shade, with none of the glaze's lighting.
private val GlazeSelectedFill = Color.Black.copy(alpha = 0.72f)

// The specular fades out toward both ends. A rim bright all the way round
// reads as a drawn border; one bright only where light would catch reads as
// a curved surface.
private val SpecularMask = Brush.horizontalGradient(
    listOf(Color.Transparent, Color.Black, Color.Transparent),
)

// The frost the bar and the capsules use, as a CSS blur.
const val GlazeFrost = 4f

private fun glazeBlur(frost: Float) = HazeBlurStyle {
    backgroundColor(OctoColors.Background)
    blurRadius(backdropBlur(frost))
    noiseFactor(0f)
    colorEffects(listOf(HazeColorEffect.colorFilter(saturation(1.1f))))
    fallbackColorEffect(HazeColorEffect.tint(OctoColors.BackgroundTertiary))
}

private val GlazeBlur = glazeBlur(GlazeFrost)

private fun saturation(amount: Float) =
    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(amount) })

private fun accent(alpha: Float) = OctoColors.Accent.copy(alpha = alpha)

// The glaze: a lit glass capsule. Pass a backdrop when it floats over varied
// content, so it frosts what is behind it. Leave it null when it sits on an
// already-frosted or solid surface; blurring that again changes nothing.
// `frost` is how hard the backdrop blurs, as a CSS blur: text behind a menu
// needs more than the page behind the bar.
@Composable
fun Glaze(
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    light: GlazeLight = GlazeLight.Rest,
    backdrop: HazeState? = null,
    film: Color = GlazeTint,
    frost: Float = GlazeFrost,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val blur = remember(frost) { if (frost == GlazeFrost) GlazeBlur else glazeBlur(frost) }
    Box(
        modifier
            // A half-pixel dark contour, so the capsule separates from
            // anything bright behind it.
            .dropShadow(shape, Shadow(radius = 0.dp, spread = 0.5.dp, color = Color.Black.copy(alpha = 0.38f)))
            // A contact line and a small drop: an object resting on the
            // surface, not a hole cut through it.
            .dropShadow(shape, Shadow(radius = 0.dp, color = Color.Black.copy(alpha = 0.05f), offset = DpOffset(0.dp, 1.dp)))
            .dropShadow(shape, Shadow(radius = shadowBlur(3f), color = Color.Black.copy(alpha = 0.10f), offset = DpOffset(0.dp, 1.dp))),
        contentAlignment = Alignment.Center,
    ) {
        // Fill and edge.
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .then(
                    if (backdrop != null) {
                        Modifier.hazeBlur(input = HazeInput.Backdrop(backdrop), style = blur)
                    } else {
                        Modifier
                    },
                )
                .background(film)
                .background(accent(light.lift))
                // Light along the top edge only.
                .innerShadow(shape, Shadow(radius = 0.dp, color = accent(light.ring), offset = DpOffset(0.dp, 1.dp)))
                // A soft inner glow that gives the edge thickness.
                .innerShadow(shape, Shadow(radius = shadowBlur(6f), spread = 1.dp, color = accent(light.inner)))
                // Light thrown back up along the bottom inside edge. It is
                // most of what stops a translucent shape reading as a hole.
                .innerShadow(shape, Shadow(radius = 0.dp, color = Color.White.copy(alpha = 0.10f), offset = DpOffset(0.dp, (-0.5).dp))),
        )
        // Specular: a brighter rim, masked to the middle.
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(SpecularMask, blendMode = BlendMode.DstIn)
                }
                .innerShadow(shape, Shadow(radius = shadowBlur(1.5f), spread = 1.dp, color = accent(light.spec)))
                .innerShadow(shape, Shadow(radius = shadowBlur(3f), spread = 1.dp, color = accent(light.specSoft))),
        )
        content()
    }
}

// A glaze floating over the page, like the bottom bar: the lit glass,
// frosting what scrolls behind it, with a soft shadow pooled underneath.
// The content sits on top; place it with the box's alignment.
@Composable
fun FloatingGlaze(
    backdrop: HazeState,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    film: Color = GlazeTint,
    frost: Float = GlazeFrost,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        // The shadow is cast by a shape 12dp smaller on every side, so it
        // pools under the glass instead of haloing around it.
        Box(
            Modifier
                .matchParentSize()
                .padding(12.dp)
                .dropShadow(
                    shape,
                    Shadow(radius = shadowBlur(24f), color = Color.Black.copy(alpha = 0.45f), offset = DpOffset(0.dp, 8.dp)),
                ),
        )
        Glaze(Modifier.matchParentSize(), shape = shape, backdrop = backdrop, film = film, frost = frost)
        content()
    }
}

// The chosen item inside a glaze: a darker pill set into the glass, unlit,
// so only the glaze around it carries the light.
@Composable
fun GlazeSelected(modifier: Modifier = Modifier, shape: Shape = CircleShape) {
    Box(modifier.clip(shape).background(GlazeSelectedFill))
}

package app.winters.octo.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

private val InsetSpecularMask = Brush.horizontalGradient(
    listOf(Color.Transparent, Color.Black, Color.Transparent),
)

// Glass inside glass: a component sitting in a surface rather than on the
// page. No blur of its own. It casts a shadow onto what it sits in, and
// light comes from above and bounces back up along the bottom edge.
@Composable
fun GlazeInset(
    fill: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .dropShadow(shape, Shadow(radius = 0.dp, spread = 0.5.dp, color = Color.Black.copy(alpha = 0.40f)))
            .dropShadow(shape, Shadow(radius = 0.dp, color = Color.Black.copy(alpha = 0.05f), offset = DpOffset(0.dp, 1.dp)))
            .elevation1(shape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(fill)
                .innerShadow(shape, Shadow(radius = 0.dp, color = Color.White.copy(alpha = 0.10f), offset = DpOffset(0.dp, 1.dp)))
                .innerShadow(shape, Shadow(radius = 0.dp, color = Color.White.copy(alpha = 0.08f), offset = DpOffset(0.dp, (-0.5).dp))),
        )
        // Specular, masked to the middle, at the three-quarter strength
        // nested components use.
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    alpha = 0.75f
                }
                .drawWithContent {
                    drawContent()
                    drawRect(InsetSpecularMask, blendMode = BlendMode.DstIn)
                }
                .innerShadow(shape, Shadow(radius = shadowBlur(1.5f), spread = 1.dp, color = Color.White.copy(alpha = 0.14f)))
                .innerShadow(shape, Shadow(radius = shadowBlur(3f), spread = 1.dp, color = Color.White.copy(alpha = 0.11f))),
        )
        content()
    }
}

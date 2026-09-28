package app.winters.octo.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

// Glass inside glass: a component sitting in a surface rather than on the
// page. No blur of its own; the surface it sits in has already frosted
// what is behind. It casts a contact shadow onto that surface (without it,
// it reads as a hole cut through), has a faint rim all round inside, and
// light bounces back up along its bottom edge. `focused` false (a desktop
// window in the background) turns its specular off.
@Composable
fun GlazeInset(
    fill: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
    focused: Boolean = LocalWindowFocused.current,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            // A half-pixel dark contour against a bright surface.
            .dropShadow(shape, Shadow(radius = 0.dp, spread = 0.5.dp, color = Color.Black.copy(alpha = 0.40f)))
            // The contact shadow: a hard line, then a soft one.
            .dropShadow(shape, Shadow(radius = 0.dp, color = Color.Black.copy(alpha = 0.05f), offset = DpOffset(0.dp, 1.dp)))
            .dropShadow(shape, Shadow(radius = shadowBlur(3f), color = Color.Black.copy(alpha = 0.10f), offset = DpOffset(0.dp, 1.dp))),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(fill)
                // The inner rim, all round.
                .innerShadow(shape, Shadow(radius = 0.dp, spread = 1.dp, color = Color.White.copy(alpha = if (focused) 0.10f else 0.05f)))
                // The light line along the bottom inside edge.
                .innerShadow(shape, Shadow(radius = 0.dp, color = Color.White.copy(alpha = if (focused) 0.10f else 0.04f), offset = DpOffset(0.dp, (-0.5).dp))),
        )
        // Specular, masked to the middle, at the three-quarter strength
        // nested components use.
        if (focused) Specular(shape, Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.11f), strength = 0.75f)
        content()
    }
}

package app.winters.octo.design

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

// The standard panel for a card on the page. No blur: the page behind it
// is a flat colour, and blurring a flat colour changes nothing.
fun Modifier.glassPanel(shape: Shape): Modifier = this
    // A dark half-pixel just outside the edge separates it from the page.
    .dropShadow(shape, Shadow(radius = 0.dp, spread = 0.5.dp, color = Color.Black.copy(alpha = 0.40f)))
    .elevation1(shape)
    .clip(shape)
    .background(OctoColors.BackgroundSecondary)
    // Light along the top edge only.
    .innerShadow(shape, Shadow(radius = 0.dp, color = Color.White.copy(alpha = 0.08f), offset = DpOffset(0.dp, 1.dp)))

// The lowest rung of the shadow ladder: a tight contact shadow, then a
// softer one.
internal fun Modifier.elevation1(shape: Shape): Modifier = this
    .dropShadow(shape, Shadow(radius = shadowBlur(2f), color = Color.Black.copy(alpha = 0.20f), offset = DpOffset(0.dp, 1.dp)))
    .dropShadow(shape, Shadow(radius = shadowBlur(6f), color = Color.Black.copy(alpha = 0.12f), offset = DpOffset(0.dp, 2.dp)))

// The top rung, for a picture that floats above the page.
fun Modifier.elevation3(shape: Shape): Modifier = this
    .dropShadow(shape, Shadow(radius = shadowBlur(4f), color = Color.Black.copy(alpha = 0.25f), offset = DpOffset(0.dp, 2.dp)))
    .dropShadow(shape, Shadow(radius = shadowBlur(16f), color = Color.Black.copy(alpha = 0.20f), offset = DpOffset(0.dp, 8.dp)))
    .dropShadow(shape, Shadow(radius = shadowBlur(32f), color = Color.Black.copy(alpha = 0.15f), offset = DpOffset(0.dp, 16.dp)))

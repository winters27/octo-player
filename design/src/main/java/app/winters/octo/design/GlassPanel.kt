package app.winters.octo.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

// The standard panel for a card on the page. No blur: the page behind it
// is a flat colour, and blurring a flat colour changes nothing. On a light
// theme the card is solid, or the shadows under it show through and gray it.
@Composable
fun Modifier.glassPanel(shape: Shape): Modifier = this
    // A dark half-pixel just outside the edge separates it from the page.
    .dropShadow(shape, Shadow(radius = 0.dp, spread = 0.5.dp, color = shadowColor(0.40f)))
    .elevation1(shape)
    .clip(shape)
    .background(if (LocalOctoSurfaces.current.light) OctoColors.BackgroundTertiary else OctoColors.BackgroundSecondary)
    // Light along the top edge only.
    .innerShadow(shape, Shadow(radius = 0.dp, color = glassRim(0.08f), offset = DpOffset(0.dp, 1.dp)))

// A panel that holds rows to read and change, as settings do: a solid
// surface a clear step up from the page, with a faint rim, so each group
// stands apart and the lines between its rows show.
@Composable
fun Modifier.raisedPanel(shape: Shape): Modifier = this
    .dropShadow(shape, Shadow(radius = 0.dp, spread = 0.5.dp, color = shadowColor(0.40f)))
    .elevation1(shape)
    .clip(shape)
    .background(OctoColors.SurfaceRaised)
    .border(1.dp, glassRim(0.06f), shape)
    .innerShadow(shape, Shadow(radius = 0.dp, color = glassRim(0.06f), offset = DpOffset(0.dp, 1.dp)))

// The lowest rung of the shadow ladder: a tight contact shadow, then a
// softer one.
@Composable
internal fun Modifier.elevation1(shape: Shape): Modifier = this
    .dropShadow(shape, Shadow(radius = shadowBlur(2f), color = shadowColor(0.20f), offset = DpOffset(0.dp, 1.dp)))
    .dropShadow(shape, Shadow(radius = shadowBlur(6f), color = shadowColor(0.12f), offset = DpOffset(0.dp, 2.dp)))

// The top rung, for a picture that floats above the page.
@Composable
fun Modifier.elevation3(shape: Shape): Modifier = this
    .dropShadow(shape, Shadow(radius = shadowBlur(4f), color = shadowColor(0.25f), offset = DpOffset(0.dp, 2.dp)))
    .dropShadow(shape, Shadow(radius = shadowBlur(16f), color = shadowColor(0.20f), offset = DpOffset(0.dp, 8.dp)))
    .dropShadow(shape, Shadow(radius = shadowBlur(32f), color = shadowColor(0.15f), offset = DpOffset(0.dp, 16.dp)))

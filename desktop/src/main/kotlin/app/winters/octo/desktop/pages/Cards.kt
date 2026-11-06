package app.winters.octo.desktop.pages

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.winters.octo.design.OctoColors
import app.winters.octo.design.glassRim
import app.winters.octo.design.shadowColor
import app.winters.octo.desktop.ui.keyRim

// A card lifted off the page: a clearer glass than the page's other
// panels, with a contour, a soft shadow, light along the top, and a rim
// with a fifth of the key colour in it.
@Composable
internal fun Modifier.settingsSurface(shape: Shape, key: Color): Modifier {
    val fill = OctoColors.TextPrimary.copy(alpha = CARD_FILL)
    val top = glassRim(0.08f)
    return this
        .dropShadow(shape, Shadow(radius = 0.dp, spread = 0.5.dp, color = shadowColor(0.40f)))
        .dropShadow(shape, Shadow(radius = 6.dp, color = shadowColor(0.20f), offset = DpOffset(0.dp, 6.dp)))
        .dropShadow(shape, Shadow(radius = 20.dp, color = shadowColor(0.10f)))
        .clip(shape)
        .background(fill)
        .innerShadow(shape, Shadow(radius = 0.dp, spread = 1.dp, color = keyRim(key, alpha = 0.10f)))
        .innerShadow(shape, Shadow(radius = 0.dp, color = top, offset = DpOffset(0.dp, 1.dp)))
}

private const val CARD_FILL = 0.06f

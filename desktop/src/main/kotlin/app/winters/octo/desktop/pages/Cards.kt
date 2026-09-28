package app.winters.octo.desktop.pages

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
import app.winters.octo.desktop.ui.keyRim

// A card lifted off the page: a clearer glass than the page's other
// panels, with a contour, a soft shadow, light along the top, and a rim
// with a fifth of the key colour in it.
internal fun Modifier.settingsSurface(shape: Shape, key: Color): Modifier = this
    .dropShadow(shape, Shadow(radius = 0.dp, spread = 0.5.dp, color = Color.Black.copy(alpha = 0.40f)))
    .dropShadow(shape, Shadow(radius = 6.dp, color = Color.Black.copy(alpha = 0.20f), offset = DpOffset(0.dp, 6.dp)))
    .dropShadow(shape, Shadow(radius = 20.dp, color = Color.Black.copy(alpha = 0.10f)))
    .clip(shape)
    .background(CardFill)
    .innerShadow(shape, Shadow(radius = 0.dp, spread = 1.dp, color = keyRim(key, alpha = 0.10f)))
    .innerShadow(shape, Shadow(radius = 0.dp, color = Color.White.copy(alpha = 0.08f), offset = DpOffset(0.dp, 1.dp)))

private val CardFill = Color.White.copy(alpha = 0.06f)

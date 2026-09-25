package app.winters.octo.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.dp

val ArtworkShape = RoundedCornerShape(6.dp)

// A faint line on the artwork itself. Cards carry no outline; the picture
// is what gets the edge.
fun Modifier.artworkRim(shape: Shape): Modifier =
    innerShadow(shape, Shadow(radius = 0.dp, spread = 1.dp, color = Color(0xFFC8C8C8).copy(alpha = 0.16f)))

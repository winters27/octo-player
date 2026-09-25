package app.winters.octo.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Two rounded bars: the pause sign, drawn so it matches the rounded icons.
@Composable
fun PauseGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Canvas(modifier.size(size)) {
        val bar = this.size.width * 0.26f
        val gap = this.size.width * 0.18f
        val height = this.size.height * 0.72f
        val top = (this.size.height - height) / 2
        val left = (this.size.width - (bar * 2 + gap)) / 2
        val radius = CornerRadius(bar / 2.5f)
        drawRoundRect(color, Offset(left, top), Size(bar, height), radius)
        drawRoundRect(color, Offset(left + bar + gap, top), Size(bar, height), radius)
    }
}

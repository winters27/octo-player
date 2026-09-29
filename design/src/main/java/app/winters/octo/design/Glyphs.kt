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

// Two rounded bars: the pause sign, in the same square, proportions and
// scale as the Phosphor pause (sym_pause, drawn at 0.8 by tools/icons), so
// it swaps with the play icon at one size.
@Composable
fun PauseGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Canvas(modifier.size(size)) {
        // Phosphor's 256 square, scaled by 0.8 about its middle.
        val unit = this.size.width / 256f * 0.8f
        val inset = this.size.width * 0.1f
        val radius = CornerRadius(16f * unit)
        drawRoundRect(color, Offset(inset + 40f * unit, inset + 32f * unit), Size(72f * unit, 192f * unit), radius)
        drawRoundRect(color, Offset(inset + 144f * unit, inset + 32f * unit), Size(72f * unit, 192f * unit), radius)
    }
}

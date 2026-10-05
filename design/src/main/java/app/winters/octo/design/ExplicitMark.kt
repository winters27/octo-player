package app.winters.octo.design

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Shared by the phone and the desktop, so an explicit song is marked the
// same way in both.

// The small "E" after the title of a song marked explicit, as Apple Music
// marks one: a muted tile with the letter cut out of it, so whatever is
// behind (the page, a picked row's pill, the player's picture) shows
// through the letter. Quiet beside the title, and as tall as a line of
// small text, so it grows with the listener's text size. A clean edit
// gets no mark; screen readers hear "Explicit".
@Composable
fun ExplicitMark(modifier: Modifier = Modifier, color: Color = OctoColors.TextMuted) {
    val side = with(LocalDensity.current) { MarkSide.toDp() }
    val measurer = rememberTextMeasurer()
    Spacer(
        modifier
            .size(side)
            // The letter is cut out of the tile in a layer of its own.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val letter = measurer.measure("E", LetterStyle)
                val corner = CornerRadius(size.minDimension * CornerShare)
                val at = Offset((size.width - letter.size.width) / 2f, (size.height - letter.size.height) / 2f)
                onDrawBehind {
                    drawRoundRect(color, cornerRadius = corner)
                    drawText(letter, Color.Black, at, blendMode = BlendMode.DstOut)
                }
            }
            .clearAndSetSemantics { contentDescription = "Explicit" },
    )
}

// The tile's side, sized as text is so it follows the text size, how round
// its corners are (3 of its 14), and the letter.
private val MarkSide = 14.sp
private const val CornerShare = 3f / 14f
private val LetterStyle = TextStyle(fontSize = 9.5.sp, lineHeight = 9.5.sp, fontWeight = FontWeight.Bold)

// The room between a title and its mark.
val ExplicitMarkGap = 6.dp

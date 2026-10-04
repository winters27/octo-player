package app.winters.octo.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// StreamNook's tooltip: its near black glass ink with a hairline of white
// round it. StreamNook frosts what is behind at 80%; a pop-up here cannot,
// so the ink is a little thicker to read as well.
val TooltipFill = Color(0xFF09090B).copy(alpha = 0.92f)

private val TooltipShape = OctoShapes.ChromeSmall

// How long the pointer rests on a control before its tooltip shows.
const val TooltipDelayMs = 250L

// How long a tooltip takes to fade in, and out.
const val TooltipInMs = 90
const val TooltipOutMs = 60

// How far from what it names a tooltip sits, how far it drifts in from,
// and how close it may come to the window's edge.
val TooltipGap = 8.dp
val TooltipDrift = 6.dp
val TooltipEdge = 12.dp

// A small dark bubble naming a control, at most 320 dp wide, its words
// centred. Desktop only: its showing and fading live with the desktop's
// OctoTooltip.
@Composable
fun TooltipBubble(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .widthIn(max = 320.dp)
            .dropShadow(TooltipShape, Shadow(radius = shadowBlur(25f), spread = (-5).dp, color = Color.Black.copy(alpha = 0.30f), offset = DpOffset(0.dp, 12.dp)))
            .clip(TooltipShape)
            .background(TooltipFill)
            .border(1.dp, Color.White.copy(alpha = 0.10f), TooltipShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        BasicText(
            text,
            style = OctoType.caption.copy(color = OctoInk.Primary, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
        )
    }
}

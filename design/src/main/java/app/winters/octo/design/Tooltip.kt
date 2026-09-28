package app.winters.octo.design

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// A tooltip's fill: a tenth of the accent in a near black, with a faint
// sheen falling from its top.
val TooltipFill = mix(Color(0xFF121214), OctoColors.Accent, 0.10f)

private val TooltipShape = OctoShapes.Chrome

// How long a tooltip takes to fade in.
const val TooltipFadeMs = 120

// How far below what it names a tooltip sits.
val TooltipGap = 8.dp

// A small dark bubble naming a control, at most 320 dp wide. It fades in
// as it appears.
@Composable
fun TooltipBubble(text: String, modifier: Modifier = Modifier) {
    val shown = remember { Animatable(0f) }
    val motion = motionScale()
    LaunchedEffect(Unit) { shown.animateTo(1f, octoTween(motion, TooltipFadeMs)) }
    Box(
        modifier
            .graphicsLayer { alpha = shown.value }
            .widthIn(max = 320.dp)
            .dropShadow(TooltipShape, Shadow(radius = shadowBlur(24f), color = Color.Black.copy(alpha = 0.32f), offset = DpOffset(0.dp, 12.dp)))
            .clip(TooltipShape)
            .background(TooltipFill)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.09f), Color.White.copy(alpha = 0.01f))))
            // A light line along the top inside edge.
            .innerShadow(TooltipShape, Shadow(radius = 0.dp, color = Color.White.copy(alpha = 0.14f), offset = DpOffset(0.dp, 1.dp)))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        BasicText(text, style = OctoType.caption.copy(color = OctoInk.Primary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium))
    }
}

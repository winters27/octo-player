package app.winters.octo.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

// Names a control in a tooltip bubble when the pointer rests on it for
// half a second, 8 dp below it and centred on it.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OctoTooltip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = { TooltipBubble(text) },
        modifier = modifier,
        delayMillis = 500,
        tooltipPlacement = TooltipPlacement.ComponentRect(
            anchor = Alignment.BottomCenter,
            alignment = Alignment.BottomCenter,
            offset = DpOffset(0.dp, TooltipGap),
        ),
        content = content,
    )
}

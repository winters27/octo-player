package app.winters.octo.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
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

// Words cut to their lines with an ellipsis, as Txt draws them; when they
// are cut, resting the pointer on them shows them whole in a tooltip under
// their start. Words that fit show no tooltip.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CutTxt(
    text: String,
    style: TextStyle,
    color: Color = OctoColors.TextPrimary,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    var cut by remember(text) { mutableStateOf(false) }
    TooltipArea(
        tooltip = { if (cut) TooltipBubble(text) },
        modifier = modifier,
        delayMillis = 500,
        tooltipPlacement = TooltipPlacement.ComponentRect(
            anchor = Alignment.BottomStart,
            alignment = Alignment.BottomEnd,
            offset = DpOffset(0.dp, TooltipGap),
        ),
    ) {
        BasicText(
            text,
            style = style.copy(color = color),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { cut = it.hasVisualOverflow },
        )
    }
}

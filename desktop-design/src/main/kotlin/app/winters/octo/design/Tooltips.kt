package app.winters.octo.design

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider

// Names a control in a tooltip bubble when the pointer rests on it for
// half a second, 8 dp below it and centred on it. It shows at once when
// the keyboard reaches the control too, so what it says is not for the
// mouse alone.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OctoTooltip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val visibility = LocalFocusVisibility.current
    var focused by remember { mutableStateOf(false) }
    Box(modifier.onFocusChanged { focused = it.hasFocus }) {
        TooltipArea(
            tooltip = { TooltipBubble(text) },
            delayMillis = 500,
            tooltipPlacement = TooltipPlacement.ComponentRect(
                anchor = Alignment.BottomCenter,
                alignment = Alignment.BottomCenter,
                offset = DpOffset(0.dp, TooltipGap),
            ),
            content = content,
        )
        if (focused && visibility.keyboard) {
            val gap = with(LocalDensity.current) { TooltipGap.roundToPx() }
            Popup(popupPositionProvider = remember(gap) { BelowCentre(gap) }) { TooltipBubble(text) }
        }
    }
}

// Under the control, centred on it, kept inside the window.
private class BelowCentre(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.center.x - popupContentSize.width / 2
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height) below else anchorBounds.top - gap - popupContentSize.height
        return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)), y.coerceAtLeast(0))
    }
}

// Words cut to their lines with an ellipsis, as Txt draws them; when they
// are cut, resting the pointer on them shows them whole in a tooltip under
// their start. Words that fit show no tooltip. The keyboard shows it too:
// on words that take the keyboard themselves (a link), or inside a control
// that has it (`LocalKeyboardHere`). A screen reader always gets the whole
// words. A title's words are a heading.
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
    var own by remember { mutableStateOf(false) }
    val keyboard = LocalFocusVisibility.current.keyboard
    val here = LocalKeyboardHere.current
    TooltipArea(
        tooltip = { if (cut) TooltipBubble(text) },
        modifier = Modifier.onFocusChanged { own = it.hasFocus }.then(modifier),
        delayMillis = 500,
        tooltipPlacement = TooltipPlacement.ComponentRect(
            anchor = Alignment.BottomStart,
            alignment = Alignment.BottomEnd,
            offset = DpOffset(0.dp, TooltipGap),
        ),
    ) {
        BasicText(
            text,
            modifier = if (isHeading(style)) Modifier.semantics { heading() } else Modifier,
            style = style.copy(color = color),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { cut = it.hasVisualOverflow },
        )
        if (cut && (here || (own && keyboard))) {
            val gap = with(LocalDensity.current) { TooltipGap.roundToPx() }
            Popup(popupPositionProvider = remember(gap) { BelowStart(gap) }) { TooltipBubble(text) }
        }
    }
}

// Under the words, lined up with their start, kept inside the window.
private class BelowStart(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height) below else anchorBounds.top - gap - popupContentSize.height
        return IntOffset(anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)), y.coerceAtLeast(0))
    }
}

// True inside a control that has the keyboard while the keyboard is in
// use, so cut words in it show whole.
val LocalKeyboardHere = compositionLocalOf { false }

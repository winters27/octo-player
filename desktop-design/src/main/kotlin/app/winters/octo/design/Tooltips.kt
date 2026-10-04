package app.winters.octo.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.delay

// Tooltips as StreamNook draws them: a quarter second after the pointer
// rests on a control (at once when the keyboard reaches it), a small dark
// bubble names it, above it by default. It fades in drifting a few dp away
// from the control and grows the last 3%, and fades out quicker still.
// Where it would leave the window it flips to the other side, and it is
// kept 12 dp clear of the window's edges. One shows at a time: a new one
// takes over from the last. A press or a turn of the wheel puts it away
// until the pointer comes back.

// The side of its control a tooltip sits on, before any flip.
enum class TooltipSide { Top, Bottom, Left, Right }

// What a control's tooltip goes by: whether the pointer is on the control
// and whether the control has the focus.
@Stable
class TooltipState internal constructor() {
    internal var hovered by mutableStateOf(false)
    internal var focused by mutableStateOf(false)
}

@Composable
fun rememberTooltipState(): TooltipState = remember { TooltipState() }

// The one tooltip showing, by its state.
private var showing by mutableStateOf<TooltipState?>(null)

// Follows the pointer and the focus for a tooltip. It goes on the control
// itself, ahead of its clickable, so the focus it sees is the control's.
fun Modifier.tooltipTarget(state: TooltipState): Modifier = this
    .onFocusChanged { state.focused = it.hasFocus }
    .pointerInput(state) {
        awaitPointerEventScope {
            // After a press, nothing until the pointer leaves and comes back.
            var put = false
            while (true) {
                val event = awaitPointerEvent()
                when (event.type) {
                    PointerEventType.Enter -> {
                        put = false
                        state.hovered = true
                    }
                    PointerEventType.Move -> if (!put) state.hovered = true
                    PointerEventType.Exit -> {
                        put = false
                        state.hovered = false
                    }
                    PointerEventType.Press, PointerEventType.Scroll -> {
                        put = true
                        state.hovered = false
                    }
                }
            }
        }
    }

// The tooltip itself, placed inside the control it names (its last child),
// for a control that cannot be wrapped in OctoTooltip. `start` lines it up
// with the control's start rather than its middle; `keyboardHere` shows it
// because the control it sits in has the keyboard.
@Composable
fun TooltipPopup(state: TooltipState, text: String, side: TooltipSide = TooltipSide.Top, start: Boolean = false, keyboardHere: Boolean = false) {
    // Nothing to say: nothing to wait for (a table has hundreds of these).
    if (text.isBlank()) return
    val keyboard = LocalFocusVisibility.current.keyboard
    var rested by remember { mutableStateOf(false) }
    LaunchedEffect(state.hovered) {
        rested = false
        if (state.hovered) {
            delay(TooltipDelayMs)
            rested = true
        }
    }
    val wanted = rested || keyboardHere || (state.focused && keyboard)
    LaunchedEffect(wanted) {
        if (wanted) showing = state else if (showing === state) showing = null
    }
    DisposableEffect(state) { onDispose { if (showing === state) showing = null } }
    val shown = wanted && showing === state
    val motion = motionScale()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        if (shown) progress.animateTo(1f, tween(motion.ms(TooltipInMs), easing = LinearOutSlowInEasing))
        else progress.animateTo(0f, tween(motion.ms(TooltipOutMs), easing = FastOutSlowInEasing))
    }
    val drawn by remember { derivedStateOf { progress.value > 0f } }
    if (!shown && !drawn) return
    val density = LocalDensity.current
    val gap = with(density) { TooltipGap.roundToPx() }
    val edge = with(density) { TooltipEdge.roundToPx() }
    val drift = with(density) { motion.travel(TooltipDrift).toPx() }
    var placed by remember { mutableStateOf(side) }
    val placer = remember(side, start, gap, edge) { TooltipPlacer(side, start, gap, edge) { placed = it } }
    Popup(popupPositionProvider = placer) {
        TooltipBubble(
            text,
            Modifier.graphicsLayer {
                val p = progress.value
                alpha = p
                val grow = if (motion.still) 1f else 0.97f + 0.03f * p
                scaleX = grow
                scaleY = grow
                // Drifts in from the control's side, and only on the way in.
                val away = if (shown) drift * (1f - p) else 0f
                when (placed) {
                    TooltipSide.Top -> translationY = away
                    TooltipSide.Bottom -> translationY = -away
                    TooltipSide.Left -> translationX = away
                    TooltipSide.Right -> translationX = -away
                }
            },
        )
    }
}

// Names a control in a tooltip: wrap the control in it. The control should
// still carry its own words for a screen reader.
@Composable
fun OctoTooltip(text: String, modifier: Modifier = Modifier, side: TooltipSide = TooltipSide.Top, content: @Composable () -> Unit) {
    val state = rememberTooltipState()
    Box(modifier.tooltipTarget(state)) {
        content()
        TooltipPopup(state, text, side)
    }
}

// On its side of the control (centred on it, or from its start), flipped
// to the other side when it would leave the window, then slid along to
// stay clear of the window's edges. Says where it went.
private class TooltipPlacer(
    private val side: TooltipSide,
    private val start: Boolean,
    private val gap: Int,
    private val edge: Int,
    private val placed: (TooltipSide) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val spot = tooltipSpot(side, start, anchorBounds, windowSize, popupContentSize, gap, edge)
        placed(spot.side)
        return spot.offset
    }
}

// Where a tooltip goes, and on which side it ended up.
internal data class TooltipSpot(val side: TooltipSide, val offset: IntOffset)

internal fun tooltipSpot(side: TooltipSide, start: Boolean, anchor: IntRect, window: IntSize, size: IntSize, gap: Int, edge: Int): TooltipSpot {
    fun above() = anchor.top - gap - size.height
    fun below() = anchor.bottom + gap
    fun before() = anchor.left - gap - size.width
    fun after() = anchor.right + gap
    val flipped = when (side) {
        TooltipSide.Top -> if (above() < edge) TooltipSide.Bottom else side
        TooltipSide.Bottom -> if (below() + size.height > window.height - edge) TooltipSide.Top else side
        TooltipSide.Left -> if (before() < edge) TooltipSide.Right else side
        TooltipSide.Right -> if (after() + size.width > window.width - edge) TooltipSide.Left else side
    }
    fun along(at: Int, length: Int, room: Int) = at.coerceIn(edge, (room - edge - length).coerceAtLeast(edge))
    val offset = when (flipped) {
        TooltipSide.Top, TooltipSide.Bottom -> {
            val x = if (start) anchor.left else anchor.center.x - size.width / 2
            IntOffset(along(x, size.width, window.width), if (flipped == TooltipSide.Top) above() else below())
        }
        TooltipSide.Left, TooltipSide.Right -> {
            val y = anchor.center.y - size.height / 2
            IntOffset(if (flipped == TooltipSide.Left) before() else after(), along(y, size.height, window.height))
        }
    }
    return TooltipSpot(flipped, offset)
}

// Words cut to their lines with an ellipsis, as Txt draws them; when they
// are cut, resting the pointer on them shows them whole in a tooltip under
// their start. Words that fit show no tooltip. The keyboard shows it too:
// on words that take the keyboard themselves (a link), or inside a control
// that has it (`LocalKeyboardHere`). A screen reader always gets the whole
// words. A title's words are a heading.
@Composable
fun CutTxt(
    text: String,
    style: TextStyle,
    color: Color = OctoColors.TextPrimary,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    var cut by remember(text) { mutableStateOf(false) }
    val state = rememberTooltipState()
    val here = LocalKeyboardHere.current
    Box(Modifier.tooltipTarget(state).then(modifier)) {
        BasicText(
            text,
            modifier = if (isHeading(style)) Modifier.semantics { heading() } else Modifier,
            style = style.copy(color = color),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { cut = it.hasVisualOverflow },
        )
        TooltipPopup(state, if (cut) text else "", TooltipSide.Bottom, start = true, keyboardHere = here)
    }
}

// True inside a control that has the keyboard while the keyboard is in
// use, so cut words in it show whole.
val LocalKeyboardHere = compositionLocalOf { false }

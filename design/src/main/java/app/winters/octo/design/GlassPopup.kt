package app.winters.octo.design

import android.animation.ValueAnimator
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.chrisbanes.haze.HazeState

// Where a pop-up goes. `above` means it flipped over the control that opened
// it, for want of room below; `fromEnd` means it lines up with the control's
// end edge instead of its start. `centred` means there was no control, so it
// sits in the middle of the screen.
@Immutable
data class PopupSpot(
    val x: Int,
    val y: Int,
    val above: Boolean = false,
    val fromEnd: Boolean = false,
    val centred: Boolean = false,
) {
    // The corner it grows from: the one nearest the control, or its middle.
    val origin: TransformOrigin
        get() = if (centred) TransformOrigin.Center else TransformOrigin(if (fromEnd) 1f else 0f, if (above) 1f else 0f)
}

// Places a pop-up of `size` in a window of `window`, keeping `margin` clear of
// every edge and of the system bars (`top` and `bottom` tall). It goes under
// the control, `gap` away, when it fits there; over it when only that fits;
// otherwise on the roomier side, pushed back inside. It lines up with the
// control's start edge, or its end edge when the control is on the far half
// of the screen. With no control it sits in the middle.
fun placePopup(
    anchor: IntRect?,
    size: IntSize,
    window: IntSize,
    margin: Int,
    gap: Int = 0,
    top: Int = 0,
    bottom: Int = 0,
): PopupSpot {
    val left = margin
    val right = window.width - margin
    val roof = top + margin
    val floor = window.height - bottom - margin
    // Too big to fit, it keeps to the start and the top.
    fun fitX(x: Int) = x.coerceIn(left, maxOf(left, right - size.width))
    fun fitY(y: Int) = y.coerceIn(roof, maxOf(roof, floor - size.height))

    if (anchor == null) {
        return PopupSpot(
            x = fitX(left + (right - left - size.width) / 2),
            y = fitY(roof + (floor - roof - size.height) / 2),
            centred = true,
        )
    }
    val fromEnd = anchor.left + anchor.right >= window.width
    val x = fitX(if (fromEnd) anchor.right - size.width else anchor.left)
    val roomBelow = floor - (anchor.bottom + gap)
    val roomAbove = anchor.top - gap - roof
    val above = when {
        size.height <= roomBelow -> false
        size.height <= roomAbove -> true
        else -> roomAbove > roomBelow
    }
    val y = fitY(if (above) anchor.top - gap - size.height else anchor.bottom + gap)
    return PopupSpot(x, y, above = above, fromEnd = fromEnd)
}

private val PopupShape = RoundedCornerShape(20.dp)

// Room around the card inside the pop-up's window for its shadow, which
// would be cut off at the window's edge otherwise.
private val ShadowRoom = 24.dp
private val EdgeMargin = 12.dp
private val AnchorGap = 6.dp

// The film under a menu: darker than the bar's, since text sits on it and
// the page behind may be text too.
val PopupFilm = Color.Black.copy(alpha = 0.52f)

// How long it takes to grow in or shrink away.
private const val POPUP_MS = 160

// A free-floating glass card that pops up beside the control that opened it
// (`anchor`, its bounds in the window), or in the middle of the screen when
// there is none. It grows from the corner nearest the control, frosts what
// is behind it, and closes on a tap outside it or on back. It is at most 60%
// of the screen tall; the content scrolls itself within that.
@Composable
fun GlassPopup(
    visible: Boolean,
    anchor: IntRect?,
    onDismiss: () -> Unit,
    backdrop: HazeState,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    SideEffect { state.targetState = visible }
    // It stays up while it shrinks away.
    if (!visible && !state.currentState && state.isIdle) return

    val dismiss by rememberUpdatedState(onDismiss)
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val top = WindowInsets.statusBars.getTop(density)
    val bottom = WindowInsets.navigationBars.getBottom(density)
    // Phones with animations switched off get it at once.
    val still = remember(visible) { !ValueAnimator.areAnimatorsEnabled() }
    // Where it landed, known once it is measured; it grows from there.
    var spot by remember { mutableStateOf<PopupSpot?>(null) }

    val position = remember(anchor, top, bottom, density) {
        CardPosition(
            anchor = anchor,
            room = with(density) { ShadowRoom.roundToPx() },
            margin = with(density) { EdgeMargin.roundToPx() },
            gap = with(density) { AnchorGap.roundToPx() },
            top = top,
            bottom = bottom,
            onPlaced = { spot = it },
        )
    }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = { dismiss() },
        properties = PopupProperties(focusable = true, clippingEnabled = false),
    ) {
        val transition = rememberTransition(state, label = "glass popup")
        val shown by transition.animateFloat(
            transitionSpec = { tween(if (still) 0 else POPUP_MS, easing = FastOutSlowInEasing) },
            label = "glass popup shown",
        ) { if (it) 1f else 0f }
        val maxWidth = with(density) { window.width.toDp() } - EdgeMargin * 2
        val maxHeight = with(density) { (window.height * 0.6f).toDp() }
        Box(
            Modifier
                // A tap on the shadow's room, outside the card, closes it too.
                .pointerInput(Unit) {
                    val room = ShadowRoom.roundToPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val p = down.position
                        val inside = p.x >= room && p.y >= room && p.x <= size.width - room && p.y <= size.height - room
                        if (!inside && waitForUpOrCancellation() != null) dismiss()
                    }
                }
                .padding(ShadowRoom),
        ) {
            FloatingGlaze(
                backdrop = backdrop,
                shape = PopupShape,
                film = PopupFilm,
                frost = 24f,
                modifier = modifier
                    .widthIn(max = maxWidth)
                    .heightIn(max = maxHeight)
                    .graphicsLayer {
                        val scale = 0.92f + 0.08f * shown
                        scaleX = scale
                        scaleY = scale
                        alpha = shown
                        transformOrigin = spot?.origin ?: TransformOrigin.Center
                    }
                    .semantics { if (title != null) paneTitle = title },
                content = content,
            )
        }
    }
}

// Places the card through the pop-up's window, which is larger than the
// card by the shadow's room on every side.
private class CardPosition(
    private val anchor: IntRect?,
    private val room: Int,
    private val margin: Int,
    private val gap: Int,
    private val top: Int,
    private val bottom: Int,
    private val onPlaced: (PopupSpot) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val card = IntSize(popupContentSize.width - room * 2, popupContentSize.height - room * 2)
        val spot = placePopup(anchor, card, windowSize, margin, gap, top, bottom)
        onPlaced(spot)
        return IntOffset(spot.x - room, spot.y - room)
    }
}

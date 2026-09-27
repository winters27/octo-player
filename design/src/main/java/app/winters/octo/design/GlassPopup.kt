package app.winters.octo.design

import android.animation.ValueAnimator
import android.view.WindowManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
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
// of the screen. With no control it sits in the middle. `keepAbove` is the
// side it took when it opened: while it still fits there it stays, so a
// menu that grows to its next page does not jump to the other side.
fun placePopup(
    anchor: IntRect?,
    size: IntSize,
    window: IntSize,
    margin: Int,
    gap: Int = 0,
    top: Int = 0,
    bottom: Int = 0,
    keepAbove: Boolean? = null,
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
        keepAbove == true && size.height <= roomAbove -> true
        keepAbove == false && size.height <= roomBelow -> false
        size.height <= roomBelow -> false
        size.height <= roomAbove -> true
        else -> roomAbove > roomBelow
    }
    val y = fitY(if (above) anchor.top - gap - size.height else anchor.bottom + gap)
    return PopupSpot(x, y, above = above, fromEnd = fromEnd)
}

private val PopupShape = RoundedCornerShape(20.dp)

private val EdgeMargin = 12.dp
private val AnchorGap = 6.dp

// The widest a pop-up gets, so a menu stays a menu on a wide screen.
val PopupMaxWidth = 340.dp

// The share of the screen's height a pop-up may take before it scrolls.
const val PopupHeightShare = 0.6f

// The film under a menu: darker than the bar's, since text sits on it and
// the page behind may be text too.
val PopupFilm = Color.Black.copy(alpha = 0.52f)

// How hard a pop-up frosts what is behind it, as a CSS blur. The same glass
// as the bar's, frosted harder since text sits on it.
const val PopupFrost = 24f

// How long it takes to grow in or shrink away.
private const val POPUP_MS = 160

// What the pop-up remembers between layouts without asking for another:
// the side it opened on, where it landed, and the card's bounds for telling
// a tap outside it.
private class PopupPlacing {
    var keepAbove: Boolean? = null
    var spot: PopupSpot? = null
    var card = IntRect.Zero
}

// A free-floating glass card that pops up beside the control that opened it
// (`anchor`, its bounds in the window), or in the middle of the screen when
// there is none. It grows from the corner nearest the control, frosts what
// is behind it, and closes on a tap outside it or on back. Back first asks
// `onBack`, which answers true when it went back a page instead. It is at
// most `maxWidth` wide and `heightShare` of the screen tall; the content
// scrolls itself within that. While the keyboard is up it keeps above it,
// moving over the control when there is no room under it any more.
@Composable
fun GlassPopup(
    visible: Boolean,
    anchor: IntRect?,
    onDismiss: () -> Unit,
    backdrop: HazeState,
    modifier: Modifier = Modifier,
    title: String? = null,
    maxWidth: Dp = PopupMaxWidth,
    heightShare: Float = PopupHeightShare,
    onBack: () -> Boolean = { false },
    content: @Composable BoxScope.() -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    SideEffect { state.targetState = visible }
    // It stays up while it shrinks away.
    if (!visible && !state.currentState && state.isIdle) return

    val dismiss by rememberUpdatedState(onDismiss)
    val back by rememberUpdatedState(onBack)
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.getTop(density)
    val navigation = WindowInsets.navigationBars.getBottom(density)
    val hostKeyboard = WindowInsets.ime.getBottom(density)
    // Phones with animations switched off, or Reduce motion, get it at once.
    val animatorsOff = remember(visible) { !ValueAnimator.areAnimatorsEnabled() }
    val still = LocalReduceMotion.current || animatorsOff
    val placing = remember { PopupPlacing() }
    // A fresh opening picks its side afresh.
    if (visible && state.isIdle && !state.currentState) placing.keepAbove = null

    // The pop-up's window covers the whole screen, clear, so the card can be
    // placed and moved inside it, and a tap anywhere off the card closes it.
    // Back is the only thing the window itself reports. It leaves the
    // screen's edges to the system, so the back swipe still works.
    Popup(
        popupPositionProvider = WholeWindow,
        onDismissRequest = { if (!back()) dismiss() },
        properties = PopupProperties(
            focusable = true,
            dismissOnClickOutside = false,
            excludeFromSystemGesture = false,
            clippingEnabled = false,
        ),
    ) {
        KeepStillForKeyboard()
        val keyboard = maxOf(hostKeyboard, WindowInsets.ime.getBottom(density))
        val bottom = maxOf(navigation, keyboard)
        val transition = rememberTransition(state, label = "glass popup")
        val shown by transition.animateFloat(
            transitionSpec = { tween(if (still) 0 else POPUP_MS, easing = FastOutSlowInEasing) },
            label = "glass popup shown",
        ) { if (it) 1f else 0f }
        Layout(
            content = {
                FloatingGlaze(
                    backdrop = backdrop,
                    shape = PopupShape,
                    film = PopupFilm,
                    frost = PopupFrost,
                    modifier = modifier
                        .graphicsLayer {
                            // With motion reduced it only fades.
                            val scale = if (still) 1f else 0.92f + 0.08f * shown
                            scaleX = scale
                            scaleY = scale
                            alpha = shown
                            transformOrigin = placing.spot?.origin ?: TransformOrigin.Center
                        }
                        .semantics { if (title != null) paneTitle = title },
                    content = content,
                )
            },
            modifier = Modifier.pointerInput(Unit) {
                // A tap off the card closes it.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val p = down.position
                    val card = placing.card
                    val inside = p.x >= card.left && p.x <= card.right && p.y >= card.top && p.y <= card.bottom
                    if (!inside && waitForUpOrCancellation() != null) dismiss()
                }
            },
        ) { measurables, constraints ->
            val window = IntSize(constraints.maxWidth, constraints.maxHeight)
            val margin = EdgeMargin.roundToPx()
            val widest = minOf(maxWidth.roundToPx(), window.width - margin * 2).coerceAtLeast(0)
            val room = window.height - top - bottom - margin * 2
            val tallest = minOf((window.height * heightShare).toInt(), room).coerceAtLeast(0)
            val card = measurables.first().measure(Constraints(maxWidth = widest, maxHeight = tallest))
            val size = IntSize(card.width, card.height)
            val spot = placePopup(anchor, size, window, margin, AnchorGap.roundToPx(), top, bottom, placing.keepAbove)
            if (placing.keepAbove == null && !spot.centred) placing.keepAbove = spot.above
            placing.spot = spot
            placing.card = IntRect(IntOffset(spot.x, spot.y), size)
            layout(window.width, window.height) { card.place(spot.x, spot.y) }
        }
    }
}

// The pop-up's window lies over the whole screen at its top corner.
private object WholeWindow : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}

// The keyboard must not push or squash the pop-up's window: the card keeps
// above it by itself, from the keyboard's height.
@Composable
private fun KeepStillForKeyboard() {
    val view = LocalView.current
    DisposableEffect(view) {
        val root = view.rootView
        val params = root.layoutParams as? WindowManager.LayoutParams
        val manager = view.context.getSystemService(WindowManager::class.java)
        if (params != null && manager != null && params.softInputMode != WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING) {
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            runCatching { manager.updateViewLayout(root, params) }
        }
        onDispose { }
    }
}

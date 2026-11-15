package app.winters.octo.design

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// A thin bar over the right edge of a list, grid or page that scrolls,
// saying where it is; drag it to move through. It shows while the pointer
// moves or the wheel turns over what scrolls and fades a moment after the
// pointer rests; under the pointer or while dragged it stays, grows a
// little wider and brighter. Nothing shows when everything fits. It goes
// on the scrolling list itself, so wrapping nothing: `bottom` keeps it
// clear of what floats over the page's foot (the player).
fun Modifier.scrollbar(state: LazyListState, bottom: Dp = 0.dp): Modifier = composed { scrollbar(state, rememberScrollbarAdapter(state), bottom) }

fun Modifier.scrollbar(state: LazyGridState, bottom: Dp = 0.dp): Modifier = composed { scrollbar(state, rememberScrollbarAdapter(state), bottom) }

// A `resting` one keeps a thinner, fainter thumb while what scrolls does
// not fit, so a list cut off at its foot says there is more. `top` keeps
// the bar clear of the top as `bottom` does of the foot (a rounded card's
// corners). The bar counts from `start`, the least offset a scroll back
// reaches, so what lies before it is never offered as room to go back to.
fun Modifier.scrollbar(state: ScrollState, bottom: Dp = 0.dp, resting: Boolean = false, top: Dp = 0.dp, start: () -> Int = { 0 }): Modifier = composed {
    val from by rememberUpdatedState(start)
    val inner = rememberScrollbarAdapter(state)
    scrollbar(state, remember(inner) { FromAdapter(inner) { from().toDouble() } }, bottom, resting, top)
}

private fun Modifier.scrollbar(state: ScrollableState, adapter: ScrollbarAdapter, bottom: Dp, resting: Boolean = false, top: Dp = 0.dp): Modifier = composed {
    val rest = OctoColors.TextPrimary.copy(alpha = ScrollbarRestAlpha)
    val inkRest = OctoColors.TextPrimary.copy(alpha = ScrollbarInkAlpha)
    val inkLit = OctoColors.TextPrimary.copy(alpha = ScrollbarInkLitAlpha)
    val bar = remember { Bar() }
    val scope = rememberCoroutineScope()
    val motion = motionScale()
    val seen = remember { Animatable(0f) }
    val wide = remember { Animatable(0f) }
    // Rests a moment after the pointer last stirred.
    LaunchedEffect(bar) {
        snapshotFlow { bar.awake }.collectLatest { awake ->
            if (!awake) return@collectLatest
            while (true) {
                val left = ScrollbarRestMs - (System.currentTimeMillis() - bar.at)
                if (left <= 0) break
                delay(left)
            }
            bar.awake = false
        }
    }
    LaunchedEffect(bar, motion) {
        snapshotFlow { bar.awake || bar.over || bar.dragging }.collectLatest { shown ->
            seen.animateTo(if (shown) 1f else 0f, octoTween(motion, if (shown) OctoDuration.Press else ScrollbarFadeMs))
        }
    }
    LaunchedEffect(bar, motion) {
        snapshotFlow { bar.over || bar.dragging }.collectLatest { lit ->
            wide.animateTo(if (lit) 1f else 0f, octoTween(motion, OctoDuration.Press))
        }
    }
    this
        .pointerInput(adapter, bottom, top) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    val track = Track(size.width.toFloat(), size.height.toFloat(), this@awaitPointerEventScope, bottom, top)
                    when (event.type) {
                        PointerEventType.Enter, PointerEventType.Move -> {
                            bar.poke()
                            bar.over = track.thumbAt(adapter)?.contains(change.position) == true
                        }
                        PointerEventType.Scroll -> bar.poke()
                        PointerEventType.Exit -> {
                            bar.awake = false
                            bar.over = false
                        }
                        PointerEventType.Press -> {
                            val thumb = track.thumbAt(adapter)
                            if (!event.buttons.isPrimaryPressed || state.fits() || thumb == null || !thumb.contains(change.position)) continue
                            // Grabbed: the bar has the pointer until it lets go.
                            change.consume()
                            bar.dragging = true
                            val grab = change.position.y - thumb.top
                            var job: Job? = null
                            while (true) {
                                val next = awaitPointerEvent(PointerEventPass.Initial)
                                val moved = next.changes.firstOrNull() ?: break
                                moved.consume()
                                if (!moved.pressed) break
                                val to = track.offsetFor(adapter, moved.position.y - grab)
                                job?.cancel()
                                job = scope.launch { adapter.scrollTo(to) }
                            }
                            bar.dragging = false
                            bar.poke()
                        }
                    }
                }
            }
        }
        .drawWithContent {
            drawContent()
            val shown = seen.value
            if (state.fits()) return@drawWithContent
            val track = Track(size.width, size.height, this, bottom, top)
            val thumb = track.thumbAt(adapter) ?: return@drawWithContent
            if (resting && shown < 1f) {
                val thin = ScrollbarResting.toPx()
                drawRoundRect(
                    rest.copy(alpha = rest.alpha * (1f - shown)),
                    topLeft = Offset(track.end - thin, thumb.top),
                    size = Size(thin, thumb.height),
                    cornerRadius = CornerRadius(thin / 2),
                )
            }
            if (shown <= 0f) return@drawWithContent
            val lit = wide.value
            val thickness = (ScrollbarThin + (ScrollbarWide - ScrollbarThin) * lit).toPx()
            val ink = lerpAlpha(inkRest, inkLit, lit)
            drawRoundRect(
                ink.copy(alpha = ink.alpha * shown),
                topLeft = Offset(track.end - thickness, thumb.top),
                size = Size(thickness, thumb.height),
                cornerRadius = CornerRadius(thickness / 2),
            )
        }
}

private fun ScrollableState.fits() = !canScrollForward && !canScrollBackward

// The lane down the right edge the thumb runs in, in pixels, `start` under
// the top and `bottom` over the foot.
private class Track(width: Float, height: Float, density: androidx.compose.ui.unit.Density, bottom: Dp, start: Dp = 0.dp) {
    private val inset = with(density) { ScrollbarInset.toPx() }
    private val reach = with(density) { (ScrollbarWide + ScrollbarInset).toPx() }
    private val least = with(density) { ScrollbarMinLength.toPx() }
    private val right = width - inset

    // Where the drawn thumb ends, short of the edge.
    val end get() = right
    private val top = inset + with(density) { start.toPx() }
    private val length = (height - inset * 2 - with(density) { (bottom + start).toPx() }).coerceAtLeast(0f)

    // The thumb, as wide as the pointer may grab it; nothing when all fits.
    fun thumbAt(adapter: ScrollbarAdapter): androidx.compose.ui.geometry.Rect? {
        val content = adapter.contentSize
        val view = adapter.viewportSize
        if (content <= view || length <= 0f) return null
        val size = (length * (view / content)).toFloat().coerceIn(least.coerceAtMost(length), length)
        val travel = (content - view).coerceAtLeast(1.0)
        val at = top + (length - size) * (adapter.scrollOffset / travel).toFloat().coerceIn(0f, 1f)
        return androidx.compose.ui.geometry.Rect(right - reach, at, right + inset, at + size)
    }

    // The scroll offset that puts the thumb's top at `y`.
    fun offsetFor(adapter: ScrollbarAdapter, y: Float): Double {
        val content = adapter.contentSize
        val view = adapter.viewportSize
        val size = (length * (view / content)).toFloat().coerceIn(least.coerceAtMost(length), length)
        val room = (length - size).coerceAtLeast(1f)
        return ((y - top) / room).coerceIn(0f, 1f) * (content - view).coerceAtLeast(0.0)
    }
}

// A scroll's bar counted from `start`: what lies before it is neither shown
// as room to scroll back into nor reached by a drag.
private class FromAdapter(private val inner: ScrollbarAdapter, private val start: () -> Double) : ScrollbarAdapter {
    override val scrollOffset: Double get() = (inner.scrollOffset - start()).coerceAtLeast(0.0)
    override val contentSize: Double get() = inner.contentSize - start()
    override val viewportSize: Double get() = inner.viewportSize

    override suspend fun scrollTo(scrollOffset: Double) = inner.scrollTo(scrollOffset + start())
}

// Whether the bar is awake for a stirring pointer (and when it last
// stirred), under the pointer, or dragged. Only the turns are state, so a
// moving pointer costs no redraws.
@Stable
private class Bar {
    var at = 0L
    var awake by mutableStateOf(false)
    var over by mutableStateOf(false)
    var dragging by mutableStateOf(false)

    fun poke() {
        at = System.currentTimeMillis()
        if (!awake) awake = true
    }
}

private fun lerpAlpha(from: Color, to: Color, f: Float): Color = from.copy(alpha = from.alpha + (to.alpha - from.alpha) * f)

// How long the bar stays after the pointer rests, and how long it fades.
private const val ScrollbarRestMs = 1_200L
private const val ScrollbarFadeMs = 240

private val ScrollbarThin = 6.dp
private val ScrollbarWide = 9.dp
private val ScrollbarInset = 3.dp
private val ScrollbarMinLength = 36.dp

// The resting thumb: thinner, and the words' ink faint.
private val ScrollbarResting = 4.dp
private const val ScrollbarRestAlpha = 0.18f

// The room a scrolling list keeps at its end so a row's fill stops before
// the bar at its widest, with a little air.
val ScrollbarGutter = ScrollbarWide + 3.dp

// The words' ink, quiet at rest and brighter under the pointer, so it reads
// on any cover's colours without taking the eye.
private const val ScrollbarInkAlpha = 0.28f
private const val ScrollbarInkLitAlpha = 0.55f

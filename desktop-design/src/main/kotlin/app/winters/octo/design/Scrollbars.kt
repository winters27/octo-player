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

fun Modifier.scrollbar(state: ScrollState, bottom: Dp = 0.dp): Modifier = composed { scrollbar(state, rememberScrollbarAdapter(state), bottom) }

private fun Modifier.scrollbar(state: ScrollableState, adapter: ScrollbarAdapter, bottom: Dp): Modifier = composed {
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
        .pointerInput(adapter, bottom) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    val track = Track(size.width.toFloat(), size.height.toFloat(), this@awaitPointerEventScope, bottom)
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
            if (shown <= 0f || state.fits()) return@drawWithContent
            val track = Track(size.width, size.height, this, bottom)
            val thumb = track.thumbAt(adapter) ?: return@drawWithContent
            val lit = wide.value
            val thickness = (ScrollbarThin + (ScrollbarWide - ScrollbarThin) * lit).toPx()
            val ink = lerpAlpha(ScrollbarInk, ScrollbarInkLit, lit)
            drawRoundRect(
                ink.copy(alpha = ink.alpha * shown),
                topLeft = Offset(track.end - thickness, thumb.top),
                size = Size(thickness, thumb.height),
                cornerRadius = CornerRadius(thickness / 2),
            )
        }
}

private fun ScrollableState.fits() = !canScrollForward && !canScrollBackward

// The lane down the right edge the thumb runs in, in pixels.
private class Track(width: Float, height: Float, density: androidx.compose.ui.unit.Density, bottom: Dp) {
    private val inset = with(density) { ScrollbarInset.toPx() }
    private val reach = with(density) { (ScrollbarWide + ScrollbarInset).toPx() }
    private val least = with(density) { ScrollbarMinLength.toPx() }
    private val right = width - inset

    // Where the drawn thumb ends, short of the edge.
    val end get() = right
    private val top = inset
    private val length = (height - inset * 2 - with(density) { bottom.toPx() }).coerceAtLeast(0f)

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

// White, quiet at rest and brighter under the pointer, so it reads on any
// cover's colours without taking the eye.
private val ScrollbarInk = Color.White.copy(alpha = 0.28f)
private val ScrollbarInkLit = Color.White.copy(alpha = 0.55f)

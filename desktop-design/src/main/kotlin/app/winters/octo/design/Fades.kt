package app.winters.octo.design

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The top edge fades out, so words scrolled up melt away under a title
// rather than being cut off. At rest nothing sits in it.
fun Modifier.topFade(height: Dp): Modifier = edgeFades(height, top = { 1f }, bottom = { 0f })

// Soft fades at the top and bottom edges of a list that scrolls, each only
// while there is more of the list that way, and coming in over the first
// stretch scrolled so it never pops.
fun Modifier.scrollFades(state: ScrollState, height: Dp = 12.dp, eased: Boolean = false): Modifier = edgeFades(
    height,
    top = { edge -> (state.value / edge).coerceIn(0f, 1f) },
    bottom = { edge -> ((state.maxValue - state.value) / edge).coerceIn(0f, 1f) },
    eased = eased,
)

// The top edge fading only while scrolled from the top, eased in over the
// first stretch scrolled.
fun Modifier.topScrollFade(state: ScrollState, height: Dp, eased: Boolean = false): Modifier = edgeFades(
    height,
    top = { edge -> (state.value / edge).coerceIn(0f, 1f) },
    bottom = { 0f },
    eased = eased,
)

// Fades the top and bottom `height` of what is drawn, `top` and `bottom` of
// the way (0 to 1), read while drawing from the fade's height in pixels. An
// `eased` fade starts and ends gently rather than in a straight ramp.
private fun Modifier.edgeFades(height: Dp, top: (Float) -> Float, bottom: (Float) -> Float, eased: Boolean = false): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = height.toPx()
        val above = top(edge)
        if (above > 0f) {
            drawRect(
                Brush.verticalGradient(*ramp(1f - above, 1f, eased), startY = 0f, endY = edge),
                size = Size(size.width, edge),
                blendMode = BlendMode.DstIn,
            )
        }
        val below = bottom(edge)
        if (below > 0f) {
            drawRect(
                Brush.verticalGradient(*ramp(1f, 1f - below, eased), startY = size.height - edge, endY = size.height),
                topLeft = Offset(0f, size.height - edge),
                size = Size(size.width, edge),
                blendMode = BlendMode.DstIn,
            )
        }
    }

// The stops of a fade's mask from `from` to `to` (how much shows): two for
// a straight ramp, or several along a smooth step for an eased one.
private fun ramp(from: Float, to: Float, eased: Boolean): Array<Pair<Float, Color>> {
    val steps = if (eased) EASED_STOPS else 1
    return Array(steps + 1) { i ->
        val t = i.toFloat() / steps
        val k = if (eased) t * t * (3 - 2 * t) else t
        t to Color.Black.copy(alpha = from + (to - from) * k)
    }
}

private const val EASED_STOPS = 8

// Soft fades at the two ends of a row that scrolls sideways, each only
// while there is more of the row that way, so a chip running under the
// next control melts away instead of being cut.
fun Modifier.rowFades(state: LazyListState, width: Dp = 16.dp): Modifier =
    sideFades(width, { state.canScrollBackward }, { state.canScrollForward })

// The same, for a row scrolled by a ScrollState.
fun Modifier.rowFades(state: ScrollState, width: Dp = 16.dp): Modifier =
    sideFades(width, { state.value > 0 }, { state.value < state.maxValue })

private fun Modifier.sideFades(width: Dp, before: () -> Boolean, after: () -> Boolean): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = width.toPx()
        if (before()) {
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = edge), size = Size(edge, size.height), blendMode = BlendMode.DstIn)
        }
        if (after()) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - edge, endX = size.width),
                topLeft = Offset(size.width - edge, 0f),
                size = Size(edge, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }

package app.winters.octo.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.winters.octo.design.FloatingGlaze
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder

// The order a list is in, as a small line of text at its top: the sort
// mark, what it goes by, and an arrow for which way it runs. A tap opens the
// sort sheet.
@Composable
fun SortButton(list: SortList, order: SortOrder, onChange: (SortOrder) -> Unit, modifier: Modifier = Modifier) {
    val sheet = LocalChoiceSheet.current
    val direction = if (order.descending) "descending" else "ascending"
    Row(
        modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Change the order") { sheet.show(SortRequest(list.options, order, onChange)) }
            .semantics(mergeDescendants = true) { contentDescription = "Sorted by ${order.by.label}, $direction" }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(OctoIcons.Sort), contentDescription = null, tint = OctoColors.TextSecondary, modifier = Modifier.size(18.dp))
        Text(order.by.label, style = OctoType.label, color = OctoColors.TextSecondary)
        Icon(
            painterResource(if (order.descending) OctoIcons.Descending else OctoIcons.Ascending),
            contentDescription = null,
            tint = OctoColors.TextSecondary,
            modifier = Modifier.size(15.dp),
        )
    }
}

// A line above a list inside a page: an optional name for the list on the
// left, and its sort button on the right.
@Composable
fun SortBar(
    list: SortList,
    order: SortOrder,
    onChange: (SortOrder) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            title?.let { Text(it, style = OctoType.section, color = OctoColors.TextPrimary, modifier = Modifier.padding(vertical = 10.dp)) }
        }
        SortButton(list, order, onChange)
    }
}

// When a list shows up in a new order, it starts again from its first row
// (`top`, the row after any page header), rather than following the row
// that was at the top to its new place. A page scrolled only as far as its
// header stays put. It runs as the new rows are placed, so the old position
// never shows for a frame.
@Composable
fun TopOnNewOrder(order: SortOrder, state: LazyListState, top: Int = 0) {
    val shown = remember { ShownOrder(order) }
    SideEffect {
        if (shown.order != order) {
            shown.order = order
            if (state.firstVisibleItemIndex >= top) state.requestScrollToItem(top)
        }
    }
}

@Composable
fun TopOnNewOrder(order: SortOrder, state: LazyGridState, top: Int = 0) {
    val shown = remember { ShownOrder(order) }
    SideEffect {
        if (shown.order != order) {
            shown.order = order
            if (state.firstVisibleItemIndex >= top) state.requestScrollToItem(top)
        }
    }
}

// The order last shown. Not state: changing it must not recompose.
private class ShownOrder(var order: SortOrder)

// Rows under sticky letter headings, a heading for each run. Rows move
// lightly into place when the order changes.
fun <T> LazyListScope.letteredRows(
    items: List<T>,
    runs: List<LetterRun>,
    key: (T) -> Any,
    row: @Composable LazyItemScope.(T) -> Unit,
) {
    runs.forEachIndexed { runIndex, run ->
        stickyHeader(key = "letter:$runIndex:${run.letter}") { LetterHeading(run.letter) }
        items(items.subList(run.start, run.start + run.count), key = key) { item ->
            Box(Modifier.animateItem()) { row(item) }
        }
    }
}

// Rows with no headings, moving lightly into place when the order changes.
fun <T> LazyListScope.sortedRows(items: List<T>, key: (T) -> Any, row: @Composable LazyItemScope.(T) -> Unit) {
    items(items, key = key) { item ->
        Box(Modifier.animateItem()) { row(item) }
    }
}

@Composable
private fun LetterHeading(letter: Char) {
    Text(
        letter.toString(),
        style = OctoType.caption.copy(fontWeight = FontWeight.Bold),
        color = OctoColors.TextMuted,
        modifier = Modifier
            .fillMaxWidth()
            .background(OctoColors.Background)
            .padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

// A list this long, ordered by name, gets the letter rail.
const val RAIL_MIN_ITEMS = 60

private val RailWidth = 24.dp
private val BubbleSize = 64.dp

// The letters of a long list ordered by name, down its right edge. Touching
// or dragging along it jumps the list to a letter, with that letter shown
// large beside the finger. Place it over the list, the size of the list;
// only the rail itself takes touches.
@Composable
fun LetterRail(stops: List<RailStop>, onJump: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val latestStops by rememberUpdatedState(stops)
    val jump by rememberUpdatedState(onJump)
    var railHeight by remember { mutableIntStateOf(0) }
    // The letter under the finger, or -1 when the rail is not touched.
    var active by remember { mutableIntStateOf(-1) }
    // The letter last touched, so the bubble keeps it while fading away.
    var shown by remember { mutableIntStateOf(0) }

    Box(modifier) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(RailWidth)
                .onSizeChanged { railHeight = it.height }
                .pointerInput(Unit) {
                    fun touch(y: Float) {
                        val list = latestStops
                        if (list.isEmpty() || railHeight == 0) return
                        val index = (y / railHeight * list.size).toInt().coerceIn(0, list.lastIndex)
                        if (index == active) return
                        active = index
                        shown = index
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        jump(list[index].index)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        touch(down.position.y)
                        drag(down.id) { change ->
                            change.consume()
                            touch(change.position.y)
                        }
                        active = -1
                    }
                }
                .semantics { contentDescription = "Letters. Drag to jump through the list." },
        ) {
            stops.forEachIndexed { index, stop ->
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        stop.letter.toString(),
                        style = OctoType.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        color = if (index == active) OctoColors.TextPrimary else OctoColors.TextMuted,
                    )
                }
            }
        }
        // The bubble beside the finger, level with the letter.
        val slot = if (stops.isEmpty()) 0f else railHeight.toFloat() / stops.size
        AnimatedVisibility(
            visible = active >= 0,
            enter = fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.85f),
            exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset {
                    val y = slot * (shown + 0.5f) - BubbleSize.toPx() / 2
                    IntOffset(-(RailWidth + 16.dp).roundToPx(), y.toInt())
                },
        ) {
            FloatingGlaze(LocalHaze.current, Modifier.size(BubbleSize)) {
                Text(
                    stops.getOrNull(shown)?.letter?.toString().orEmpty(),
                    style = OctoType.title,
                    color = OctoColors.TextPrimary,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

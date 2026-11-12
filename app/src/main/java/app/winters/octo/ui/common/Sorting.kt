package app.winters.octo.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.winters.octo.design.FloatingGlaze
import app.winters.octo.design.Glaze
import app.winters.octo.design.SelectedFill
import app.winters.octo.design.GlowIcon
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.glassFilm
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOption
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.directionChoices
import app.winters.octo.sort.sortScale

// How tall the sort button's capsule is. Its touch target is still 48dp.
private val SortCapsuleHeight = 34.dp

// The marks either side of the sort name: white, a step quieter than it.
private val SortMarkTint: Color @Composable @ReadOnlyComposable get() = OctoColors.TextPrimary.copy(alpha = 0.72f)

// Which way the sort button's arrow points, in degrees: the up arrow as
// drawn for ascending, turned half round for descending. The turn is
// animated, so a change of direction shows as the arrow flipping over.
fun sortArrowTurn(descending: Boolean): Float = if (descending) 180f else 0f

// The order a list is in, as a small glass capsule at its top: the sort
// mark, what it goes by in white, and an arrow for which way it runs. A tap
// pops the sort list up beside it. On a page header short of room it moves
// into More (see sortItem).
@Composable
fun SortButton(list: SortList, order: SortOrder, onChange: (SortOrder) -> Unit, modifier: Modifier = Modifier) {
    val sheet = LocalChoiceSheet.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.94f else 1f, spring(0.45f, 600f), label = "sort press")
    val turn by animateFloatAsState(sortArrowTurn(order.descending), spring(0.7f, 300f), label = "sort arrow")
    val direction = sortScale(order.by).label(order.descending)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .choiceAnchor(sheet)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = "Change the order") {
                sheet.show(SortRequest(list.options, order, onChange))
            }
            .semantics(mergeDescendants = true) { contentDescription = "Sorted by ${order.by.label}, $direction" },
        contentAlignment = Alignment.Center,
    ) {
        Glaze(
            Modifier
                .height(SortCapsuleHeight)
                .graphicsLayer {
                    scaleX = press
                    scaleY = press
                },
        ) {
            Row(
                Modifier.padding(start = 12.dp, end = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(painterResource(OctoIcons.Sort), contentDescription = null, tint = SortMarkTint, modifier = Modifier.size(16.dp))
                Text(
                    order.by.label,
                    style = OctoType.label.copy(fontWeight = FontWeight.Medium),
                    color = OctoColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    painterResource(OctoIcons.Ascending),
                    contentDescription = null,
                    tint = SortMarkTint,
                    modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = turn },
                )
            }
        }
    }
}

// A line above a list inside a page: an optional name for the list on the
// left, and its sort button on the right at the page's 20dp edge.
@Composable
fun SortBar(
    list: SortList,
    order: SortOrder,
    onChange: (SortOrder) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            title?.let { Text(it, style = OctoType.section, color = OctoColors.TextPrimary, modifier = Modifier.padding(vertical = 10.dp)) }
        }
        SortButton(list, order, onChange)
    }
}

// A list's sort button as a control line item, the first to move into More
// when the line is short of room. There it is "Sort: Date liked", its
// direction under it, and opens the same sort list.
@Composable
fun sortItem(list: SortList, order: SortOrder, onChange: (SortOrder) -> Unit, overflow: Int = 0): LineItem {
    val sheet = LocalChoiceSheet.current
    val direction = sortScale(order.by).label(order.descending)
    return LineItem(
        "sort",
        overflow = overflow,
        more = MoreItem(sortMoreLabel(order), OctoIcons.Sort, detail = direction) { sheet.show(SortRequest(list.options, order, onChange)) },
    ) { _ -> SortButton(list, order, onChange) }
}

// What a sort button says in More: what the list goes by.
fun sortMoreLabel(order: SortOrder): String = "Sort: ${order.by.label}"

// Whether a page title, at its whole one-line width, fits beside its
// buttons on a line `width` wide.
fun titleFitsBeside(titleWidth: Int, buttonsWidth: Int, width: Int): Boolean = titleWidth + buttonsWidth <= width

// The sort list: the two directions on top, then the options. Flipping the
// direction reorders the list behind at once and keeps this open; picking an
// option closes it.
@Composable
internal fun SortChoices(request: SortRequest, close: () -> Unit) {
    var descending by remember(request) { mutableStateOf(request.order.descending) }
    Column(Modifier.width(248.dp).padding(6.dp)) {
        DirectionPair(request.order.by, descending, Modifier.padding(bottom = 6.dp)) { picked ->
            if (picked != descending) {
                descending = picked
                request.onChange(request.order.copy(descending = picked))
            }
        }
        SortOptions(
            request.options,
            selected = request.options.indexOf(request.order.by),
            onPick = { index ->
                val option = request.options[index]
                if (option != request.order.by) request.onChange(request.order.picking(option))
                close()
            },
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
        )
    }
}

// The words and mark of the direction not chosen: white, well back.
private val UnchosenTint: Color @Composable @ReadOnlyComposable get() = OctoColors.TextPrimary.copy(alpha = 0.45f)

// The two directions side by side in one glaze, named for what they mean
// here ("A to Z", "Newest"). The chosen one is the accent-tinted pill with white
// words and its arrow lit; the other is muted, so which is on reads at once.
@Composable
private fun DirectionPair(option: SortOption, descending: Boolean, modifier: Modifier = Modifier, onPick: (Boolean) -> Unit) {
    val choices = remember(option) { directionChoices(option) }
    val selected = choices.indexOfFirst { it.descending == descending }
    Glaze(modifier.fillMaxWidth().height(44.dp)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
            val half = maxWidth / 2
            val x by animateDpAsState(half * selected, spring(dampingRatio = 0.8f, stiffness = 350f), label = "direction")
            Box(
                Modifier
                    .offset { IntOffset(x.roundToPx(), 0) }
                    .width(half)
                    .fillMaxHeight()
                    .background(OctoColors.AccentSelected, OptionShape),
            )
            Row(Modifier.fillMaxSize().selectableGroup()) {
                choices.forEachIndexed { index, choice ->
                    val chosen = index == selected
                    val tint by animateColorAsState(if (chosen) OctoColors.TextPrimary else UnchosenTint, label = "direction tint")
                    Row(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(selected = chosen, interactionSource = null, indication = null, role = Role.RadioButton) {
                                onPick(choice.descending)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    ) {
                        GlowIcon(
                            painterResource(if (choice.descending) OctoIcons.Descending else OctoIcons.Ascending),
                            tint = tint,
                            lit = chosen,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            choice.label,
                            style = OctoType.label.copy(fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium),
                            color = tint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// The popup's card is padded 6dp, so a pill's corners follow its 20dp.
private val OptionShape = RoundedCornerShape(14.dp)

private val OptionLine: Color @Composable @ReadOnlyComposable get() = OctoColors.TextPrimary.copy(alpha = 0.08f)

// The options not chosen: a step back from the chosen one's white.
private val OtherOptionTint: Color @Composable @ReadOnlyComposable get() = OctoColors.TextPrimary.copy(alpha = 0.7f)

// What a list can be ordered by, one above the other with hairlines
// between, as in the other glass menus. The chosen option sits in the
// accent-tinted pill with white words and a white check, and the others are
// dimmed a little.
@Composable
private fun SortOptions(options: List<SortOption>, selected: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val looks = remember(options.size, selected) { menuRowLooks(options.size, selected) }
    Column(modifier.selectableGroup()) {
        options.forEachIndexed { index, option ->
            if (index > 0) {
                // Always 1dp, drawn or not, so the rows never shift.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .height(1.dp)
                        .background(if (looks[index].lineAbove) OptionLine else Color.Transparent),
                )
            }
            val chosen = looks[index].selected
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(OptionShape)
                    .selectable(selected = chosen, role = Role.RadioButton) { onPick(index) },
                contentAlignment = Alignment.CenterStart,
            ) {
                if (chosen) Box(Modifier.matchParentSize().background(OctoColors.AccentSelected, OptionShape))
                SelectedFill(chosen) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            option.label,
                            style = OctoType.bodySmall.copy(fontWeight = if (chosen) FontWeight.Medium else FontWeight.Normal),
                            color = if (chosen) OctoColors.TextPrimary else OtherOptionTint,
                            modifier = Modifier.weight(1f),
                        )
                        // The check's room is kept on every line.
                        if (chosen) {
                            Icon(painterResource(OctoIcons.Check), contentDescription = null, tint = OctoColors.TextPrimary, modifier = Modifier.size(18.dp))
                        } else {
                            Spacer(Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
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

// Rows under letter headings, a heading for each run, each heading one item
// of the list. Rows move lightly into place when the order changes.
fun <T> LazyListScope.letteredRows(
    items: List<T>,
    runs: List<LetterRun>,
    key: (T) -> Any,
    row: @Composable LazyItemScope.(T) -> Unit,
) {
    runs.forEachIndexed { runIndex, run ->
        item(key = "letter:$runIndex:${run.letter}") { LetterHeading(run.letter) }
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

// The letter a run of rows starts with, scrolling with its rows: a small dark
// pill rather than a full-width band, so the page's glow still shows around
// it. Its left edge lines up with the rows' covers.
@Composable
fun LetterHeading(letter: Char) {
    Box(Modifier.fillMaxWidth().padding(start = ListRowSide, end = ListRowSide, top = 16.dp, bottom = 4.dp)) {
        Text(
            letter.toString(),
            style = OctoType.caption.copy(fontWeight = FontWeight.Bold),
            color = OctoColors.TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(glassFilm(0.55f), CircleShape)
                .sizeIn(minWidth = 26.dp, minHeight = 26.dp)
                .wrapContentHeight(Alignment.CenterVertically)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

// The letter the list shows at its top: the last rail stop at or above the
// first visible item `firstVisible`, null above the first stop.
fun railLetterAt(stops: List<RailStop>, firstVisible: Int): Char? =
    stops.lastOrNull { it.index <= firstVisible }?.letter

// A list this long, ordered by name, gets the letter rail.
const val RAIL_MIN_ITEMS = 60

// Whether a list shows the letter rail: ordered by name (it has headings)
// and long enough.
fun railShows(byName: Boolean, count: Int): Boolean = byName && count >= RAIL_MIN_ITEMS

// How wide the letter rail is. A list that shows it keeps letterRailRoom()
// at its end, so the rail never sits over a row's length or a cover.
private val LetterRailWidth = 24.dp
private val RailWidth = LetterRailWidth
private val BubbleSize = 64.dp

// The strip along the list's end edge where the phone's back gesture starts:
// a touch there goes to the gesture, never to the app. It is as wide as the
// phone sets it (often 30dp with gesture navigation) and nothing with
// navigation buttons. The letter rail sits just inside it. Pages already
// keep clear of the cutout and a side navigation bar (sideways), so only
// the part of the strip past those counts.
@Composable
fun backGestureEdge(): Dp {
    val density = LocalDensity.current
    val layout = LocalLayoutDirection.current
    val gestures = WindowInsets.systemGestures
    val sides = WindowInsets.displayCutout.union(WindowInsets.navigationBars)
    val px = if (layout == LayoutDirection.Ltr) {
        gestures.getRight(density, layout) - sides.getRight(density, layout)
    } else {
        gestures.getLeft(density, layout) - sides.getLeft(density, layout)
    }
    return with(density) { px.coerceAtLeast(0).toDp() }
}

// The room a letter rail takes at the end of a list: the rail itself and the
// back gesture's strip beyond it.
@Composable
fun letterRailRoom(): Dp = LetterRailWidth + backGestureEdge()

// The letters of a long list ordered by name, down its end edge, just inside
// the phone's back gesture strip so a drag along it reaches the rail.
// Touching or dragging along it jumps the list to a letter, with that letter
// shown large beside the finger. `current` is the letter the list shows at
// its top (see railLetterAt), lit while the list scrolls; the touched letter
// wins while the rail is held. Place it over the list, the size of the
// list; only the rail itself takes touches.
@Composable
fun LetterRail(stops: List<RailStop>, current: Char?, onJump: (Int) -> Unit, modifier: Modifier = Modifier) {
    val edge = backGestureEdge()
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
                .padding(end = edge)
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
            val lit = if (active >= 0) active else stops.indexOfFirst { it.letter == current }
            stops.forEachIndexed { index, stop ->
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        stop.letter.toString(),
                        style = OctoType.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        color = if (index == lit) OctoColors.TextPrimary else OctoColors.TextMuted,
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
                    IntOffset(-(RailWidth + edge + 16.dp).roundToPx(), y.toInt())
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

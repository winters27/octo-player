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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.winters.octo.design.FloatingGlaze
import app.winters.octo.design.Glaze
import app.winters.octo.design.GlazeSelected
import app.winters.octo.design.GlowIcon
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOption
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.directionChoices
import app.winters.octo.sort.sortScale

// How tall the sort button's capsule is. Its touch target is still 48dp.
private val SortCapsuleHeight = 34.dp

// The marks either side of the sort name: white, a step quieter than it.
private val SortMarkTint = Color.White.copy(alpha = 0.72f)

// Which way the sort button's arrow points, in degrees: the up arrow as
// drawn for ascending, turned half round for descending. The turn is
// animated, so a change of direction shows as the arrow flipping over.
fun sortArrowTurn(descending: Boolean): Float = if (descending) 180f else 0f

// The order a list is in, as a small glass capsule at its top: the sort
// mark, what it goes by in white, and an arrow for which way it runs. A tap
// pops the sort list up beside it.
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

// A page's big title with its sort button at the end of the line, at the
// page's 20dp edge. The title has more room under its words than over them,
// so the button is lifted by the difference to sit level with the words.
// The title is never shortened or broken inside a word: when it and the
// buttons do not both fit whole on one line, the buttons go on a line of
// their own under it, at the same edge.
@Composable
fun TitleWithSort(title: String, modifier: Modifier = Modifier, sort: @Composable () -> Unit) {
    Layout(
        content = {
            ScreenTitle(title)
            Box(Modifier.padding(end = 20.dp, bottom = 8.dp)) { sort() }
        },
        modifier = modifier,
    ) { (titleText, buttons), constraints ->
        val width = constraints.maxWidth
        val buttonsPlaced = buttons.measure(Constraints(maxWidth = width))
        val titleWidth = titleText.maxIntrinsicWidth(Constraints.Infinity)
        if (titleFitsBeside(titleWidth, buttonsPlaced.width, width)) {
            val titlePlaced = titleText.measure(Constraints(maxWidth = width - buttonsPlaced.width))
            val height = maxOf(titlePlaced.height, buttonsPlaced.height)
            layout(width, height) {
                titlePlaced.place(0, (height - titlePlaced.height) / 2)
                buttonsPlaced.place(width - buttonsPlaced.width, (height - buttonsPlaced.height) / 2)
            }
        } else {
            val titlePlaced = titleText.measure(Constraints(maxWidth = width))
            layout(width, titlePlaced.height + buttonsPlaced.height) {
                titlePlaced.place(0, 0)
                buttonsPlaced.place(width - buttonsPlaced.width, titlePlaced.height)
            }
        }
    }
}

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
private val UnchosenTint = Color.White.copy(alpha = 0.45f)

// The two directions side by side in one glaze, named for what they mean
// here ("A to Z", "Newest"). The chosen one is the darker pill with white
// words and its arrow lit; the other is muted, so which is on reads at once.
@Composable
private fun DirectionPair(option: SortOption, descending: Boolean, modifier: Modifier = Modifier, onPick: (Boolean) -> Unit) {
    val choices = remember(option) { directionChoices(option) }
    val selected = choices.indexOfFirst { it.descending == descending }
    Glaze(modifier.fillMaxWidth().height(44.dp)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
            val half = maxWidth / 2
            val x by animateDpAsState(half * selected, spring(dampingRatio = 0.8f, stiffness = 350f), label = "direction")
            GlazeSelected(
                Modifier
                    .offset { IntOffset(x.roundToPx(), 0) }
                    .width(half)
                    .fillMaxHeight(),
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

private val OptionLine = OctoColors.TextPrimary.copy(alpha = 0.08f)

// The options not chosen: a step back from the chosen one's white.
private val OtherOptionTint = Color.White.copy(alpha = 0.7f)

// What a list can be ordered by, one above the other with hairlines
// between, as in the other glass menus. On the popup's dark film the darker
// pill alone is faint, so the chosen option also has white words and a
// white check, and the others are dimmed a little.
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
                if (chosen) GlazeSelected(Modifier.matchParentSize(), shape = OptionShape)
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
                        Icon(painterResource(OctoIcons.Check), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    } else {
                        Spacer(Modifier.size(18.dp))
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

// The letter a run of rows starts with. It stays at the top while its rows
// scroll, as a small dark pill rather than a full-width band, so the page's
// glow still shows around it.
@Composable
private fun LetterHeading(letter: Char) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            letter.toString(),
            style = OctoType.caption.copy(fontWeight = FontWeight.Bold),
            color = OctoColors.TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                .sizeIn(minWidth = 26.dp, minHeight = 26.dp)
                .wrapContentHeight(Alignment.CenterVertically)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
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

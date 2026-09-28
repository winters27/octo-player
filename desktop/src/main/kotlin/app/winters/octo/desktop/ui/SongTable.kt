package app.winters.octo.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import app.winters.octo.design.ControlHeight
import app.winters.octo.design.Corner
import app.winters.octo.design.DesktopType
import app.winters.octo.design.FrameSize
import app.winters.octo.design.Glaze
import app.winters.octo.design.GlazeLight
import app.winters.octo.design.GlazeSelected
import app.winters.octo.design.Glyph
import app.winters.octo.design.HoverFill
import app.winters.octo.design.IconAction
import app.winters.octo.design.IconSize
import app.winters.octo.design.MenuRow
import app.winters.octo.design.MenuSeparator
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.NowPlayingBars
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoTooltip
import app.winters.octo.design.OctoType
import app.winters.octo.design.RowHeight
import app.winters.octo.design.Separator
import app.winters.octo.design.Space
import app.winters.octo.design.TextAction
import app.winters.octo.design.Txt
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.library.AllColumns
import app.winters.octo.desktop.library.ColumnGap
import app.winters.octo.desktop.library.Cover
import app.winters.octo.desktop.library.RowEnd
import app.winters.octo.desktop.library.SongColumn
import app.winters.octo.desktop.library.TableRow
import app.winters.octo.desktop.library.TableSelection
import app.winters.octo.desktop.library.chosenColumns
import app.winters.octo.desktop.library.clicking
import app.winters.octo.desktop.library.fitColumns
import app.winters.octo.desktop.library.lengthText
import app.winters.octo.desktop.library.moved
import app.winters.octo.desktop.library.rowsOf
import app.winters.octo.desktop.library.sizeText
import app.winters.octo.desktop.library.specOf
import app.winters.octo.desktop.library.typeAhead
import app.winters.octo.desktop.library.widthOf
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.player.PlayFormat
import app.winters.octo.desktop.player.formatLabel
import app.winters.octo.desktop.player.libraryFormat
import app.winters.octo.desktop.ratingOf
import app.winters.octo.desktop.removeFromPlaylist
import app.winters.octo.desktop.removeFromQueue
import app.winters.octo.desktop.settings.TablePrefs
import app.winters.octo.server.serverTime
import app.winters.octo.sort.SortOrder
import app.winters.octo.subsonic.Song
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// How long two clicks may be apart to count as a double click, and how
// long typed letters keep adding to what is being looked for.
internal const val DOUBLE_CLICK_MS = 400L
private const val TYPE_AHEAD_MS = 1_000L

private val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

fun dateText(serverDate: String?): String = serverTime(serverDate)?.let { dates.format(Instant.ofEpochMilli(it)) }.orEmpty()

// How tall a row is at each density, from Settings or the columns menu.
fun rowHeightFor(density: String): Dp = when (density) {
    "compact" -> RowHeight.Compact
    "roomy" -> RowHeight.Roomy
    else -> RowHeight.Regular
}

// A table of songs, the desktop way, used by every song list: columns the
// listener chooses, orders and widens (kept per table under `id`), a
// heading that stays in view and sorts, rows picked with Ctrl and Shift or
// the keyboard, a double click or Enter to play, a right click for the
// song menu on everything picked, a play button and a More button under
// the pointer, and a small bar of actions while several rows are picked.
// `columns` is the table's own choice. `header` goes above the table in the
// same scrolling list, for the page's heading; `footer` below it.
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun SongTable(
    app: AppState,
    songs: List<Song>,
    columns: List<SongColumn>,
    state: LazyListState,
    modifier: Modifier = Modifier,
    id: String = "songs",
    order: SortOrder? = null,
    onSort: ((SortOrder) -> Unit)? = null,
    // Whether rows may show covers (at the roomy height); an album's own
    // table need not.
    covers: Boolean = true,
    number: (Int, Song) -> String = { index, _ -> "${index + 1}" },
    // A heading above a row, like a disc's name on an album.
    groupTitle: (Int) -> String? = { null },
    padding: PaddingValues = pagePadding(LocalBottomRoom.current),
    // Where picked rows live, for the menu's removals and the Delete key:
    // a playlist says which of its places they are.
    place: (List<TableRow>) -> SongPlace = { SongPlace.Library },
    // What the rows do with songs dropped on them, if anything.
    rowDrop: RowDrop? = null,
    empty: @Composable () -> Unit = {},
    footer: LazyListScope.() -> Unit = {},
    header: LazyListScope.() -> Unit = {},
) {
    val settings by app.settings.state.collectAsState()
    val prefs = settings.tables[id]
    val chosen = remember(prefs, columns) { chosenColumns(prefs, columns) }
    val rows = remember(songs) { rowsOf(songs) }
    val keys = remember(rows) { rows.map(TableRow::key) }
    val selection = remember(id) { TableSelection() }
    LaunchedEffect(keys) { selection.keepOnly(keys.toHashSet()) }
    // Only the playing song's id, so the rows redraw when it changes and
    // not on every word from the player.
    val currentId by remember(app.player) { app.player.state.map { it.current?.song?.id }.distinctUntilChanged() }
        .collectAsState(app.player.state.value.current?.song?.id)
    // Whether it is sounding, for the playing row's bars to move or rest.
    val sounding by remember(app.player) { app.player.state.map { it.playing }.distinctUntilChanged() }
        .collectAsState(app.player.state.value.playing)
    val focus = remember { FocusRequester() }
    val lists = LocalListFocus.current
    var hasFocus by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (hasFocus) lists.active = false } }
    val scope = rememberCoroutineScope()
    val typed = remember { TypedLetters() }
    val clicks = remember { LastClick() }
    // Widths while a column's edge is dragged, before they are saved.
    val dragging = remember(id) { mutableStateMapOf<SongColumn, Dp>() }
    val rowHeight = rowHeightFor(settings.density)
    val showCovers = covers && settings.density == "roomy"
    val pointer = LocalPointer.current
    val drag = LocalDrag.current

    fun picked(): List<Song> = selection.of(rows).map(TableRow::song)
    fun playFrom(key: String) {
        val at = rows.indexOfFirst { it.key == key }
        if (at >= 0) app.play(songs, at)
    }
    fun openMenu(at: IntOffset) {
        val rowsPicked = selection.of(rows).ifEmpty { return }
        val where = place(rowsPicked)
        app.popups.showAt(at) { close -> SongMenu(app, rowsPicked.map(TableRow::song), close, place = where) }
    }
    // Delete takes the picked rows out of the playlist or the queue they
    // are in; in the library it does nothing.
    fun removePicked(): Boolean {
        val rowsPicked = selection.of(rows).ifEmpty { return false }
        when (val where = place(rowsPicked)) {
            is SongPlace.Playlist -> {
                val playlist = app.playlists.firstOrNull { it.id == where.id } ?: return false
                if (!app.canEdit(playlist)) return false
                app.removeFromPlaylist(where.id, where.positions)
            }
            is SongPlace.Queue -> app.removeFromQueue(where.keys)
            SongPlace.Library -> return false
        }
        selection.clear()
        return true
    }
    // Brings a row into view after the keyboard moved to it.
    fun reveal(key: String) {
        val index = rows.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: return
        scope.launch {
            val info = state.layoutInfo
            val seen = info.visibleItemsInfo.firstOrNull { (it.key as? String)?.startsWith(RowKey) == true }
            val offset = seen?.let { item -> item.index - rows.indexOfFirst { RowKey + it.key == item.key } } ?: (info.totalItemsCount - rows.size)
            val target = offset + index
            val shown = info.visibleItemsInfo.filter { (it.key as? String)?.startsWith(RowKey) == true }
            val first = shown.firstOrNull()?.index ?: 0
            val last = shown.lastOrNull()?.index ?: 0
            when {
                target <= first -> state.scrollToItem((target - 1).coerceAtLeast(0))
                target >= last -> state.scrollToItem((target - shown.size + 3).coerceAtLeast(0))
            }
        }
    }
    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || rows.isEmpty() || !hasFocus) return false
        val command = if (app.mac) event.isMetaPressed else event.isCtrlPressed
        val page = (state.layoutInfo.visibleItemsInfo.size - 2).coerceAtLeast(1)
        fun move(step: Int): Boolean {
            selection.move(step, event.isShiftPressed, keys)?.let(::reveal)
            return true
        }
        return when {
            event.key == Key.DirectionDown -> move(1)
            event.key == Key.DirectionUp -> move(-1)
            event.key == Key.PageDown -> move(page)
            event.key == Key.PageUp -> move(-page)
            event.key == Key.MoveHome -> move(-rows.size)
            event.key == Key.MoveEnd -> move(rows.size)
            command && event.key == Key.A -> {
                selection.selectAll(keys)
                true
            }
            event.key == Key.Enter -> {
                when {
                    event.isShiftPressed -> app.addToQueue(picked())
                    command -> app.playNext(picked())
                    else -> selection.focus?.let(::playFrom)
                }
                true
            }
            event.key == Key.Delete || (app.mac && event.key == Key.Backspace) -> removePicked()
            event.key == Key.Escape && selection.picked.isNotEmpty() -> {
                selection.clear()
                true
            }
            !command && !event.isAltPressed -> {
                val letter = event.awtEventOrNull?.keyChar?.takeIf { it.isLetterOrDigit() } ?: return false
                val found = typeAhead(rows, typed.add(letter), selection.focus) ?: return true
                selection.jumpTo(found)
                reveal(found)
                true
            }
            else -> false
        }
    }
    fun widthOf(column: SongColumn): Dp = dragging[column] ?: widthOf(column, prefs)
    fun cell(scope: RowScope, column: SongColumn): Modifier =
        with(scope) { if (column == SongColumn.Title) Modifier.weight(1f) else Modifier.width(widthOf(column)) }

    BoxWithConstraints(
        modifier
            .focusRequester(focus)
            // The table's own focus, not a field in its heading (a filter),
            // so typing there never moves the rows.
            .onFocusChanged {
                hasFocus = it.isFocused
                lists.active = it.isFocused
            }
            .onPreviewKeyEvent(::onKey)
            .focusable(),
    ) {
        val usable = maxWidth - padding.calculateStartPadding(LayoutDirection.Ltr) - padding.calculateEndPadding(LayoutDirection.Ltr) - Space.M * 2
        val shown = remember(chosen, usable, prefs) { fitColumns(chosen, usable, prefs) }
        val favouriteShown = SongColumn.Favourite in shown
        // The heading sits on a plate only while rows pass under it.
        val stuck by remember {
            derivedStateOf {
                val at = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == HeaderKey }?.index ?: return@derivedStateOf false
                state.firstVisibleItemIndex > at || (state.firstVisibleItemIndex == at && state.firstVisibleItemScrollOffset > 0)
            }
        }
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = padding) {
            header()
            if (songs.isEmpty()) {
                item(key = "empty") { empty() }
                footer()
                return@LazyColumn
            }
            stickyHeader(key = HeaderKey) {
                HeaderRow(
                    app, id, shown, chosen, columns, order, onSort, stuck,
                    cell = ::cell,
                    widthNow = ::widthOf,
                    onDrag = { column, change -> dragging[column] = ((dragging[column] ?: widthOf(column, prefs)) + change).coerceAtLeast(specOf(column).min) },
                    onDragEnd = { column ->
                        dragging[column]?.let { width -> app.updateTable(id) { it.copy(widths = it.widths + (column.name to width.value)) } }
                        dragging.remove(column)
                    },
                )
            }
            itemsIndexed(rows, key = { _, row -> RowKey + row.key }) { index, row ->
                groupTitle(index)?.let { title ->
                    Txt(title, DesktopType.emphasis, OctoColors.TextSecondary, Modifier.padding(start = Space.M, top = if (index == 0) Space.Xs else Space.Xl, bottom = Space.S))
                }
                SongRow(
                    app, row, index, shown, rowHeight,
                    playing = row.song.id == currentId,
                    sounding = row.song.id == currentId && sounding,
                    picked = row.key in selection.picked,
                    focused = hasFocus && selection.focus == row.key,
                    covers = showCovers,
                    heartInTitle = !favouriteShown,
                    number = number,
                    cell = ::cell,
                    onPress = { primary, toggle, range ->
                        runCatching { focus.requestFocus() }
                        if (primary) {
                            if (clicks.isDouble(row.key) && !toggle && !range) playFrom(row.key)
                            else selection.click(row.key, toggle, range, keys)
                        } else {
                            selection.pickForMenu(row.key)
                            openMenu(pointer.point)
                        }
                    },
                    onPlay = { playFrom(row.key) },
                    // Dragging a picked row carries every picked song;
                    // any other row carries itself, and is picked.
                    onDragStart = { at ->
                        if (row.key !in selection.picked) selection.click(row.key, toggle = false, range = false, order = keys)
                        drag.start(picked(), at, place(selection.of(rows)))
                    },
                    onDrag = drag::move,
                    onDragEnd = { drag.drop() },
                    onDragCancel = drag::cancel,
                    onMore = { at ->
                        selection.pickForMenu(row.key)
                        openMenu(at)
                    },
                    drop = rowDrop?.takeIf { drag.active && it.takes(drag.from) }?.let { taken ->
                        "$id:${row.key}" to Modifier.dropTarget("$id:${row.key}", taken.action) { songs -> taken.onDrop(songs, drag.from, row, drag.below) }
                    },
                )
            }
            footer()
        }
        if (selection.picked.size > 1) {
            PickedBar(app, selection.picked.size, ::picked, { place(selection.of(rows)) }, { selection.clear() }, Modifier.align(Alignment.BottomCenter).padding(bottom = Space.Xl))
        }
    }
}

private const val RowKey = "row:"
private const val HeaderKey = "columns"

// Tells a second click on the same row soon after the first.
private class LastClick {
    private var row: String? = null
    private var at = 0L

    fun isDouble(key: String): Boolean {
        val now = System.currentTimeMillis()
        val double = key == row && now - at < DOUBLE_CLICK_MS
        row = if (double) null else key
        at = now
        return double
    }
}

// Letters typed in quick succession, to find a row by its title.
private class TypedLetters {
    private var text = ""
    private var at = 0L

    fun add(letter: Char): String {
        val now = System.currentTimeMillis()
        text = if (now - at > TYPE_AHEAD_MS) "$letter" else text + letter
        at = now
        return text
    }
}

// The columns' names: a click sorts by one (again, the other way round), a
// drag at a name's right edge widens or narrows it, and a right click (or
// the button at the end) chooses the columns and the row height.
@Composable
private fun HeaderRow(
    app: AppState,
    id: String,
    shown: List<SongColumn>,
    chosen: List<SongColumn>,
    defaults: List<SongColumn>,
    order: SortOrder?,
    onSort: ((SortOrder) -> Unit)?,
    stuck: Boolean,
    cell: (RowScope, SongColumn) -> Modifier,
    widthNow: (SongColumn) -> Dp,
    onDrag: (SongColumn, Dp) -> Unit,
    onDragEnd: (SongColumn) -> Unit,
) {
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    val openColumns = { app.popups.showUnder(anchor, width = ColumnsMenuWidth) { close -> ColumnsMenu(app, id, chosen, defaults, close) } }
    Column(Modifier.fillMaxWidth().then(if (stuck) Modifier.background(StuckFill) else Modifier)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(ControlHeight.M)
                .padding(horizontal = Space.M)
                .onRightClick(openColumns)
                .onGloballyPositioned { anchor = it.windowRect() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ColumnGap),
        ) {
            shown.forEach { column ->
                val sortable = onSort != null && order != null && column.sort != null
                val active = sortable && order.by == column.sort
                Box(cell(this, column), contentAlignment = if (specOf(column).endAligned) Alignment.CenterEnd else Alignment.CenterStart) {
                    Row(
                        Modifier.then(if (sortable) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { onSort(order.clicking(column)) } else Modifier),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (column == SongColumn.Favourite) {
                            Glyph(OctoIcons.Like, size = IconSize.Inline, tint = if (active) OctoColors.TextPrimary else OctoColors.TextMuted)
                        } else {
                            Txt(column.title.uppercase(), DesktopType.label, if (active) OctoColors.TextPrimary else OctoColors.TextMuted)
                        }
                        if (active) Glyph(if (order.descending) OctoIcons.Descending else OctoIcons.Ascending, Modifier.padding(start = Space.Xs), size = IconSize.Inline - Space.Xxs)
                    }
                    if (specOf(column).resizable) {
                        ResizeHandle(onDrag = { onDrag(column, it) }, onDone = { onDragEnd(column) }, modifier = Modifier.align(Alignment.CenterEnd))
                    }
                }
            }
            Box(Modifier.width(RowEnd), contentAlignment = Alignment.Center) {
                IconAction(OctoIcons.More, "Choose columns", openColumns, size = ControlHeight.S, iconSize = IconSize.Table, tint = OctoColors.TextMuted)
            }
        }
        Separator()
    }
}

private val StuckFill = OctoColors.Background.copy(alpha = 0.92f)
private val ColumnsMenuWidth = FrameSize.Menu

// The columns to show, each with its place, the row height, and the way
// back to the table's own choice.
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ColumnsMenu(app: AppState, id: String, chosen: List<SongColumn>, defaults: List<SongColumn>, close: () -> Unit) {
    val settings by app.settings.state.collectAsState()
    val now = chosenColumns(settings.tables[id], defaults)
    MenuTitle("Columns")
    AllColumns.filter { it != SongColumn.Title }.forEach { column ->
        val on = column in now
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.Xs), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                MenuRow(column.title, {
                    val next = if (on) now - column else now + column
                    app.updateTable(id) { it.copy(columns = next.map(SongColumn::name)) }
                }, if (on) OctoIcons.Check else null)
            }
            if (on) {
                IconAction(OctoIcons.Back, "Move earlier", { app.updateTable(id) { it.copy(columns = now.moved(column, -1).map(SongColumn::name)) } }, size = ControlHeight.Xs, iconSize = IconSize.Inline)
                IconAction(OctoIcons.Forward, "Move later", { app.updateTable(id) { it.copy(columns = now.moved(column, 1).map(SongColumn::name)) } }, size = ControlHeight.Xs, iconSize = IconSize.Inline)
            }
        }
    }
    MenuSeparator()
    MenuTitle("Row height")
    listOf("compact" to "Compact", "regular" to "Regular", "roomy" to "Roomy, with covers").forEach { (key, label) ->
        MenuRow(label, { app.settings.update { it.copy(density = key) } }, if (settings.density == key) OctoIcons.Check else null)
    }
    MenuSeparator()
    MenuRow("Go back to this table's columns", {
        app.updateTable(id) { TablePrefs() }
        close()
    }, enabled = settings.tables[id] != null)
}

// One song: the picked ones on the darker pill, the playing one tinted with
// the key colour and marked in the number column, the keyboard's row
// ringed. Under the pointer, the number becomes a play button and a More
// button shows at the end.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SongRow(
    app: AppState,
    row: TableRow,
    index: Int,
    shown: List<SongColumn>,
    height: Dp,
    playing: Boolean,
    // Only the playing row: whether it is sounding now or paused.
    sounding: Boolean,
    picked: Boolean,
    focused: Boolean,
    covers: Boolean,
    heartInTitle: Boolean,
    number: (Int, Song) -> String,
    cell: (RowScope, SongColumn) -> Modifier,
    onPress: (primary: Boolean, toggle: Boolean, range: Boolean) -> Unit,
    onPlay: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onMore: (IntOffset) -> Unit,
    // The row as a drop target, while a drag it takes is under way.
    drop: Pair<String, Modifier>? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    // Where the row is in the window, to follow a drag in window terms, and
    // the latest drag callbacks (the gesture is set up once per row).
    var placed by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val dragStart by rememberUpdatedState(onDragStart)
    val dragMove by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val dragCancel by rememberUpdatedState(onDragCancel)
    val hovered by interaction.collectIsHoveredAsState()
    val key = LocalKeyColour.current
    var moreAnchor by remember { mutableStateOf(IntRect.Zero) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .hoverable(interaction)
            .then(drop?.second ?: Modifier)
            .onGloballyPositioned { placed = it }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { local -> placed?.let { dragStart(it.localToWindow(local)) } },
                    onDragEnd = { dragEnd() },
                    onDragCancel = { dragCancel() },
                ) { change, _ ->
                    change.consume()
                    placed?.let { dragMove(it.localToWindow(change.position)) }
                }
            }
            .onPointerEvent(PointerEventType.Press) { event ->
                val keys = event.keyboardModifiers
                val toggle = if (app.mac) keys.isMetaPressed else keys.isCtrlPressed
                when {
                    event.buttons.isSecondaryPressed -> onPress(false, false, false)
                    event.buttons.isPrimaryPressed -> onPress(true, toggle, keys.isShiftPressed)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        when {
            picked -> GlazeSelected(Modifier.matchParentSize(), Corner.RowShape)
            hovered -> Box(Modifier.matchParentSize().background(HoverFill, Corner.RowShape))
        }
        if (focused) Box(Modifier.matchParentSize().border(FocusLine, OctoColors.FocusRing, Corner.RowShape))
        drop?.let { DropLine(it.first) }
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.M), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ColumnGap)) {
            shown.forEach { column ->
                Box(cell(this, column), contentAlignment = if (specOf(column).endAligned) Alignment.CenterEnd else Alignment.CenterStart) {
                    SongCell(app, column, index, row.song, playing, sounding, hovered, covers, heartInTitle, number, onPlay)
                }
            }
            Box(Modifier.width(RowEnd).onGloballyPositioned { moreAnchor = it.windowRect() }, contentAlignment = Alignment.Center) {
                if (hovered || picked) {
                    IconAction(OctoIcons.More, "More", { onMore(IntOffset(moreAnchor.left, moreAnchor.bottom)) }, size = ControlHeight.S, iconSize = IconSize.Table, tint = OctoColors.TextSecondary)
                }
            }
        }
    }
}

private val FocusLine = Space.Xxs / 2

@Composable
private fun SongCell(
    app: AppState,
    column: SongColumn,
    index: Int,
    song: Song,
    playing: Boolean,
    sounding: Boolean,
    hovered: Boolean,
    covers: Boolean,
    heartInTitle: Boolean,
    number: (Int, Song) -> String,
    onPlay: () -> Unit,
) {
    val muted = OctoColors.TextMuted
    val numbers = DesktopType.table.copy(fontFeatureSettings = "tnum")
    when (column) {
        SongColumn.Number -> when {
            // Under the pointer, the playing row pauses and carries on
            // rather than starting the song again.
            hovered && playing -> IconAction(if (sounding) OctoIcons.Pause else OctoIcons.Play, if (sounding) "Pause" else "Play", app.player::togglePlay, size = ControlHeight.S, iconSize = IconSize.Table)
            hovered -> IconAction(OctoIcons.Play, "Play from here", onPlay, size = ControlHeight.S, iconSize = IconSize.Table)
            // The song playing: the bars move while it sounds, rest while paused.
            playing -> NowPlayingBars(sounding, Modifier.size(IconSize.Inline))
            else -> Txt(number(index, song), numbers, muted, align = TextAlign.End)
        }
        SongColumn.Title -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M + Space.Xxs)) {
            if (covers) Cover(song.coverArt, Modifier.size(RowHeight.Roomy - Space.L), shape = Corner.ArtSShape, placeholder = OctoIcons.Songs)
            val failed = app.failedSongs[song.id]
            Txt(
                song.title,
                DesktopType.tableTitle,
                if (failed != null) muted else OctoColors.TextPrimary,
                Modifier.weight(1f, fill = false),
            )
            if (heartInTitle && app.isStarred(song)) Glyph(OctoIcons.Liked, size = IconSize.Inline - Space.Xxs, tint = OctoColors.TextSecondary)
            // A song that would not play this time says why on hover.
            if (failed != null) OctoTooltip(failed) { Glyph(OctoIcons.Info, size = IconSize.Inline - Space.Xxs, tint = OctoColors.SignalOrange) }
        }
        SongColumn.Artist -> LinkText(song.displayArtist ?: song.artist.orEmpty(), song.artistId) { app.navigator.go(Page.Artist(it, song.artist.orEmpty())) }
        SongColumn.Album -> LinkText(song.album.orEmpty(), song.albumId) { app.navigator.go(Page.Album(it)) }
        SongColumn.Genre -> {
            val genre = (song.genres.firstOrNull() ?: song.genre).orEmpty()
            if (genre.isNotBlank()) LinkText(genre, genre) { app.navigator.go(Page.Genre(it)) }
        }
        SongColumn.Composer -> Txt(song.displayComposer.orEmpty(), DesktopType.table, OctoColors.TextSecondary)
        SongColumn.Year -> Txt(song.year?.takeIf { it > 0 }?.toString().orEmpty(), numbers, muted, align = TextAlign.End)
        SongColumn.Added -> Txt(dateText(song.created), numbers, muted)
        SongColumn.Played -> Txt(dateText(song.played), numbers, muted)
        SongColumn.Plays -> Txt(song.playCount?.takeIf { it > 0 }?.toString().orEmpty(), numbers, muted, align = TextAlign.End)
        // Shown only: rating is in the menu, where a stray click cannot set
        // it (on an Octo server one star can take a song out of the library).
        SongColumn.Rating -> Stars(app.ratingOf(song))
        SongColumn.Format -> Txt(formatLabel(PlayFormat(libraryFormat(song), null)).orEmpty(), numbers, muted)
        SongColumn.Bpm -> Txt(song.bpm?.takeIf { it > 0 }?.toString().orEmpty(), numbers, muted, align = TextAlign.End)
        SongColumn.Size -> Txt(song.size?.takeIf { it > 0 }?.let { sizeText(it) }.orEmpty(), numbers, muted, align = TextAlign.End)
        SongColumn.Favourite -> {
            val starred = app.isStarred(song)
            if (starred || hovered) {
                IconAction(
                    if (starred) OctoIcons.Liked else OctoIcons.Like,
                    if (starred) "Remove from favourites" else "Add to favourites",
                    { app.setStarred(listOf(song), !starred) },
                    size = ControlHeight.S,
                    iconSize = IconSize.Table,
                    tint = if (starred) OctoColors.TextSecondary else muted,
                )
            }
        }
        SongColumn.Length -> Txt(lengthText(song.duration), numbers, muted, align = TextAlign.End)
    }
}

// A song's rating as five small stars, the given ones filled.
@Composable
private fun Stars(rating: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.Xxs)) {
        (1..5).forEach { star ->
            Glyph(if (star <= rating) OctoIcons.StarFilled else OctoIcons.Star, size = IconSize.Inline, tint = if (star <= rating) OctoColors.TextSecondary else OctoColors.TextMuted.copy(alpha = 0.35f))
        }
    }
}

// While several rows are picked: how many, and what can be done with them
// all at once, over the foot of the table.
@Composable
private fun PickedBar(app: AppState, count: Int, picked: () -> List<Song>, place: () -> SongPlace, clear: () -> Unit, modifier: Modifier) {
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    Glaze(modifier.onGloballyPositioned { anchor = it.windowRect() }, shape = Corner.PanelShape, light = GlazeLight.Lifted) {
        Row(Modifier.padding(horizontal = Space.L, vertical = Space.S), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M)) {
            Txt("$count songs picked", DesktopType.emphasis, modifier = Modifier.padding(end = Space.Xs))
            TextAction("Play", { app.play(picked()) })
            TextAction("Play next", { app.playNext(picked()) })
            TextAction("Add to queue", { app.addToQueue(picked()) })
            IconAction(OctoIcons.More, "More for these songs", { app.popups.showUnder(anchor) { close -> SongMenu(app, picked(), close, place = place()) } }, size = ControlHeight.S, iconSize = IconSize.Table)
            IconAction(OctoIcons.Close, "Let go of the picked songs", clear, size = ControlHeight.S, iconSize = IconSize.Table)
        }
    }
}

// Words that open a page when clicked: an artist's or album's name.
@Composable
fun LinkText(text: String, id: String?, width: Dp? = null, open: (String) -> Unit) {
    val modifier = if (width != null) Modifier.width(width) else Modifier
    if (id.isNullOrEmpty()) {
        Txt(text, DesktopType.table, OctoColors.TextSecondary, modifier)
    } else {
        Txt(
            text,
            DesktopType.table,
            OctoColors.TextSecondary,
            modifier.pointerHoverIcon(PointerIcon.Hand).clickable { open(id) },
        )
    }
}

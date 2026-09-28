package app.winters.octo.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.library.Cover
import app.winters.octo.desktop.library.SongColumn
import app.winters.octo.desktop.library.TableSelection
import app.winters.octo.desktop.library.clicking
import app.winters.octo.desktop.library.lengthText
import app.winters.octo.desktop.library.serverTime
import app.winters.octo.desktop.nav.Page
import app.winters.octo.design.Glyph
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.Separator
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import app.winters.octo.sort.SortOrder
import app.winters.octo.subsonic.Song
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val RowShape = RoundedCornerShape(8.dp)
private val PickedFill = Color.White.copy(alpha = 0.10f)

// How long two clicks may be apart to count as a double click.
private const val DOUBLE_CLICK_MS = 400L

private val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

fun dateText(serverDate: String?): String = serverTime(serverDate)?.let { dates.format(Instant.ofEpochMilli(it)) }.orEmpty()

// How wide each column is: a share of what is left, or a fixed width.
private fun RowScope.cell(column: SongColumn): Modifier = when (column) {
    SongColumn.Number -> Modifier.width(44.dp)
    SongColumn.Title -> Modifier.weight(3f)
    SongColumn.Artist, SongColumn.Album -> Modifier.weight(2f)
    SongColumn.Year -> Modifier.width(56.dp)
    SongColumn.Length -> Modifier.width(64.dp)
    SongColumn.Plays -> Modifier.width(56.dp)
    // Set off from a number column on its left, which lines up on the right.
    SongColumn.Added, SongColumn.Played -> Modifier.width(DateWidth).padding(start = 16.dp)
}

private val DateWidth = 128.dp

// The fixed width a column takes, or the least a shared one should get
// before columns start being left out.
private fun roomFor(column: SongColumn): Dp = when (column) {
    SongColumn.Number -> 44.dp
    SongColumn.Title -> 200.dp
    SongColumn.Artist, SongColumn.Album -> 120.dp
    SongColumn.Year, SongColumn.Plays -> 56.dp
    SongColumn.Length -> 64.dp
    SongColumn.Added, SongColumn.Played -> DateWidth
}

// The columns that fit in `width`: when the table is narrow (a side panel
// open, a small window), the least needed go first, so the titles keep
// their room rather than being cut short beside empty columns.
fun fitColumns(columns: List<SongColumn>, width: Dp): List<SongColumn> {
    val shown = columns.toMutableList()
    fun needed() = shown.fold(16.dp) { sum, column -> sum + roomFor(column) + 8.dp }
    for (column in DropOrder) {
        if (needed() <= width) break
        shown.remove(column)
    }
    return shown
}

private val DropOrder = listOf(SongColumn.Added, SongColumn.Played, SongColumn.Plays, SongColumn.Year, SongColumn.Album, SongColumn.Artist)

private fun alignOf(column: SongColumn) = when (column) {
    SongColumn.Length, SongColumn.Plays, SongColumn.Year -> TextAlign.End
    else -> TextAlign.Start
}

// A table of songs, the desktop way: sortable columns, rows that lift under
// the pointer, picking with Ctrl and Shift, a double click to play, and a
// right click for the song menu (on everything picked). `header` goes above
// the table in the same scrolling list, for the page's own heading.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SongTable(
    app: AppState,
    songs: List<Song>,
    columns: List<SongColumn>,
    state: LazyListState,
    modifier: Modifier = Modifier,
    order: SortOrder? = null,
    onSort: ((SortOrder) -> Unit)? = null,
    covers: Boolean = true,
    number: (Int, Song) -> String = { index, _ -> "${index + 1}" },
    // A heading above a row, like a disc's name on an album.
    groupTitle: (Int) -> String? = { null },
    padding: PaddingValues = pagePadding(LocalBottomRoom.current),
    empty: @Composable () -> Unit = {},
    header: LazyListScope.() -> Unit = {},
) {
    val selection = remember(songs) { TableSelection() }
    val pointer = LocalPointer.current
    val playing by app.player.state.collectAsState()
    val currentId = playing.current?.song?.id
    val clicks = remember(songs) { LastClick() }
    BoxWithConstraints(modifier) {
        val shown = remember(columns, maxWidth) { fitColumns(columns, maxWidth - padding.calculateStartPadding(LayoutDirection.Ltr) - padding.calculateEndPadding(LayoutDirection.Ltr)) }
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = padding) {
            header()
            if (songs.isEmpty()) {
                item(key = "empty") { empty() }
                return@LazyColumn
            }
            item(key = "columns") {
                Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    shown.forEach { column ->
                        val sortable = onSort != null && order != null && column.sort != null
                        val active = sortable && order.by == column.sort
                        Row(
                            cell(column)
                                .then(if (sortable) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { onSort(order.clicking(column)) } else Modifier)
                                .padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = if (alignOf(column) == TextAlign.End) Arrangement.End else Arrangement.Start,
                        ) {
                            Txt(column.title, OctoType.caption, if (active) OctoColors.TextPrimary else OctoColors.TextMuted)
                            if (active) Glyph(if (order.descending) OctoIcons.Descending else OctoIcons.Ascending, Modifier.padding(start = 4.dp), size = 12.dp)
                        }
                    }
                }
                Separator(Modifier.padding(bottom = 4.dp))
            }
            itemsIndexed(songs, key = { index, song -> "$index:${song.id}" }) { index, song ->
                groupTitle(index)?.let { title ->
                    Txt(title, OctoType.label, OctoColors.TextSecondary, Modifier.padding(start = 8.dp, top = if (index == 0) 4.dp else 18.dp, bottom = 6.dp))
                }
                val picked = index in selection.picked
                val isCurrent = song.id == currentId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(if (covers) 48.dp else 40.dp)
                        .hoverLift(RowShape, clickable = false, lifted = false)
                        .then(if (picked) Modifier.background(PickedFill, RowShape) else Modifier)
                        .onPointerEvent(PointerEventType.Press) { event ->
                            val keys = event.keyboardModifiers
                            val toggle = if (app.mac) keys.isMetaPressed else keys.isCtrlPressed
                            when {
                                event.buttons.isSecondaryPressed -> {
                                    selection.pickForMenu(index)
                                    val chosen = selection.of(songs)
                                    app.popups.showAt(pointer.point) { close -> SongMenu(app, chosen, close) }
                                }
                                event.buttons.isPrimaryPressed -> {
                                    if (clicks.isDouble(index) && !toggle && !keys.isShiftPressed) {
                                        app.play(songs, index)
                                    } else {
                                        selection.click(index, toggle, keys.isShiftPressed)
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    shown.forEach { column ->
                        Box(cell(column).padding(end = 8.dp), contentAlignment = if (alignOf(column) == TextAlign.End) Alignment.CenterEnd else Alignment.CenterStart) {
                            SongCell(app, column, index, song, isCurrent, covers, number)
                        }
                    }
                }
            }
        }
    }
}

// Tells a second click on the same row soon after the first.
private class LastClick {
    private var row = -1
    private var at = 0L

    fun isDouble(index: Int): Boolean {
        val now = System.currentTimeMillis()
        val double = index == row && now - at < DOUBLE_CLICK_MS
        row = if (double) -1 else index
        at = now
        return double
    }
}

@Composable
private fun SongCell(app: AppState, column: SongColumn, index: Int, song: Song, isCurrent: Boolean, covers: Boolean, number: (Int, Song) -> String) {
    val muted = OctoColors.TextMuted
    when (column) {
        SongColumn.Number ->
            if (isCurrent) Glyph(OctoIcons.Lossless, size = 16.dp, tint = OctoColors.Accent)
            else Txt(number(index, song), OctoType.caption, muted)
        SongColumn.Title -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (covers) Cover(song.coverArt, Modifier.size(34.dp), shape = RoundedCornerShape(5.dp), placeholder = OctoIcons.Songs)
            Txt(song.title, OctoType.bodySmall, if (isCurrent) OctoColors.Accent else OctoColors.TextPrimary)
            if (app.isStarred(song)) Glyph(OctoIcons.Liked, size = 12.dp, tint = OctoColors.TextSecondary)
        }
        SongColumn.Artist -> LinkText(song.displayArtist ?: song.artist.orEmpty(), song.artistId) { app.navigator.go(Page.Artist(it, song.artist.orEmpty())) }
        SongColumn.Album -> LinkText(song.album.orEmpty(), song.albumId) { app.navigator.go(Page.Album(it)) }
        SongColumn.Year -> Txt(song.year?.takeIf { it > 0 }?.toString().orEmpty(), OctoType.caption, muted, align = TextAlign.End)
        SongColumn.Length -> Txt(lengthText(song.duration), OctoType.caption, muted, align = TextAlign.End)
        SongColumn.Plays -> Txt(song.playCount?.takeIf { it > 0 }?.toString().orEmpty(), OctoType.caption, muted, align = TextAlign.End)
        SongColumn.Added -> Txt(dateText(song.created), OctoType.caption, muted)
        SongColumn.Played -> Txt(dateText(song.played), OctoType.caption, muted)
    }
}

// Words that open a page when clicked: an artist's or album's name.
@Composable
fun LinkText(text: String, id: String?, width: Dp? = null, open: (String) -> Unit) {
    val modifier = if (width != null) Modifier.width(width) else Modifier
    if (id.isNullOrEmpty()) {
        Txt(text, OctoType.bodySmall, OctoColors.TextSecondary, modifier)
    } else {
        Txt(
            text,
            OctoType.bodySmall,
            OctoColors.TextSecondary,
            modifier.pointerHoverIcon(PointerIcon.Hand).clickable { open(id) },
        )
    }
}

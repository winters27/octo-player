package app.winters.octo.desktop.library

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.winters.octo.desktop.settings.TablePrefs

// How a column sits in a song table: its width (the Title column takes
// what is left), the least it may be dragged to, whether it can be dragged
// at all, and whether its words line up on the right, as numbers do.
data class ColumnSpec(val width: Dp, val min: Dp, val resizable: Boolean, val endAligned: Boolean)

fun specOf(column: SongColumn): ColumnSpec = when (column) {
    SongColumn.Number -> ColumnSpec(44.dp, 44.dp, resizable = false, endAligned = true)
    SongColumn.Title -> ColumnSpec(240.dp, 160.dp, resizable = false, endAligned = false)
    SongColumn.Artist -> ColumnSpec(190.dp, 90.dp, resizable = true, endAligned = false)
    SongColumn.Album -> ColumnSpec(210.dp, 90.dp, resizable = true, endAligned = false)
    SongColumn.Genre -> ColumnSpec(130.dp, 70.dp, resizable = true, endAligned = false)
    SongColumn.Composer -> ColumnSpec(150.dp, 70.dp, resizable = true, endAligned = false)
    SongColumn.Year -> ColumnSpec(56.dp, 48.dp, resizable = false, endAligned = true)
    SongColumn.Added, SongColumn.Played -> ColumnSpec(112.dp, 80.dp, resizable = true, endAligned = false)
    SongColumn.Plays -> ColumnSpec(56.dp, 48.dp, resizable = false, endAligned = true)
    SongColumn.Rating -> ColumnSpec(84.dp, 84.dp, resizable = false, endAligned = false)
    SongColumn.Format -> ColumnSpec(96.dp, 64.dp, resizable = true, endAligned = false)
    SongColumn.Bpm -> ColumnSpec(52.dp, 44.dp, resizable = false, endAligned = true)
    SongColumn.Size -> ColumnSpec(72.dp, 56.dp, resizable = false, endAligned = true)
    SongColumn.Favourite -> ColumnSpec(32.dp, 32.dp, resizable = false, endAligned = false)
    SongColumn.Length -> ColumnSpec(60.dp, 52.dp, resizable = false, endAligned = true)
}

// The gap between columns, and the room kept at the end of each row for
// its More button.
val ColumnGap = 12.dp
val RowEnd = 32.dp

// Which columns give way first when the table is narrow (a side panel
// open, a small window): the least needed first. Title, the number, the
// heart and the length always stay.
private val GiveWay = listOf(
    SongColumn.Size, SongColumn.Bpm, SongColumn.Format, SongColumn.Composer, SongColumn.Genre,
    SongColumn.Played, SongColumn.Added, SongColumn.Rating, SongColumn.Plays, SongColumn.Year,
    SongColumn.Album, SongColumn.Artist,
)

// The columns shown, in the listener's order when they chose one, else
// the table's own, with names no longer known left out.
fun chosenColumns(prefs: TablePrefs?, defaults: List<SongColumn>): List<SongColumn> {
    val named = prefs?.columns?.mapNotNull(SongColumn::named)?.distinct().orEmpty()
    val shown = named.ifEmpty { defaults }
    // A table always has its titles.
    return if (SongColumn.Title in shown) shown else listOf(SongColumn.Title) + shown
}

// The width each shown column gets, from the listener's drags where there
// are any.
fun widthOf(column: SongColumn, prefs: TablePrefs?): Dp {
    val spec = specOf(column)
    val dragged = prefs?.widths?.get(column.name)?.takeIf { spec.resizable }?.dp
    return (dragged ?: spec.width).coerceAtLeast(spec.min)
}

// The columns that fit in `width`, leaving out the least needed until the
// rest, with the title at its least, fit.
fun fitColumns(columns: List<SongColumn>, width: Dp, prefs: TablePrefs? = null): List<SongColumn> {
    val shown = columns.toMutableList()
    fun needed(): Dp = shown.fold(RowEnd) { sum, column ->
        sum + ColumnGap + if (column == SongColumn.Title) specOf(column).min else widthOf(column, prefs)
    }
    for (column in GiveWay) {
        if (needed() <= width) break
        shown.remove(column)
    }
    return shown
}

// The text columns that share a table's width with the title.
private val Flexible = setOf(SongColumn.Artist, SongColumn.Album, SongColumn.Genre, SongColumn.Composer)

// How many shares of spare room the title takes for each one a text column takes.
private const val TITLE_SHARES = 3

// Each shown column's width in a table `width` wide, but the title's, which
// takes what is left. Text columns the listener has not widened by hand
// share spare room with the title (the title three parts to their one
// each, up to twice their own width), and give room back, down to their
// least, when the title would be narrower than its own width.
fun columnWidths(shown: List<SongColumn>, width: Dp, prefs: TablePrefs? = null): Map<SongColumn, Dp> {
    val widths = shown.filter { it != SongColumn.Title }.associateWith { widthOf(it, prefs) }.toMutableMap()
    val flex = widths.keys.filter { it in Flexible && prefs?.widths?.get(it.name) == null }
    if (flex.isEmpty() || SongColumn.Title !in shown) return widths
    val used = widths.values.fold(RowEnd + ColumnGap * shown.size) { sum, w -> sum + w }
    val spare = width - used - specOf(SongColumn.Title).width
    if (spare > 0.dp) {
        val part = spare / (flex.size + TITLE_SHARES)
        flex.forEach { column -> widths[column] = (widths.getValue(column) + part).coerceAtMost(widthOf(column, prefs) * 2) }
    } else {
        val room = flex.associateWith { widths.getValue(it) - specOf(it).min }
        val total = room.values.fold(0.dp) { sum, w -> sum + w }
        if (total > 0.dp) {
            val share = (-spare / total).coerceAtMost(1f)
            flex.forEach { column -> widths[column] = widths.getValue(column) - room.getValue(column) * share }
        }
    }
    return widths
}

// The columns a table can offer to show: all of them.
val AllColumns: List<SongColumn> = SongColumn.entries

// Moves a column one place earlier (-1) or later (+1) among those shown.
fun List<SongColumn>.moved(column: SongColumn, step: Int): List<SongColumn> {
    val at = indexOf(column)
    val to = (at + step).coerceIn(0, lastIndex)
    if (at < 0 || at == to) return this
    return toMutableList().apply { add(to, removeAt(at)) }
}

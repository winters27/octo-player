package app.winters.octo.desktop.ui

import androidx.compose.ui.unit.dp
import app.winters.octo.desktop.library.SongColumn
import app.winters.octo.desktop.library.SongColumn.Added
import app.winters.octo.desktop.library.SongColumn.Album
import app.winters.octo.desktop.library.SongColumn.Artist
import app.winters.octo.desktop.library.SongColumn.Length
import app.winters.octo.desktop.library.SongColumn.Number
import app.winters.octo.desktop.library.SongColumn.Plays
import app.winters.octo.desktop.library.SongColumn.Title
import app.winters.octo.desktop.library.SongColumn.Year
import kotlin.test.Test
import kotlin.test.assertEquals

class SongTableTest {
    private val all: List<SongColumn> = listOf(Number, Title, Artist, Album, Year, Length, Plays, Added)

    @Test
    fun aWideTableShowsEveryColumn() {
        assertEquals(all, fitColumns(all, 1200.dp))
    }

    @Test
    fun aNarrowTableLeavesOutTheLeastNeededFirst() {
        // A side panel open: dates, plays and years go before names do.
        assertEquals(listOf(Number, Title, Artist, Album, Year, Length), fitColumns(all, 700.dp))
        assertEquals(listOf(Number, Title, Artist, Album, Length), fitColumns(all, 640.dp))
        // Very narrow: only the title and its length are left with the number.
        assertEquals(listOf(Number, Title, Length), fitColumns(all, 380.dp))
    }
}

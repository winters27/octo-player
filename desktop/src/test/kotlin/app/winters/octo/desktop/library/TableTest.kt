package app.winters.octo.desktop.library

import androidx.compose.ui.unit.dp
import app.winters.octo.desktop.library.SongColumn.Added
import app.winters.octo.desktop.library.SongColumn.Album
import app.winters.octo.desktop.library.SongColumn.Artist
import app.winters.octo.desktop.library.SongColumn.Genre
import app.winters.octo.desktop.library.SongColumn.Length
import app.winters.octo.desktop.library.SongColumn.Number
import app.winters.octo.desktop.library.SongColumn.Plays
import app.winters.octo.desktop.library.SongColumn.Title
import app.winters.octo.desktop.library.SongColumn.Year
import app.winters.octo.desktop.settings.TablePrefs
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TableTest {
    private val all = listOf(Number, Title, Artist, Album, Year, Length, Plays, Added)

    // ---- Columns ----

    @Test
    fun aWideTableShowsEveryColumn() {
        assertEquals(all, fitColumns(all, 1200.dp))
    }

    @Test
    fun aNarrowTableLeavesOutTheLeastNeededFirst() {
        assertEquals(listOf(Number, Title, Artist, Album, Year, Length, Plays), fitColumns(all, 900.dp))
        assertEquals(listOf(Number, Title, Artist, Length), fitColumns(all, 700.dp))
        // Very narrow: the title and its length stay with the number.
        assertEquals(listOf(Number, Title, Length), fitColumns(all, 300.dp))
    }

    @Test
    fun theListenersColumnsAndOrderWin() {
        val prefs = TablePrefs(columns = listOf("Album", "Title", "Nonsense", "Genre"))
        assertEquals(listOf(Album, Title, Genre), chosenColumns(prefs, all))
        assertEquals(all, chosenColumns(TablePrefs(), all))
        // A table never loses its titles.
        assertEquals(listOf(Title, Artist), chosenColumns(TablePrefs(columns = listOf("Artist")), all))
    }

    @Test
    fun aDraggedWidthCountsOnlyForColumnsThatDrag() {
        val prefs = TablePrefs(widths = mapOf("Artist" to 300f, "Year" to 300f, "Album" to 10f))
        assertEquals(300.dp, widthOf(Artist, prefs))
        assertEquals(specOf(Year).width, widthOf(Year, prefs))
        assertEquals("never below its least", specOf(Album).min, widthOf(Album, prefs))
    }

    // The songs page's own columns, as a wide window shows them.
    private val songsPage = listOf(Number, Title, Artist, Album, Added, SongColumn.Favourite, Length)

    // What the row takes besides the title, at these widths.
    private fun besidesTitle(widths: Map<SongColumn, androidx.compose.ui.unit.Dp>, shown: List<SongColumn>) =
        widths.values.fold(RowEnd + ColumnGap * shown.size) { sum, w -> sum + w }

    @Test
    fun onAWideWindowTheArtistAndAlbumShareTheRoom() {
        val widths = columnWidths(songsPage, 1600.dp)
        assertTrue("artist grows: ${widths[Artist]}", widths.getValue(Artist) > specOf(Artist).width)
        assertTrue("album grows: ${widths[Album]}", widths.getValue(Album) > specOf(Album).width)
        // The title still has the most of it.
        val title = 1600.dp - besidesTitle(widths, songsPage)
        assertTrue("title $title beats album ${widths[Album]}", title > widths.getValue(Album))
        // Numbers and dates keep their own widths.
        assertEquals(specOf(Added).width, widths[Added])
        assertEquals(specOf(Length).width, widths[Length])
    }

    @Test
    fun onAnUltrawideWindowTheyStopAtTwiceTheirWidth() {
        val widths = columnWidths(songsPage, 3200.dp)
        assertEquals(specOf(Artist).width * 2, widths[Artist])
        assertEquals(specOf(Album).width * 2, widths[Album])
    }

    @Test
    fun onANarrowWindowTheyGiveRoomBackToTheTitle() {
        val shown = listOf(Number, Title, Artist, Plays, SongColumn.Favourite, Length)
        val widths = columnWidths(shown, 650.dp)
        assertTrue("artist narrower: ${widths[Artist]}", widths.getValue(Artist) < specOf(Artist).width)
        assertTrue("never below its least", widths.getValue(Artist) >= specOf(Artist).min)
        val title = 650.dp - besidesTitle(widths, shown)
        assertTrue("title $title has its own width back", title >= specOf(Title).width - 1.dp)
    }

    @Test
    fun aColumnWidenedByHandKeepsItsWidth() {
        val prefs = TablePrefs(widths = mapOf("Artist" to 150f))
        assertEquals(150.dp, columnWidths(songsPage, 1600.dp, prefs)[Artist])
        assertEquals(150.dp, columnWidths(songsPage, 650.dp, prefs)[Artist])
    }

    @Test
    fun columnsMoveOnePlaceAtATime() {
        assertEquals(listOf(Number, Artist, Title), listOf(Number, Title, Artist).moved(Artist, -1))
        assertEquals(listOf(Number, Title, Artist), listOf(Number, Title, Artist).moved(Artist, 1))
    }

    // ---- Rows and picks ----

    private val songs = listOf(Song("a", "Airbag"), Song("b", "Bones"), Song("a", "Airbag"), Song("c", "Creep"), Song("d", "Déjà vu"))
    private val rows = rowsOf(songs)
    private val keys = rows.map { it.key }

    @Test
    fun theSameSongTwiceGetsTwoKeys() {
        assertEquals(listOf("a", "b", "a#2", "c", "d"), keys)
    }

    @Test
    fun picksFollowSongsThroughANewSort() {
        val pick = TableSelection()
        pick.click("b", toggle = false, range = false, order = keys)
        pick.click("c", toggle = true, range = false, order = keys)
        val reversed = keys.reversed()
        pick.keepOnly(reversed.toSet())
        assertEquals(setOf("b", "c"), pick.picked)
        pick.keepOnly(setOf("c"))
        assertEquals(setOf("c"), pick.picked)
    }

    @Test
    fun shiftPicksARunAndTheKeyboardStretchesIt() {
        val pick = TableSelection()
        pick.click("b", toggle = false, range = false, order = keys)
        pick.click("c", toggle = false, range = true, order = keys)
        assertEquals(setOf("b", "a#2", "c"), pick.picked)
        pick.move(1, stretch = true, order = keys)
        assertEquals(setOf("b", "a#2", "c", "d"), pick.picked)
        pick.move(-10, stretch = false, order = keys)
        assertEquals(setOf("a"), pick.picked)
        assertEquals("a", pick.focus)
    }

    @Test
    fun aRightClickInsideThePickKeepsIt() {
        val pick = TableSelection()
        pick.selectAll(keys)
        pick.pickForMenu("c")
        assertEquals(keys.toSet(), pick.picked)
        pick.clear()
        pick.pickForMenu("c")
        assertEquals(setOf("c"), pick.picked)
    }

    @Test
    fun typingFindsATitleAndTheSameLetterMovesOn() {
        assertEquals("a", typeAhead(rows, "a", null))
        assertEquals("a#2", typeAhead(rows, "a", "a"))
        assertEquals("d", typeAhead(rows, "deja", null))
        assertEquals("c", typeAhead(rows, "cr", "c"))
        assertNull(typeAhead(rows, "z", null))
        assertTrue(typeAhead(rows, "", null) == null)
    }
}

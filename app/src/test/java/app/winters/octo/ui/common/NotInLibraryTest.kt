package app.winters.octo.ui.common

import androidx.compose.ui.unit.dp
import app.winters.octo.discovery.DownloadPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

// The quiet mark on artwork of what is not in the library.
class NotInLibraryTest {
    @Test
    fun onlyASongFoundOnlineAndNotYetInTheLibraryIsMarked() {
        assertTrue(isOutsideLibrary("find:e1", emptySet()))
        // A library song, from the phone or the server.
        assertFalse(isOutsideLibrary("t-42", emptySet()))
        assertFalse(isOutsideLibrary("server:x:mf-1", emptySet()))
        // Downloaded into the library since: the mark goes.
        assertFalse(isOutsideLibrary("find:e1", setOf("find:e1")))
        assertTrue(isOutsideLibrary("find:e2", setOf("find:e1")))
        assertFalse(isOutsideLibrary(null, emptySet()))
    }

    @Test
    fun addingAnAlbumsMissingSongsSaysHowMany() {
        assertEquals("Add the missing song", addMissingLabel(1))
        assertEquals("Add the 9 missing songs", addMissingLabel(9))
        assertEquals("2 of 11 in your library", albumLibraryNote(11, 9))
    }

    @Test
    fun onlyFinishedDownloadsCountAsInTheLibrary() {
        val phases = mapOf(
            "find:a" to DownloadPhase.Done,
            "find:b" to DownloadPhase.Queued,
            "find:c" to DownloadPhase.Downloading(0.5f),
            "find:d" to DownloadPhase.Adding,
            "find:e" to DownloadPhase.Failed("gone"),
            "find:f" to DownloadPhase.None,
        )
        assertEquals(setOf("find:a"), adoptedFinds(phases))
        // Still on its way, so still marked.
        assertTrue(isOutsideLibrary("find:c", adoptedFinds(phases)))
    }

    @Test
    fun aRowThatOffersAddingShowsTheButtonAndNoMark() {
        assertEquals(AddSign.Button, rowAddSign("find:e1", emptySet(), offersAdd = true))
        // Kept once added, so its check shows before the page swaps the row.
        assertEquals(AddSign.Button, rowAddSign("find:e1", setOf("find:e1"), offersAdd = true))
        // A library song never gets either.
        assertEquals(AddSign.None, rowAddSign("t-42", emptySet(), offersAdd = true))
    }

    @Test
    fun aRowElsewhereShowsTheMarkUntilTheSongIsAdded() {
        assertEquals(AddSign.Mark, rowAddSign("find:e1", emptySet(), offersAdd = false))
        assertEquals(AddSign.None, rowAddSign("find:e1", setOf("find:e1"), offersAdd = false))
        assertEquals(AddSign.None, rowAddSign("t-42", emptySet(), offersAdd = false))
        assertEquals(AddSign.None, rowAddSign("server:x:mf-1", emptySet(), offersAdd = false))
    }

    @Test
    fun rowsBesideAddButtonsKeepTheirSpace() {
        // In a list offering the button, a library song leaves the space
        // empty so every length sits in one column.
        assertTrue(rowKeepsAddSpace(AddSign.None, librarySpace = true))
        assertFalse(rowKeepsAddSpace(AddSign.Button, librarySpace = true))
        // Elsewhere nothing is kept.
        assertFalse(rowKeepsAddSpace(AddSign.None, librarySpace = false))
        assertFalse(rowKeepsAddSpace(AddSign.Mark, librarySpace = false))
    }

    // How a row for this song ends in a list, as the row draws it.
    private fun end(trackId: String, offersAdd: Boolean, librarySpace: Boolean = offersAdd, hasTrailing: Boolean = false) =
        rowEnd(rowAddSign(trackId, setOf("find:e1"), offersAdd), librarySpace, hasTrailing)

    @Test
    fun aFindThatBecameALibrarySongEndsLikeTheListsLibrarySongs() {
        // Once turned, the row is drawn for the library song "t-42".
        // Search: the library results end at their length, and so does a
        // find among the songs not in the library once it has turned, while
        // the finds beside it keep their button.
        assertEquals(end("t-42", offersAdd = false), end("t-42", offersAdd = true, librarySpace = false))
        assertEquals(RowEnd.Trailing, end("t-42", offersAdd = true, librarySpace = false))
        assertEquals(RowEnd.AddButton, end("find:e2", offersAdd = true, librarySpace = false))
        // The find itself keeps its button through the check.
        assertEquals(RowEnd.AddButton, end("find:e1", offersAdd = true, librarySpace = false))
        // A list mixing library songs with finds, like an artist's top songs:
        // every library song keeps the space, the turned one too.
        assertEquals(RowEnd.AddSpace, end("t-42", offersAdd = true))
        // A list with its own end, like a playlist's drag handle, gives the
        // turned row that end, as it does its library songs.
        assertEquals(RowEnd.Trailing, end("t-42", offersAdd = false, hasTrailing = true))
        assertEquals(RowEnd.Trailing, end("t-42", offersAdd = true, hasTrailing = true))
    }

    @Test
    fun theMarkScalesWithTheArtworkWithinItsBounds() {
        // About 19dp on a 150dp card.
        assertEquals(18.75f, notInLibraryMarkSize(150.dp).value, 0.01f)
        assertEquals(15f, notInLibraryMarkSize(120.dp).value, 0.01f)
        // Never too small to read on a row or the mini player.
        assertEquals(12f, notInLibraryMarkSize(44.dp).value, 0.01f)
        assertEquals(12f, notInLibraryMarkSize(36.dp).value, 0.01f)
        // Nor large on a big cover.
        assertEquals(20f, notInLibraryMarkSize(240.dp).value, 0.01f)
        assertEquals(20f, notInLibraryMarkSize(400.dp).value, 0.01f)
    }

    @Test
    fun onASquareCoverTheMarkSitsJustInFromTheCorner() {
        assertEquals(6f, notInLibraryMarkInset(150.dp, 18.75.dp, round = false).value, 0.01f)
        assertEquals(3f, notInLibraryMarkInset(44.dp, 12.dp, round = false).value, 0.01f)
    }

    @Test
    fun onARoundPictureTheWholeMarkStaysInsideTheCircle() {
        for (art in listOf(36.dp, 48.dp, 96.dp, 160.dp)) {
            val mark = notInLibraryMarkSize(art)
            val inset = notInLibraryMarkInset(art, mark, round = true)
            val radius = art.value / 2
            val centre = art.value - inset.value - mark.value / 2
            val fromMiddle = sqrt(2f) * (centre - radius)
            assertTrue("$art: the mark crosses the edge", fromMiddle + mark.value / 2 <= radius)
            assertTrue("$art: the mark is in the lower right", centre > radius)
            assertTrue(inset > 0.dp)
        }
    }

    @Test
    fun talkBackHearsWhatIsNotInTheLibrary() {
        assertNull(artworkDescription(null, outside = false))
        assertEquals("Not in your library", artworkDescription(null, outside = true))
        assertEquals("Cover, not in your library", artworkDescription("Cover", outside = true))
        assertEquals("Cover", artworkDescription("Cover", outside = false))
    }

    @Test
    fun anAlbumPageSaysHowMuchIsInTheLibrary() {
        // None of it: the add button beside the note already says so.
        assertNull(albumLibraryNote(7, 7))
        assertEquals("3 of 7 in your library", albumLibraryNote(7, 4))
        assertNull(albumLibraryNote(7, 0))
    }
}

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
    fun aRowWithTheDownloadButtonSaysItOnce() {
        assertTrue(rowMarksArtwork("find:e1", emptySet(), offersDownload = false))
        assertFalse(rowMarksArtwork("find:e1", emptySet(), offersDownload = true))
        assertFalse(rowMarksArtwork("t-42", emptySet(), offersDownload = false))
        assertFalse(rowMarksArtwork("find:e1", setOf("find:e1"), offersDownload = false))
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
        assertEquals("Not in your library", albumLibraryNote(7, 7))
        assertEquals("3 of 7 in your library", albumLibraryNote(7, 4))
        assertNull(albumLibraryNote(7, 0))
    }
}

package app.winters.octo.ui.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaylistsLeadTest {
    // The lines that led the list are now one plus on the header.
    @Test
    fun thePlusMakesAPlaylistAFolderALiveListOrImportsOne() {
        assertEquals(listOf("New playlist", "New folder", "New live list", "Import a playlist file"), PlaylistsAdd.entries.map { it.label })
        assertEquals("M3U or M3U8", PlaylistsAdd.Import.detail)
    }

    @Test
    fun likedSongsIsNotOnThePlaylistsPage() {
        // Hearted songs live on the Favorites page, so the plus has no
        // Liked songs line of its own.
        assertFalse(PlaylistsAdd.entries.any { "liked" in it.label.lowercase() })
    }

    @Test
    fun theListHeaderSaysItsSizeLengthAndMore() {
        assertEquals(listOf("12 songs", "45 min", "3 of 12 downloaded"), listHeaderFacts(12, 45 * 60_000L, listOf("3 of 12 downloaded")))
        // An empty list has no length to give.
        assertEquals(listOf("0 songs"), listHeaderFacts(0, 0, emptyList()))
        // Before the count is known, only what else the page says shows.
        assertEquals(listOf("With “live”"), listHeaderFacts(null, 0, listOf("With “live”")))
    }

    @Test
    fun keepDownloadedSaysHowFarItHasGotUntilItIsDone() {
        assertEquals("3 of 12 downloaded", keepingNote(serverOnly = 12, done = 3))
        assertEquals(null, keepingNote(serverOnly = 12, done = 12))
        assertEquals(null, keepingNote(serverOnly = 0, done = 0))
        assertEquals("All 12 downloaded", keepDetail(checked = true, serverOnly = 12, done = 12))
        assertEquals(KEEP_DOWNLOADED_OFF, keepDetail(checked = false, serverOnly = 12, done = 0))
    }
}

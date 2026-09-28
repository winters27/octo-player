package app.winters.octo.ui.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaylistsLeadTest {
    @Test
    fun makingAndImportingComeFirst() {
        assertEquals(listOf(PlaylistsLead.New, PlaylistsLead.NewLive, PlaylistsLead.Import), playlistsLead(importNote = false))
    }

    @Test
    fun theImportNoteFollowsWhileThereIsOne() {
        assertEquals(
            listOf(PlaylistsLead.New, PlaylistsLead.NewLive, PlaylistsLead.Import, PlaylistsLead.ImportNote),
            playlistsLead(importNote = true),
        )
    }

    @Test
    fun likedSongsIsNotOnThePlaylistsPage() {
        // Hearted songs live on the Favourites page, so the playlists list
        // has no Liked songs line of its own.
        val keys = PlaylistsLead.entries.map { it.key }
        assertFalse(keys.any { "liked" in it.lowercase() })
        assertEquals(keys.size, keys.toSet().size)
    }
}

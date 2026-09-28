package app.winters.octo.ui.playlist

import app.winters.octo.playlists.ImportReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistAddsTest {
    @Test
    fun songsNotOnThePlaylistGoStraightIn() {
        val plan = planAdd(listOf("a", "b"), onPlaylist = setOf("x"))
        assertEquals(listOf("a", "b"), plan.fresh)
        assertFalse(plan.asks)
    }

    @Test
    fun eachSongGoesInOnceInTheOrderGiven() {
        val plan = planAdd(listOf("b", "a", "b"), onPlaylist = emptySet())
        assertEquals(listOf("b", "a"), plan.songs)
    }

    @Test
    fun oneSongOnThePlaylistAlreadyAsksToAddItAgain() {
        val plan = planAdd(listOf("a"), onPlaylist = setOf("a"))
        assertTrue(plan.asks)
        assertFalse(plan.offersNewOnly)
        assertEquals("Already in Road trip. Add again?", addAgainQuestion("Road trip", plan))
    }

    @Test
    fun someAlreadyOnItOfferAllOrOnlyTheNewOnes() {
        val plan = planAdd(listOf("a", "b", "c"), onPlaylist = setOf("b", "c"))
        assertEquals(listOf("a"), plan.fresh)
        assertEquals(listOf("b", "c"), plan.repeats)
        assertTrue(plan.offersNewOnly)
        assertEquals("2 of these songs are already in Road trip.", addAgainQuestion("Road trip", plan))
        assertEquals("1 of these songs is already in Road trip.", addAgainQuestion("Road trip", planAdd(listOf("a", "b"), setOf("b"))))
    }

    @Test
    fun allAlreadyOnItAsksToAddThemAgain() {
        val plan = planAdd(listOf("a", "b"), onPlaylist = setOf("a", "b"))
        assertFalse(plan.offersNewOnly)
        assertEquals("These songs are already in Road trip. Add them again?", addAgainQuestion("Road trip", plan))
    }

    @Test
    fun theMessageCountsTheSongs() {
        assertEquals("Added to Road trip", addedMessage("Road trip", 1))
        assertEquals("Added 3 songs to Road trip", addedMessage("Road trip", 3))
    }

    @Test
    fun theAddButtonSaysWhatItAdds() {
        assertEquals("Add", addAgainChoice(planAdd(listOf("a"), setOf("a"))))
        assertEquals("Add again", addAgainChoice(planAdd(listOf("a", "b"), setOf("a", "b"))))
        assertEquals("Add all", addAgainChoice(planAdd(listOf("a", "b"), setOf("a"))))
    }

    @Test
    fun theLastPlaylistsAddedToAreKeptNewestFirst() {
        assertEquals(listOf("p1"), recentPlaylists(emptyList(), "p1"))
        assertEquals(listOf("p2", "p1"), recentPlaylists(listOf("p1"), "p2"))
        // Using one again moves it to the front rather than listing it twice.
        assertEquals(listOf("p1", "p2"), recentPlaylists(listOf("p2", "p1"), "p1"))
        // Only three are kept.
        assertEquals(listOf("p4", "p3", "p2"), recentPlaylists(listOf("p3", "p2", "p1"), "p4"))
    }

    @Test
    fun theLastPlaylistsUsedComeFirst() {
        val all = listOf("a", "b", "c", "d")
        assertEquals(listOf("c", "a", "b", "d"), recentFirst(all, listOf("c", "a"), { it }))
        // One remembered but gone is simply not there.
        assertEquals(listOf("b", "a", "c", "d"), recentFirst(all, listOf("gone", "b"), { it }))
    }

    @Test
    fun aCopyIsNamedAfterItsPlaylist() {
        assertEquals("Late night (copy)", playlistCopyName(" Late night "))
        assertEquals("Made a copy: Late night (copy)", copiedMessage("Late night (copy)"))
    }

    @Test
    fun anImportSaysHowManySongsWereFound() {
        assertEquals("Imported 18 of 20 songs into \"Road trip\"", importSummary(ImportReport("Road trip", 18, 20, listOf("a", "b"))))
        assertEquals("Imported 1 of 1 song into \"Road trip\"", importSummary(ImportReport("Road trip", 1, 1, emptyList())))
        assertEquals("None of the 4 songs in \"Road trip\" are in your library.", importSummary(ImportReport("Road trip", 0, 4, emptyList())))
        assertEquals("\"Road trip\" has no songs in it.", importSummary(ImportReport("Road trip", 0, 0, emptyList())))
    }
}

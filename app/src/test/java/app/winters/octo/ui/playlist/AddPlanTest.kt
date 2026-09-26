package app.winters.octo.ui.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddPlanTest {
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
    fun holdingsAreGroupedByPlaylist() {
        assertEquals(
            mapOf("p1" to setOf("a", "b"), "p2" to setOf("a")),
            holdingsByPlaylist(listOf("p1" to "a", "p2" to "a", "p1" to "b")),
        )
    }

    @Test
    fun theMessageCountsTheSongs() {
        assertEquals("Added to Road trip", addedMessage("Road trip", 1))
        assertEquals("Added 3 songs to Road trip", addedMessage("Road trip", 3))
    }
}

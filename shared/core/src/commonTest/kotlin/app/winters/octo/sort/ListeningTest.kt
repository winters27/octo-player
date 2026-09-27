package app.winters.octo.sort

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningTest {
    // Base order a, b, c, d, e: the order SQL gave, by name.
    private val base = listOf("a", "b", "c", "d", "e")
    private val heard = mapOf(
        "b" to Listening(plays = 3, lastPlayedAt = 100),
        "d" to Listening(plays = 7, lastPlayedAt = 50),
        "e" to Listening(plays = 3, lastPlayedAt = 300),
    )

    private fun order(mostPlayed: Boolean, descending: Boolean) =
        byListening(base, { it }, heard, mostPlayed, descending)

    @Test
    fun mostPlayedFirstThenTheRestByName() {
        // d has the most plays; b and e tie on plays, e was played later.
        assertEquals(listOf("d", "e", "b", "a", "c"), order(mostPlayed = true, descending = true))
    }

    @Test
    fun leastPlayedFirstPutsTheUnplayedOnTop() {
        assertEquals(listOf("a", "c", "b", "e", "d"), order(mostPlayed = true, descending = false))
    }

    @Test
    fun recentlyPlayedGoesByTheLastPlay() {
        assertEquals(listOf("e", "b", "d", "a", "c"), order(mostPlayed = false, descending = true))
        assertEquals(listOf("a", "c", "d", "b", "e"), order(mostPlayed = false, descending = false))
    }

    @Test
    fun fullTiesKeepTheBaseOrder() {
        val same = Listening(plays = 2, lastPlayedAt = 10)
        val listening = mapOf("c" to same, "a" to same, "e" to same)
        assertEquals(listOf("a", "c", "e", "b", "d"), byListening(base, { it }, listening, mostPlayed = true, descending = true))
        assertEquals(listOf("b", "d", "a", "c", "e"), byListening(base, { it }, listening, mostPlayed = true, descending = false))
    }

    @Test
    fun anAlbumSeenOnlyOnTheServerCountsForRecentButNotForMost() {
        val listening = mapOf("b" to Listening(plays = 0, lastPlayedAt = 500))
        assertEquals(listOf("b", "a", "c", "d", "e"), byListening(base, { it }, listening, mostPlayed = false, descending = true))
        assertEquals(base, byListening(base, { it }, listening, mostPlayed = true, descending = true))
    }
}

package app.winters.octo.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpNextTest {
    // Plays the indexes in the given order, then stops.
    private fun inOrder(order: List<Int>): (Int) -> Int = { i ->
        order.getOrNull(order.indexOf(i) + 1) ?: C.INDEX_UNSET
    }

    private fun straight(count: Int) = inOrder(List(count) { it })

    @Test
    fun startsAtTheCurrentSong() {
        val slots = upNextOrder(listOf("a", "b", "c", "d"), current = 1, next = straight(4))
        assertEquals(listOf(1, 2, 3), slots.map { it.index })
    }

    @Test
    fun theSameSongTwiceGetsTwoKeys() {
        val slots = upNextOrder(listOf("a", "b", "a", "a"), current = 0, next = straight(4))
        assertEquals(listOf("a#0", "b#0", "a#1", "a#2"), slots.map { it.key })
    }

    @Test
    fun keysCountInQueueOrderNotPlayOrder() {
        // Shuffled so the later copy plays first: it keeps its own key.
        val slots = upNextOrder(listOf("a", "b", "a"), current = 2, next = inOrder(listOf(2, 0, 1)))
        assertEquals(listOf("a#1", "a#0", "b#0"), slots.map { it.key })
    }

    @Test
    fun followsTheShuffleOrder() {
        val order = listOf(3, 0, 4, 1, 2)
        val slots = upNextOrder(listOf("a", "b", "c", "d", "e"), current = 0, next = inOrder(order))
        assertEquals(listOf(0, 4, 1, 2), slots.map { it.index })
    }

    @Test
    fun lastSongHasNothingAfterIt() {
        val slots = upNextOrder(listOf("a", "b", "c"), current = 2, next = straight(3))
        assertEquals(listOf(QueueSlot(2, "c#0")), slots)
    }

    @Test
    fun emptyQueueIsEmpty() {
        assertTrue(upNextOrder(emptyList(), current = 0, next = straight(0)).isEmpty())
    }

    @Test
    fun entryIdsAreTheKeysWhenSongsHaveThem() {
        val slots = upNextOrder(listOf("a", "b", "a"), current = 0, entryIds = listOf("q:1", "q:2", "q:3"), next = straight(3))
        assertEquals(listOf("q:1", "q:2", "q:3"), slots.map { it.key })
    }

    @Test
    fun playedSongsComeOldestFirstAndStopBeforeTheCurrentOne() {
        val order = listOf(3, 0, 4, 1, 2)
        val previous: (Int) -> Int = { i -> order.getOrNull(order.indexOf(i) - 1) ?: C.INDEX_UNSET }
        val slots = playedOrder(listOf("a", "b", "c", "d", "e"), current = 4, previous = previous)
        assertEquals(listOf(3, 0), slots.map { it.index })
    }

    @Test
    fun theFirstSongHasNothingPlayedBeforeIt() {
        assertTrue(playedOrder(listOf("a", "b"), current = 0, previous = { C.INDEX_UNSET }).isEmpty())
    }

    @Test
    fun anOrderThatLoopsStillEnds() {
        val slots = upNextOrder(listOf("a", "b"), current = 0, next = { 1 - it })
        assertEquals(listOf(0, 1), slots.map { it.index })
    }
}

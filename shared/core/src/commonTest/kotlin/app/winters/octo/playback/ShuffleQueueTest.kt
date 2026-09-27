package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class ShuffleQueueTest {
    // The songs in the order they play, by name, for a queue in queue order.
    private fun played(queue: List<String>, order: IntArray) = order.map { queue[it] }

    // What a player does when songs are added: they go into the queue at
    // `at`, and into the play order at the rank the rules give.
    private fun add(
        queue: List<String>,
        order: IntArray,
        current: Int,
        at: Int,
        songs: List<String>,
        playNext: Boolean = false,
        autoplay: Set<String> = emptySet(),
    ): Pair<List<String>, IntArray> {
        val rank = shuffleRankFor(
            order, at, queue.size, current,
            playNext = playNext,
            beforeAutoplay = at < queue.size && queue[at] in autoplay,
        )
        val newQueue = queue.subList(0, at) + songs + queue.subList(at, queue.size)
        return newQueue to insertIntoShuffle(order, at, songs.size, rank)
    }

    // A shuffled queue of six, playing c, which is third in the play order.
    private val queue = listOf("a", "b", "c", "d", "e", "f")
    private val order = intArrayOf(4, 0, 2, 5, 1, 3)
    private val current = 2

    @Test
    fun playNextPlaysRightAfterTheCurrentSongInShuffledOrder() {
        val (q, o) = add(queue, order, current, at = current + 1, songs = listOf("x"), playNext = true)
        assertEquals(listOf("e", "a", "c", "x", "f", "b", "d"), played(q, o))
    }

    @Test
    fun playNextOfSeveralSongsKeepsTheirOwnOrder() {
        val (q, o) = add(queue, order, current, at = current + 1, songs = listOf("x", "y", "z"), playNext = true)
        assertEquals(listOf("e", "a", "c", "x", "y", "z", "f", "b", "d"), played(q, o))
    }

    @Test
    fun playNextWhileTheLastSongInTheQueueIsOnStillPlaysNext() {
        // f is last in the queue but plays fourth.
        val (q, o) = add(queue, order, current = 5, at = 6, songs = listOf("x"), playNext = true)
        assertEquals(listOf("e", "a", "c", "f", "x", "b", "d"), played(q, o))
    }

    @Test
    fun addToQueueGoesToTheEndOfTheShuffledOrder() {
        val (q, o) = add(queue, order, current, at = queue.size, songs = listOf("x", "y"))
        assertEquals(listOf("e", "a", "c", "f", "b", "d", "x", "y"), played(q, o))
    }

    @Test
    fun theLatestPlayNextPlaysFirst() {
        val (q1, o1) = add(queue, order, current, at = current + 1, songs = listOf("x"), playNext = true)
        val (q2, o2) = add(q1, o1, current, at = current + 1, songs = listOf("y"), playNext = true)
        assertEquals(listOf("e", "a", "c", "y", "x", "f", "b", "d"), played(q2, o2))
    }

    @Test
    fun ownSongsGoBeforeAutoplaySongsStillToCome() {
        // c is on and last; Autoplay added p and q after it.
        val q0 = listOf("a", "b", "c", "p", "q")
        val o0 = intArrayOf(1, 0, 2, 3, 4)
        val autoplay = setOf("p", "q")
        val flags = q0.map { it in autoplay }
        val at = ownSongsAt(q0.size, upcoming = listOf(3, 4), autoplay = flags)
        assertEquals(3, at)
        val (q, o) = add(q0, o0, current = 2, at = at, songs = listOf("x"), autoplay = autoplay)
        assertEquals(listOf("b", "a", "c", "x", "p", "q"), played(q, o))
    }

    @Test
    fun withNoAutoplaySongsAheadOwnSongsGoAtTheEnd() {
        val flags = listOf(false, true, false)
        // The Autoplay song already played; nothing of Autoplay's is to come.
        assertEquals(3, ownSongsAt(3, upcoming = listOf(2), autoplay = flags))
        // Songs put in the middle stay where they were asked for.
        assertEquals(1, ownSongsAt(1, upcoming = listOf(2), autoplay = flags))
    }

    @Test
    fun songsPutInTheMiddleFollowTheSongBeforeThem() {
        // Put in before d, at queue position 3, while a is on: they follow
        // c, the song before them in the queue, which plays third.
        val (q, o) = add(queue, order, current = 0, at = 3, songs = listOf("x"))
        assertEquals(listOf("e", "a", "c", "x", "f", "b", "d"), played(q, o))
    }

    @Test
    fun songsAddedAtTheStartOfTheQueueGoFirst() {
        val (q, o) = add(queue, order, current = 2, at = 0, songs = listOf("x"))
        assertEquals(listOf("x", "e", "a", "c", "f", "b", "d"), played(q, o))
    }

    @Test
    fun removingSongsKeepsTheRestInTheirPlayOrder() {
        // Take out b and c.
        val o = removeFromShuffle(order, 1, 3)
        val q = listOf("a", "d", "e", "f")
        assertEquals(listOf("e", "a", "f", "d"), played(q, o))
    }

    @Test
    fun movingSongsInTheQueueKeepsThePlayOrder() {
        // Move b and c to the end: a d e f b c.
        val o = moveInShuffle(order, 1, 3, newFrom = 4)
        val q = listOf("a", "d", "e", "f", "b", "c")
        assertEquals(played(queue, order), played(q, o))
    }
}

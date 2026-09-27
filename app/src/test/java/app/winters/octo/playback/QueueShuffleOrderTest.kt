package app.winters.octo.playback

import androidx.media3.common.C
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class QueueShuffleOrderTest {
    // The play order of a shuffled queue of six.
    private val order = intArrayOf(4, 0, 2, 5, 1, 3)

    @Test
    fun theOrderWalksForwardAndBack() {
        val shuffle = QueueShuffleOrder(order)
        assertEquals(4, shuffle.firstIndex)
        assertEquals(3, shuffle.lastIndex)
        assertEquals(5, shuffle.getNextIndex(2))
        assertEquals(0, shuffle.getPreviousIndex(2))
        assertEquals(C.INDEX_UNSET, shuffle.getNextIndex(3))
        assertEquals(C.INDEX_UNSET, shuffle.getPreviousIndex(4))
    }

    @Test
    fun aNewQueueIsShuffledAtRandomButAnAddedSongIsNot() {
        val fresh = QueueShuffleOrder(IntArray(0), Random(7)).cloneAndInsert(0, 6) as QueueShuffleOrder
        assertEquals((0 until 6).toList(), fresh.playOrder.sorted())
        // Added at the end by the player itself: last, not at random.
        val added = fresh.cloneAndInsert(6, 1) as QueueShuffleOrder
        assertEquals(6, added.lastIndex)
        assertArrayEquals(fresh.playOrder, added.playOrder.copyOf(6))
    }

    @Test
    fun theOrderCarriesOverToTheOtherDeck() {
        // A crossfade copies the play order to the other deck, which must
        // keep adding songs by the same rules.
        val copy = QueueShuffleOrder(QueueShuffleOrder(order).playOrder)
        assertArrayEquals(order, copy.playOrder)
        val added = copy.cloneAndInsert(6, 1) as QueueShuffleOrder
        assertEquals(6, added.lastIndex)
    }

    @Test
    fun clearingEmptiesTheOrder() {
        assertEquals(0, QueueShuffleOrder(order).cloneAndClear().length)
    }
}

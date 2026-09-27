package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueUndoTest {
    @Test
    fun anUntouchedQueueHasTheSongWhereItWentIn() {
        assertEquals(1, findInserted(listOf("now", "new", "later"), listOf("new"), insertedAt = 1))
    }

    @Test
    fun aSongMovedSinceIsFoundWhereItIsNow() {
        // Something else went in ahead of it.
        assertEquals(2, findInserted(listOf("now", "other", "new", "later"), listOf("new"), insertedAt = 1))
    }

    @Test
    fun theSameSongTwiceTakesTheCopyNearestWhereItWentIn() {
        assertEquals(1, findInserted(listOf("now", "new", "a", "b", "c", "new"), listOf("new"), insertedAt = 1))
        assertEquals(5, findInserted(listOf("new", "a", "b", "c", "now", "new"), listOf("new"), insertedAt = 5))
    }

    @Test
    fun songsPutInTogetherAreFoundTogether() {
        assertEquals(2, findInserted(listOf("now", "x", "a", "b", "later"), listOf("a", "b"), insertedAt = 1))
        // Split apart, they are not the run that went in.
        assertNull(findInserted(listOf("now", "a", "x", "b"), listOf("a", "b"), insertedAt = 1))
    }

    @Test
    fun aSongGoneFromTheQueueIsNotFound() {
        assertNull(findInserted(listOf("now", "later"), listOf("new"), insertedAt = 1))
        assertNull(findInserted(emptyList(), listOf("new"), insertedAt = 0))
        assertNull(findInserted(listOf("now"), emptyList(), insertedAt = 0))
    }
}

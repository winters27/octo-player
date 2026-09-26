package app.winters.octo.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueRestoreTest {
    @Test
    fun picksUpAtTheSavedSongAndPlace() {
        assertEquals(2 to 17_192L, restorePoint(listOf(true, true, true, true), 2, 17_192))
    }

    @Test
    fun songsGoneBeforeItMoveItForward() {
        // The first song is gone, so the saved third song is now second.
        assertEquals(1 to 17_192L, restorePoint(listOf(false, true, true), 2, 17_192))
    }

    @Test
    fun whenTheSavedSongIsGoneItStartsFromTheTop() {
        assertEquals(0 to 0L, restorePoint(listOf(true, false, true), 1, 17_192))
    }

    @Test
    fun aSavedPlaceOutsideTheQueueStartsFromTheTop() {
        assertEquals(0 to 0L, restorePoint(listOf(true, true), 5, 17_192))
    }

    @Test
    fun theSavedShuffleLeavesOutSongsThatAreGone() {
        // Saved c a d b; b is gone, so c a d over the queue a c d.
        val order = restoredShuffle(listOf(2, 0, 3, 1), listOf(true, false, true, true))
        assertArrayEquals(intArrayOf(1, 0, 2), order)
    }

    @Test
    fun aShuffleThatDoesNotFitIsDropped() {
        assertNull(restoredShuffle(emptyList(), listOf(true, true)))
        assertNull(restoredShuffle(listOf(0, 0), listOf(true, true)))
    }
}

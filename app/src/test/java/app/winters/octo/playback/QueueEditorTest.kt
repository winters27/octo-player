package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueEditorTest {
    @Test
    fun twoQuickSwipesTakeOutTheTwoSongsSwiped() {
        // The same song twice, each with its own queue entry.
        val queue = queueKeys(listOf("a", "b", "a", "c"), listOf("q:0", "q:1", "q:2", "q:3")).toMutableList()
        // Both rows were drawn before either swipe took effect: the second
        // a and c, at 2 and 3 when drawn.
        listOf("q:2", "q:3").forEach { key -> queue.removeAt(queue.indexOf(key)) }
        assertEquals(listOf("q:0", "q:1"), queue)
    }

    @Test
    fun keysAreEntryIdsWhenSongsHaveThem() {
        assertEquals(listOf("q:7", "a#1"), queueKeys(listOf("a", "a"), listOf("q:7", null)))
    }
}

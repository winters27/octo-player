package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueEditorTest {
    // Takes out the positions as the player does: runs, last first.
    private fun remove(queue: List<String>, positions: Collection<Int>): List<String> {
        val out = queue.toMutableList()
        removalRuns(positions).forEach { run -> repeat(run.last - run.first + 1) { out.removeAt(run.first) } }
        return out
    }

    // Puts songs back as an undo does, run by run.
    private fun putBack(before: List<String>, now: List<String>): List<String>? {
        val runs = putBackRuns(before, now) ?: return null
        val out = now.toMutableList()
        runs.forEach { (at, old) -> out.addAll(at, old.map(before::get)) }
        return out
    }

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

    @Test
    fun runsComeOffFromTheBack() {
        assertEquals(listOf(7..8, 5..5, 1..2), removalRuns(listOf(8, 1, 5, 2, 7)))
        assertEquals(listOf("a", "d", "e", "g"), remove(listOf("a", "b", "c", "d", "e", "f", "g"), listOf(1, 2, 5)))
    }

    @Test
    fun undoPutsRemovedSongsBackWhereTheyWere() {
        val before = listOf("a", "b", "c", "d", "e")
        val now = remove(before, listOf(1, 3))
        assertEquals(listOf("a", "c", "e"), now)
        assertEquals(before, putBack(before, now))
    }

    @Test
    fun undoOfClearKeepsTheCurrentSongInPlace() {
        val before = listOf("a", "b", "c", "d")
        // c is on; clear takes out the rest.
        val now = remove(before, listOf(0, 1, 3))
        assertEquals(listOf("c"), now)
        assertEquals(listOf(0 to listOf(0, 1), 3 to listOf(3)), putBackRuns(before, now))
        assertEquals(before, putBack(before, now))
    }

    @Test
    fun undoDoesNothingOnceTheQueueChangedSomeOtherWay() {
        val before = listOf("a", "b", "c", "d")
        assertNull(putBackRuns(before, listOf("c", "x")))
        assertNull(putBackRuns(before, listOf("c", "a")))
        // Nothing was taken out.
        assertNull(putBackRuns(before, before))
    }

    @Test
    fun songsAboutToBeDeletedLeaveTheQueueEveryTimeTheyAreInIt() {
        val queue = listOf("a", "b", "c", "b", "d")
        assertEquals(listOf(1, 3), queuePositionsOf(queue, setOf("b")))
        assertEquals(listOf(0, 1, 3, 4), queuePositionsOf(queue, setOf("a", "b", "d")))
        assertEquals(emptyList<Int>(), queuePositionsOf(queue, setOf("z")))
        // Taken out as runs, the rest keep their order.
        assertEquals(listOf("a", "c", "d"), remove(queue, queuePositionsOf(queue, setOf("b"))))
        assertEquals(listOf("c"), remove(queue, queuePositionsOf(queue, setOf("a", "b", "d"))))
    }
}

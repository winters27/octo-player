package app.winters.octo.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RailLetterTest {
    // A headed list: A's heading at 0 and two rows, B's heading at 3 and
    // one row, D's heading at 5.
    private val stops = railStops(listOf(LetterRun('A', 0, 2), LetterRun('B', 2, 1), LetterRun('D', 3, 4)), headed = true)

    @Test
    fun theLetterLitIsTheRunTheTopRowIsIn() {
        assertEquals(listOf(0, 3, 5), stops.map { it.index })
        assertEquals('A', railLetterAt(stops, 0))
        assertEquals('A', railLetterAt(stops, 2))
        assertEquals('B', railLetterAt(stops, 3))
        assertEquals('B', railLetterAt(stops, 4))
        assertEquals('D', railLetterAt(stops, 5))
        assertEquals('D', railLetterAt(stops, 9))
    }

    // Rows above the list's own (a header) light no letter.
    @Test
    fun noLetterAboveTheFirstStop() {
        val below = railStops(listOf(LetterRun('A', 0, 2), LetterRun('B', 2, 1)), headed = true, before = 2)
        assertNull(railLetterAt(below, 0))
        assertNull(railLetterAt(below, 1))
        assertEquals('A', railLetterAt(below, 2))
        assertNull(railLetterAt(emptyList(), 4))
    }

    // A letter that comes back (names in other scripts under "#" at the
    // end) keeps the last letter before it lit, since the rail has no stop
    // of its own for it.
    @Test
    fun aLetterThatComesBackKeepsTheOneBefore() {
        val back = railStops(listOf(LetterRun('#', 0, 1), LetterRun('Z', 1, 1), LetterRun('#', 2, 1)), headed = true)
        assertEquals('Z', railLetterAt(back, 4))
    }
}

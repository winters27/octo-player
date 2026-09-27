package app.winters.octo.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class LettersTest {
    @Test
    fun runsFollowTheListInOrder() {
        assertEquals(
            listOf(LetterRun('#', 0, 1), LetterRun('A', 1, 2), LetterRun('B', 3, 1)),
            letterRuns(listOf("50 cent", "abba", "adele", "beyonce")),
        )
    }

    @Test
    fun aLetterThatComesBackGetsAnotherRun() {
        // Numbers first and another script last both file under "#".
        val runs = letterRuns(listOf("1999", "zz top", "ωmega"))
        assertEquals(listOf(LetterRun('#', 0, 1), LetterRun('Z', 1, 1), LetterRun('#', 2, 1)), runs)
    }

    @Test
    fun descendingNamesRunBackwards() {
        assertEquals(listOf('C', 'B', 'A'), letterRuns(listOf("cat", "bee", "ant", "ape")).map { it.letter })
    }

    @Test
    fun nothingGivesNoRuns() {
        assertEquals(emptyList<LetterRun>(), letterRuns(emptyList()))
    }

    @Test
    fun railStopsCountTheHeadingRows() {
        val runs = letterRuns(listOf("1999", "abba", "adele", "beyonce", "ωmega"))
        // Headed: each run's heading comes before its rows.
        assertEquals(
            listOf(RailStop('#', 0), RailStop('A', 2), RailStop('B', 5)),
            railStops(runs, headed = true),
        )
        // A grid with no headings, after one other row.
        assertEquals(
            listOf(RailStop('#', 1), RailStop('A', 2), RailStop('B', 4)),
            railStops(runs, headed = false, before = 1),
        )
    }
}

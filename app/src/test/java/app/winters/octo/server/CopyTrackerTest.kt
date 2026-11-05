package app.winters.octo.server

import app.winters.octo.catalog.StageProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

// How far a copy is has one writer, which drops what is late or another's.
class CopyTrackerTest {
    private val tracker = CopyTracker()
    private val seen = mutableListOf<CopyRunState?>()

    init {
        tracker.seen = { seen += it }
    }

    // A first copy, planned whole, at pass 1.
    private fun firstCopy(): Pair<Long, Int> {
        val token = tracker.begin("acc", "Home", arriving = false, whole = true)
        val pass = tracker.nextPass(token, arriving = true, whole = true)!!
        tracker.planned(token, pass, arriving = true, whole = true)
        return token to pass
    }

    @Test
    fun lateStepsAndOtherPassesAreDropped() {
        val (token, pass) = firstCopy()
        val report = tracker.report(token, pass)
        report.at(CopyStep.Read, 500, null)
        report.at(CopyStep.Build, 10, 100)
        // A Read after Build, from the album list's coroutine.
        report.at(CopyStep.Read, 2_000, 2_000)
        assertEquals(CopyStep.Build, tracker.run.value!!.step)
        report.swapped()
        report.at(CopyStep.Add, 50, 100)
        assertEquals(CopyStep.Settle, tracker.run.value!!.step)
        // An update for pass 1 during pass 2.
        val second = tracker.nextPass(token, arriving = false, whole = true)!!
        report.at(CopyStep.Read, 5_000, null)
        assertEquals(second, tracker.run.value!!.pass)
        assertEquals(CopyStep.Connect, tracker.run.value!!.step)
        assertEquals(0L, tracker.run.value!!.done)
    }

    @Test
    fun countsAndTheFractionOnlyGoForwardWithinAStep() {
        val (token, pass) = firstCopy()
        val report = tracker.report(token, pass)
        report.at(CopyStep.Read, 1_000, 10_000)
        // The album list reports with the count it read before the last page.
        report.at(CopyStep.Read, 500, 10_000)
        val run = tracker.run.value!!
        assertEquals(1_000L, run.done)
        assertEquals(copyFraction(CopyStep.Read, 1_000, 10_000)!!, run.fraction!!, 1e-6f)
        // The total, once known, stays when a page reports without it.
        report.at(CopyStep.Read, 1_500, null)
        assertEquals(10_000L, tracker.run.value!!.of)
    }

    @Test
    fun aReplacedRunsLateEndLeavesItsReplacementRunning() {
        val first = tracker.begin("acc", "Home", arriving = false, whole = true)
        val second = tracker.begin("acc", "Home", arriving = false, whole = true)
        tracker.end(first)
        assertEquals(second, tracker.run.value!!.token)
        tracker.end(second)
        assertNull(tracker.run.value)
    }

    @Test
    fun aJobsPassesNeverShowNoRunBetweenThem() {
        val token = tracker.begin("acc", "Home", arriving = false, whole = true)
        val one = tracker.nextPass(token, arriving = false, whole = false)!!
        tracker.planned(token, one, arriving = false, whole = false)
        tracker.passDone(token, one)
        tracker.report(token, one).at(CopyStep.Read, 1, null)
        val two = tracker.nextPass(token, arriving = false, whole = true)!!
        assertEquals(one + 1, two)
        assertTrue(seen.none { it == null })
        assertNull(tracker.run.value!!.fraction)
        tracker.end(token)
        assertNull(seen.last())
    }

    @Test
    fun aStepsOnlyPassShowsOnlyTheStepsAfterACopy() {
        val token = tracker.begin("acc", "Home", arriving = false, whole = true)
        val pass = tracker.nextPass(token, arriving = false, whole = true)!!
        tracker.planned(token, pass, arriving = false, whole = false)
        assertEquals(CopyStep.Settle, tracker.run.value!!.step)
        assertFalse(tracker.run.value!!.whole)
        tracker.report(token, pass).at(CopyStep.Read, 10, null)
        assertEquals(CopyStep.Settle, tracker.run.value!!.step)
    }

    @Test
    fun aFirstCopySaysItsMusicIsHereWithTheBuiltCounts() {
        val (token, pass) = firstCopy()
        val report = tracker.report(token, pass)
        report.staged(1_204, 96)
        report.swapped()
        tracker.passDone(token, pass)
        assertEquals(CopyOutcome.Arrived(token, "acc", "Home", 1_204, 96, null), tracker.outcome.value)
    }

    @Test
    fun aCopyAgainSaysNothingWhenItEnds() {
        val token = tracker.begin("acc", "Home", arriving = false, whole = true)
        val pass = tracker.nextPass(token, arriving = false, whole = true)!!
        tracker.planned(token, pass, arriving = false, whole = true)
        tracker.report(token, pass).swapped()
        tracker.passDone(token, pass)
        tracker.passFailed(token, pass, "Couldn't reach the server.")
        assertNull(tracker.outcome.value)
    }

    @Test
    fun aFailureAfterTheSwapNeverSaysTheCopyFailed() {
        val (token, pass) = firstCopy()
        val report = tracker.report(token, pass)
        report.staged(10, 2)
        report.swapped()
        tracker.passFailed(token, pass, "Couldn't reach the server.")
        val outcome = tracker.outcome.value as CopyOutcome.Arrived
        assertEquals("Couldn't reach the server.", outcome.stepsProblem)
    }

    @Test
    fun aFailureBeforeTheSwapSaysWhereItStopped() {
        val (token, pass) = firstCopy()
        tracker.report(token, pass).at(CopyStep.Read, 48_210, 200_000)
        tracker.passFailed(token, pass, "Couldn't reach the server.")
        assertEquals(CopyOutcome.Failed(token, "acc", "Home", "Couldn't reach the server.", CopyStep.Read, 48_210), tracker.outcome.value)
    }

    @Test
    fun aDoneCardPutAwayWhileTheStepsRunNeverComesBack() {
        val (token, pass) = firstCopy()
        tracker.report(token, pass).swapped()
        tracker.dismiss(token)
        tracker.passDone(token, pass)
        assertNull(tracker.outcome.value)
        tracker.passFailed(token, pass, "Couldn't reach the server.")
        assertNull(tracker.outcome.value)
    }

    @Test
    fun aFirstCopyStartingClearsTheLastOnesCard() {
        val (token, pass) = firstCopy()
        tracker.passFailed(token, pass, "Couldn't reach the server.")
        assertNotNull(tracker.outcome.value)
        tracker.end(token)
        val next = tracker.begin("acc", "Home", arriving = false, whole = true)
        tracker.nextPass(next, arriving = true, whole = true)
        assertNull(tracker.outcome.value)
    }

    @Test
    fun onlyTheSeenRunsDoneCardIsCleared() {
        val (token, pass) = firstCopy()
        tracker.report(token, pass).swapped()
        tracker.passDone(token, pass)
        tracker.clearArrived(token + 1)
        assertNotNull(tracker.outcome.value)
        tracker.clearArrived(token)
        assertNull(tracker.outcome.value)
    }

    // Nothing in the copy's state can grow with the library.
    @Test
    fun theStateHoldsOnlyNumbersFlagsStepsAndWords() {
        val allowed = setOf(
            Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            java.lang.Long::class.java, java.lang.Float::class.java, String::class.java, CopyStep::class.java,
        )
        for (type in listOf(CopyRunState::class.java, CopyOutcome.Arrived::class.java, CopyOutcome.Failed::class.java, StageProgress::class.java)) {
            for (field in type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }) {
                assertTrue("${type.simpleName}.${field.name} is ${field.type}", field.type in allowed)
            }
        }
    }
}

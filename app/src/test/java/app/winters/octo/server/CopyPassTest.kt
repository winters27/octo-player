package app.winters.octo.server

import app.winters.octo.catalog.ApplyPending
import app.winters.octo.catalog.StageAborted
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// One copy of a sync job, as the tracker shows it from Connect to its end.
@RunWith(RobolectricTestRunner::class)
class CopyPassTest {
    private val tracker = CopyTracker()
    private val seen = mutableListOf<CopyRunState?>()

    init {
        tracker.seen = { seen += it }
    }

    private suspend fun pass(
        token: Long,
        arriving: Boolean = true,
        plan: Boolean? = true,
        run: suspend (Boolean, CopyReport) -> Unit = { _, report -> report.swapped() },
    ) = copyPass(tracker, token, arriving = { arriving }, guess = true, plan = { plan }, run = run)

    @Test
    fun aJobsSecondCopyStartsAgainAtConnectWithoutTheRunEnding() = runBlocking {
        val token = tracker.begin("acc", "Home", arriving = false, whole = true)
        pass(token, arriving = false, plan = false) { _, _ -> }
        pass(token, arriving = false) { _, report ->
            assertEquals(CopyStep.Connect, tracker.run.value!!.step)
            assertNull(tracker.run.value!!.fraction)
            assertEquals(2, tracker.run.value!!.pass)
            report.at(CopyStep.Read, 500, 1_000)
        }
        assertTrue(seen.none { it == null })
        tracker.end(token)
        assertNull(tracker.run.value)
    }

    @Test
    fun aFirstCopyWhoseSwitchStillFailsSaysSo() = runBlocking {
        val token = tracker.begin("acc", "Home", arriving = false, whole = true)
        assertNull(pass(token, plan = null))
        assertEquals(SWITCH_PROBLEM, (tracker.outcome.value as CopyOutcome.Failed).reason)
    }

    @Test
    fun aCopyAgainWhoseSwitchStillFailsLeavesItToSettings() = runBlocking {
        val token = tracker.begin("acc", "Home", arriving = false, whole = true)
        assertNull(pass(token, arriving = false, plan = null))
        assertNull(tracker.outcome.value)
    }

    @Test
    fun aStepFailingAfterTheSwapStillSaysTheMusicIsHere() = runBlocking {
        val token = tracker.begin("acc", "Home", arriving = false, whole = true)
        try {
            pass(token) { _, report ->
                report.staged(600, 30)
                report.swapped()
                error("a step failed")
            }
            fail("the step's failure is the job's")
        } catch (e: IllegalStateException) {
            // Passed on to the job, which shows it in Settings.
        }
        val outcome = tracker.outcome.value as CopyOutcome.Arrived
        assertEquals(600, outcome.songs)
        assertTrue(outcome.stepsProblem != null)
    }

    @Test
    fun aCopyTheMergeCouldNotSwapSaysTheMergesReason() = runBlocking {
        val token = tracker.begin("acc", "Home", arriving = false, whole = true)
        try {
            pass(token) { _, report ->
                report.at(CopyStep.Add, 10, 100)
                throw StageAborted("Not enough room.")
            }
            fail("the abort is the job's")
        } catch (e: StageAborted) {
            // Passed on.
        }
        val outcome = tracker.outcome.value as CopyOutcome.Failed
        assertEquals("Not enough room.", outcome.reason)
        assertEquals(CopyStep.Add, outcome.step)
    }

    // A first copy whose save stopped after its first group says why it
    // stopped, at its Save step; its resume is the next run's.
    @Test
    fun aFirstCopyWhoseSaveStoppedSaysWhyItStopped() = runBlocking {
        val token = tracker.begin("acc", "Home", arriving = false, whole = true)
        try {
            pass(token) { _, report ->
                report.at(CopyStep.Save, SAVE_PARTS / 4, SAVE_PARTS)
                throw ApplyPending("The phone is out of space.")
            }
            fail("the stop is the job's")
        } catch (e: ApplyPending) {
            // Passed on.
        }
        val outcome = tracker.outcome.value as CopyOutcome.Failed
        assertEquals("The phone is out of space.", outcome.reason)
        assertEquals(CopyStep.Save, outcome.step)
        // A resumed run takes the place of the stopped one, from where its
        // bar stopped, and its first write puts the old card away.
        val stopped = tracker.run.value!!.fraction!!
        val (resumed, _) = tracker.beginResume("acc", "Home", arriving = true, saved = 0.25f)
        assertEquals(stopped, tracker.run.value!!.fraction!!, 1e-6f)
        assertNull(tracker.outcome.value)
        assertTrue(resumed != token)
    }

    @Test
    fun aFirstCopyThatEndsSaysItsMusicIsHere() = runBlocking {
        val token = tracker.begin("acc", "Home", arriving = false, whole = true)
        assertEquals(true, pass(token))
        assertTrue(tracker.outcome.value is CopyOutcome.Arrived)
    }
}

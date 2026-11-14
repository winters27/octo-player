package app.winters.octo.desktop.server

import app.winters.octo.subsonic.ScanStatus
import org.junit.Assert.assertEquals
import org.junit.Test

// Following a server's scan, one answer at a time, counted rather than timed.
class ScanFollowTest {
    private val running = ScanStatus(scanning = true, count = 10)
    private val stopped = ScanStatus(scanning = false, count = 12)

    @Test
    fun aScanSeenRunningIsDoneOnTheFirstStop() {
        val follow = ScanFollow()
        assertEquals(ScanStep.Again(running, SCAN_POLL_MS), follow.next(running))
        assertEquals(ScanStep.Done(stopped), follow.next(stopped))
    }

    @Test
    fun anEarlyNotScanningCountsOnlyWhenSaidTwice() {
        val follow = ScanFollow()
        assertEquals(ScanStep.Again(null, SCAN_POLL_MS), follow.next(stopped))
        assertEquals(ScanStep.Done(stopped), follow.next(stopped))
    }

    @Test
    fun failedAsksWaitLongerEachTimeAndGiveUpAfterTheLimit() {
        val follow = ScanFollow(maxFailures = 6)
        val waits = (1..5).map { (follow.next(null) as ScanStep.Again).waitMs }
        assertEquals(listOf(4_000L, 8_000L, 16_000L, 30_000L, 30_000L), waits)
        assertEquals(ScanStep.GiveUp, follow.next(null))
    }

    @Test
    fun anAnswerStartsTheFailureCountAgain() {
        val follow = ScanFollow(maxFailures = 3)
        follow.next(null)
        follow.next(null)
        // Two failures, then an answer: the count starts over.
        assertEquals(ScanStep.Again(running, SCAN_POLL_MS), follow.next(running))
        assertEquals(ScanStep.Again(null, 4_000L), follow.next(null))
        assertEquals(ScanStep.Again(null, 8_000L), follow.next(null))
        assertEquals(ScanStep.GiveUp, follow.next(null))
    }
}

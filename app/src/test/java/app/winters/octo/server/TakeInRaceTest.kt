package app.winters.octo.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeInRaceTest {
    private val syncing = MutableStateFlow(false)

    @Test
    fun theTakeInsAnswerComesWhenNoCopyRuns() = runTest {
        val work = CompletableDeferred<Boolean>()
        val answer = async { unlessCopying(work, copying = false, syncing) }
        runCurrent()
        work.complete(true)
        assertEquals(true, answer.await())
    }

    @Test
    fun aCopyStartingWhileTheTakeInIsHeldEndsTheWaitAtOnce() = runTest {
        // The take-in is held, as when its writes wait on a copy's.
        val work = CompletableDeferred<Boolean>()
        val answer = async { unlessCopying(work, copying = false, syncing) }
        runCurrent()
        assertFalse(answer.isCompleted)
        syncing.value = true
        runCurrent()
        assertTrue(answer.isCompleted)
        assertNull(answer.await())
        // The take-in itself goes on.
        assertFalse(work.isCompleted)
    }

    @Test
    fun aCopyAlreadyRunningIsNeverWaitedOn() = runTest {
        val work = CompletableDeferred<Boolean>()
        assertNull(unlessCopying(work, copying = true, syncing))
        syncing.value = true
        assertNull(unlessCopying(work, copying = false, syncing))
    }
}

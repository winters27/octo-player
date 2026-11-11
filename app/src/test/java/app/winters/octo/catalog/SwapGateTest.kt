package app.winters.octo.catalog

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The merge lock on one thread, so every step between two coroutines is
// exact: a coroutine that has not entered after the others have all run as
// far as they can is waiting. The side alone is a save's group (`null`).
class SwapGateTest {
    private val gate = SwapGate()

    private fun run(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(5_000) { block() } }

    // Lets every other coroutine run until each waits.
    private suspend fun settle() = repeat(20) { yield() }

    // A hold that comes in, says so, and stays until let go.
    private inner class Holder(private val scope: CoroutineScope, private val kind: HoldKind?) {
        val inside = CompletableDeferred<Unit>()
        val leave = CompletableDeferred<Unit>()
        var ran = false
        lateinit var job: Job

        fun start(): Holder {
            job = scope.launch {
                val block: suspend () -> Unit = {
                    ran = true
                    inside.complete(Unit)
                    leave.await()
                }
                if (kind == null) gate.exclusive(block) else gate.shared(kind, block)
            }
            return this
        }

        val entered get() = inside.isCompleted

        suspend fun done() {
            leave.complete(Unit)
            job.join()
        }
    }

    private fun CoroutineScope.hold(kind: HoldKind?) = Holder(this, kind).start()

    @Test
    fun sharedHoldersOverlap() = run {
        val page = hold(HoldKind.Page)
        val walk = hold(HoldKind.Walk)
        val read = hold(HoldKind.Read)
        settle()
        assertTrue(page.entered && walk.entered && read.entered)
        listOf(page, walk, read).forEach { it.done() }
    }

    // With only short holds inside, a waiting group queues every new hold:
    // pages, walks and reads.
    @Test
    fun aGroupWaitingForShortHoldsQueuesEveryNewHold() = run {
        val page = hold(HoldKind.Page)
        settle()
        val swap = hold(null)
        settle()
        assertFalse("the group entered beside a sharer", swap.entered)
        val laterPage = hold(HoldKind.Page)
        val laterWalk = hold(HoldKind.Walk)
        val laterRead = hold(HoldKind.Read)
        settle()
        assertFalse("a page entered while the group waited for short holds", laterPage.entered)
        assertFalse("a walk entered while the group waited", laterWalk.entered)
        assertFalse("a read entered while the group waited for short holds", laterRead.entered)
        page.done()
        settle()
        assertTrue(swap.entered)
        assertFalse(laterPage.entered || laterWalk.entered || laterRead.entered)
        swap.done()
        settle()
        assertTrue(laterPage.entered && laterWalk.entered && laterRead.entered)
        listOf(laterPage, laterWalk, laterRead).forEach { it.done() }
    }

    // While a long hold (a walk) is inside, a waiting group still lets
    // short holds in, and queues new walks; once the walk leaves, it waits
    // for the short ones inside and queues new ones too.
    @Test
    fun aPageAskedWhileAGroupWaitsForAWalkEnters() = run {
        val walk = hold(HoldKind.Walk)
        settle()
        val swap = hold(null)
        settle()
        val page = hold(HoldKind.Page)
        val laterWalk = hold(HoldKind.Walk)
        settle()
        assertTrue("a page waited behind a group that waits for a walk", page.entered)
        assertFalse("a walk entered while the group waited", laterWalk.entered)
        walk.done()
        settle()
        assertFalse("the group entered beside the page", swap.entered)
        val pageAfter = hold(HoldKind.Page)
        settle()
        assertFalse("a page entered while the group waited for short holds", pageAfter.entered)
        page.done()
        settle()
        assertTrue(swap.entered)
        swap.done()
        settle()
        assertTrue(pageAfter.entered && laterWalk.entered)
        pageAfter.done()
        laterWalk.done()
    }

    @Test
    fun aReadEntersBesideAWalkWhileTheSwapWaits() = run {
        val walk = hold(HoldKind.Walk)
        settle()
        val swap = hold(null)
        settle()
        val read = hold(HoldKind.Read)
        settle()
        assertTrue("a read waited behind the waiting swap", read.entered)
        assertFalse(swap.entered)
        read.done()
        walk.done()
        settle()
        assertTrue(swap.entered)
        // While the swap runs, a read waits for it.
        val readDuring = hold(HoldKind.Read)
        settle()
        assertFalse(readDuring.entered)
        swap.done()
        settle()
        assertTrue(readDuring.entered)
        readDuring.done()
    }

    @Test
    fun aHolderStoppedMidHoldLetsGo() = run {
        val page = hold(HoldKind.Page)
        settle()
        val swap = hold(null)
        settle()
        assertFalse(swap.entered)
        page.job.cancel()
        page.job.join()
        settle()
        assertTrue(swap.entered)
        swap.done()
    }

    @Test
    fun aTurnedAwayWalkStoppedWhileWaitingLeavesTheSwapFree() = run {
        val page = hold(HoldKind.Page)
        settle()
        val swap = hold(null)
        settle()
        val walk = hold(HoldKind.Walk)
        settle()
        walk.job.cancel()
        walk.job.join()
        page.done()
        settle()
        assertTrue(swap.entered)
        swap.done()
        // Nothing of the stopped walk is left holding: the next swap enters.
        val next = hold(null)
        settle()
        assertTrue(next.entered)
        next.done()
        assertFalse(walk.ran)
    }

    @Test
    fun aSwapStoppedWhileWaitingLetsTheTurnedAwayIn() = run {
        val read = hold(HoldKind.Read)
        settle()
        val swap = hold(null)
        settle()
        val page = hold(HoldKind.Page)
        val walk = hold(HoldKind.Walk)
        settle()
        assertFalse(page.entered || walk.entered)
        swap.job.cancel()
        swap.job.join()
        settle()
        assertTrue("the turned-away holds still wait after the swap gave up", page.entered && walk.entered)
        assertFalse(swap.ran)
        listOf(read, page, walk).forEach { it.done() }
        val next = hold(null)
        settle()
        assertTrue(next.entered)
        next.done()
    }

    // A waiter let in and stopped before it wakes (a stopped coroutine never
    // resumes normally) gives back the hold it was counted for.
    @Test
    fun aWaiterStoppedBetweenItsGrantAndWakingLetsGo() = run {
        var ran = false
        lateinit var page: Job
        gate.exclusive {
            page = launch { gate.shared(HoldKind.Page) { ran = true } }
            settle()
        }
        // The exclusive hold let the page in on its way out; it has not woken.
        page.cancel()
        page.join()
        assertFalse(ran)
        val swap = hold(null)
        settle()
        assertTrue("the stopped page still holds the lock", swap.entered)
        swap.done()
    }

    // Holding the lock, shared or alone, a call for it again runs at once:
    // a nested page inside a page while a swap waits, and a page inside the
    // swap, would otherwise wait on themselves.
    @Test
    fun aNestedHoldRunsAtOnce() = run {
        val order = ArrayList<String>()
        val swapWaits = CompletableDeferred<Unit>()
        val outer = launch {
            gate.shared(HoldKind.Page) {
                order += "outer"
                swapWaits.await()
                gate.shared(HoldKind.Page) { order += "nested" }
            }
        }
        settle()
        val swap = launch { gate.exclusive { order += "swap" } }
        settle()
        swapWaits.complete(Unit)
        outer.join()
        swap.join()
        assertEquals(listOf("outer", "nested", "swap"), order)
        gate.exclusive { gate.shared(HoldKind.Walk) { order += "inside the swap" } }
        assertEquals("inside the swap", order.last())
        assertFalse(gate.held())
    }
}

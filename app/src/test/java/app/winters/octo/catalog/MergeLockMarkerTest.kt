package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

// A hold of the merge lock or a stable read asked for inside one runs its
// block once, at once, and never throws: inside a shared hold, inside a
// stable read, and inside each group of a save, where waiting for the lock
// or for an even stamp would wait forever.
@RunWith(RobolectricTestRunner::class)
class MergeLockMarkerTest {
    private val server = "server:home.test"
    private val db = openTestCatalog()

    @After
    fun close() {
        db.close()
    }

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    @Test
    fun insideAHoldEachRunsAtOnce() = run {
        val merge = db.testMerge(server)
        var blocks = 0
        var prepared = 0
        val answer = merge.betweenRebuilds(HoldKind.Page) {
            assertTrue(merge.holdsMergeLock())
            val nested = merge.betweenRebuilds(HoldKind.Walk) { ++blocks }
            val read = stableRead(merge, HoldKind.Read, prepare = { prepared++ }) { ++blocks }
            nested + read
        }
        assertEquals(3, answer)
        assertEquals(2, blocks)
        assertEquals("a stable read inside a hold prepared", 0, prepared)
        assertFalse(merge.holdsMergeLock())
    }

    @Test
    fun aStableReadInsideAStableReadRunsAtOnce() = run {
        val merge = db.testMerge(server)
        var outer = 0
        var inner = 0
        var outerPrepared = 0
        var innerPrepared = 0
        val answer = stableRead(merge, HoldKind.Read, prepare = { outerPrepared++ }) {
            outer++
            stableRead(merge, HoldKind.Walk, prepare = { innerPrepared++ }) {
                inner++
                7
            }
        }
        assertEquals(7, answer)
        assertEquals(listOf(1, 1, 1, 0), listOf(outer, inner, outerPrepared, innerPrepared))
    }

    // Inside each group of a save, which holds the lock alone.
    @Test
    fun insideAGroupEachRunsAtOnce() = run {
        db.put(library(DEVICE, 30))
        val merge = db.boundMerge(server, SAVE_GROUP_FLOOR)
        val ran = CopyOnWriteArrayList<String>()
        merge.hooks = object : MergeHooks() {
            override suspend fun inGroup(turn: Long, group: Long) {
                ran += "stamp ${merge.swapStamp.value % 2}"
                merge.betweenRebuilds(HoldKind.Page) { ran += "page" }
                stableRead(merge, HoldKind.Walk) { ran += "read" }
            }
        }
        merge.rebuild()
        assertTrue("${ran.size / 3} groups", ran.size >= 6)
        assertEquals(List(ran.size / 3) { listOf("stamp 1", "page", "read") }.flatten(), ran.toList())
    }
}

package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

// The merge lock's admissions while a save applies, per class of hold:
// short holds (Read, Page) enter while a group waits for a long hold (Walk)
// to leave and queue once it waits only for short ones; a Walk asked for in
// the middle of a save enters after one group, before the next; and a
// waiter of each class, or a group, let in and stopped at once gives back
// what it was counted for, so whoever may enter then does.
@RunWith(RobolectricTestRunner::class)
class SaveGateTest {
    private fun run(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    private suspend fun settle() = repeat(20) { yield() }

    private class Holder(private val gate: SwapGate, private val kind: HoldKind?) {
        val inside = CompletableDeferred<Unit>()
        val leave = CompletableDeferred<Unit>()
        lateinit var job: Job

        fun start(scope: CoroutineScope): Holder {
            job = scope.launch {
                val block: suspend () -> Unit = {
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

    @Test
    fun shortHoldsEnterWhileAGroupWaitsForALongOneAndQueueOnceItWaitsForShortOnes() = run {
        for (short in listOf(HoldKind.Read, HoldKind.Page)) {
            val gate = SwapGate()
            val walk = Holder(gate, HoldKind.Walk).start(this)
            settle()
            val group = Holder(gate, null).start(this)
            settle()
            val first = Holder(gate, short).start(this)
            settle()
            assertTrue("$short waited behind a group that waits for a walk", first.entered)
            walk.done()
            settle()
            assertFalse(group.entered)
            val second = Holder(gate, short).start(this)
            settle()
            assertFalse("$short entered while the group waited for short holds", second.entered)
            first.done()
            settle()
            assertTrue(group.entered)
            assertFalse(second.entered)
            group.done()
            settle()
            assertTrue(second.entered)
            second.done()
        }
    }

    // A waiter of each class, and a group, let in by a holder leaving and
    // stopped before it wakes: what it was counted for is given back.
    @Test
    fun aWaiterOfEachClassAndAGroupAdmittedAndStoppedAtOnceLetGo() = run {
        for (kind in listOf(HoldKind.Read, HoldKind.Page, HoldKind.Walk)) {
            val gate = SwapGate()
            var ran = false
            lateinit var waiter: Job
            gate.exclusive {
                waiter = launch { gate.shared(kind) { ran = true } }
                settle()
            }
            // Let in as the group left, not yet woken: stopped now.
            waiter.cancel()
            waiter.join()
            assertFalse("$kind ran", ran)
            val group = Holder(gate, null).start(this)
            settle()
            assertTrue("the stopped $kind still holds the lock", group.entered)
            group.done()
        }
        val gate = SwapGate()
        var ran = false
        lateinit var waiter: Job
        val reader = Holder(gate, HoldKind.Read).start(this)
        settle()
        waiter = launch { gate.exclusive { ran = true } }
        settle()
        val queued = Holder(gate, HoldKind.Page).start(this)
        settle()
        assertFalse(queued.entered)
        // The reader leaves and the group is let in; it is stopped before it
        // wakes, so the queued page enters.
        reader.leave.complete(Unit)
        waiter.cancel()
        reader.job.join()
        waiter.join()
        settle()
        assertFalse("the group ran", ran)
        assertTrue("the queued page still waits for a stopped group", queued.entered)
        queued.done()
    }

    // The key refill writes each page under the source-write gate, so no
    // save's find or apply meets a key it did not read: while a source
    // write holds the gate, the refill writes nothing.
    @Test
    fun theKeyRefillWritesUnderTheSourceWriteGate() = runBlocking {
        val db = openTestCatalog()
        try {
            db.put(library("server:keys.test", 50))
            db.exec("UPDATE source_track SET titleKey = 'stale', matchKey = 'stale', keysVersion = 0")
            val stale = { db.longOf("SELECT COUNT(*) FROM source_track WHERE keysVersion != ?", SONG_KEYS_VERSION) }
            val holding = java.util.concurrent.atomic.AtomicBoolean(true)
            val writtenWhileHeld = java.util.concurrent.atomic.AtomicInteger()
            val written = java.util.concurrent.atomic.AtomicInteger()
            val read = CompletableDeferred<Unit>()
            val inner = db.workDao()
            val work = object : WorkDao by inner {
                override suspend fun keyRows(old: Int, after: String, limit: Int): List<KeyRow> = inner.keyRows(old, after, limit).also { read.complete(Unit) }

                override suspend fun setKeys(id: String, titleKey: String, matchKey: String, version: Int, old: Int) {
                    written.incrementAndGet()
                    if (holding.get()) writtenWhileHeld.incrementAndGet()
                    inner.setKeys(id, titleKey, matchKey, version, old)
                }
            }
            val merge = CatalogMerge(db, { Long.MAX_VALUE }, { "server:keys.test" }, work, SAVE_GROUP_ENTRIES)
            val held = CompletableDeferred<Unit>()
            val leave = CompletableDeferred<Unit>()
            val writer = async(Dispatchers.Default) { merge.sourceWrite { held.complete(Unit); leave.await() } }
            held.await()
            merge.start()
            // The refill has read its first page while a source write holds
            // the gate; give it every chance to write.
            read.await()
            repeat(500) { yield() }
            holding.set(false)
            leave.complete(Unit)
            writer.await()
            withTimeout(10_000) { merge.keysReady.first { it } }
            assertEquals("the refill wrote while a source write held the gate", 0, writtenWhileHeld.get())
            assertEquals(50, written.get())
            assertEquals(0L, stale())
        } finally {
            db.close()
        }
    }

    // A Walk asked for while a save's group runs enters as that group
    // leaves, before the save's next group.
    @Test
    fun aWalkAskedInTheMiddleOfASaveEntersAfterOneGroup() = runBlocking {
        for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) {
            val db = openTestCatalog()
            try {
                db.put(library(DEVICE, if (bound == SAVE_GROUP_FLOOR) 30 else 600))
                val order = CopyOnWriteArrayList<String>()
                val asked = CompletableDeferred<Unit>()
                lateinit var merge: CatalogMerge
                var walk: kotlinx.coroutines.Deferred<Unit>? = null
                val hooks = object : GroupHooks() {}
                hooks.inside = { turn ->
                    order += "group $turn"
                    if (turn == 2L && walk == null) {
                        walk = async(Dispatchers.Default) {
                            merge.betweenRebuilds(HoldKind.Walk) {
                                order += "walk"
                            }
                        }
                        // The walk has asked: it queues behind this group.
                        withTimeout(5_000) { while (merge.lockWaiters() == 0) yield() }
                        asked.complete(Unit)
                    }
                }
                merge = db.boundMerge(null, bound, hooks)
                merge.rebuild()
                walk!!.await()
                assertTrue(asked.isCompleted)
                val at = order.indexOf("walk")
                assertEquals("bound $bound: $order", "group 2", order[at - 1])
                assertTrue("bound $bound: no group after the walk: $order", order.size > at + 1 && order[at + 1] == "group 3")
            } finally {
                db.close()
            }
        }
    }
}

package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Between two groups of a save the database's write turn goes to every
// writer already waiting: a write asked for while group k runs commits
// before group k+1 begins. No write of a save runs outside a transaction.
@RunWith(RobolectricTestRunner::class)
class SaveHandoffTest {
    @Test
    fun aWriterWaitingOnAGroupCommitsBeforeTheNext() = runBlocking {
        for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) {
            val db = openTestCatalog()
            try {
                db.put(library(DEVICE, if (bound == SAVE_GROUP_FLOOR) 30 else 600))
                val asked = CompletableDeferred<Thread>()
                var writer: kotlinx.coroutines.Deferred<Unit>? = null
                val own = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
                val hooks = object : GroupHooks() {}
                hooks.inside = { turn ->
                    if (turn == 2L) {
                        writer = async(own) {
                            withContext(WriteLabels.of("a like")) {
                                asked.complete(Thread.currentThread())
                                db.userDao().like(LikedTrackEntity("$DEVICE:s1", "k", 1))
                            }
                        }
                        // The writer's thread has asked Room for the write
                        // turn and gone idle, waiting behind this group.
                        val thread = asked.await()
                        withTimeout(5_000) { while (thread.state == Thread.State.RUNNABLE) yield() }
                    }
                }
                val merge = db.boundMerge(null, bound, hooks)
                val (_, ran) = db.counted.record { runBlocking { merge.rebuild() } }
                writer!!.await()
                own.close()
                val begins = db.counted.begins
                val groups = begins.filter { it.label == SAVE_APPLY }.map { it.beginSeq }.sorted()
                val like = begins.single { it.label == "a like" }.beginSeq
                assertTrue("bound $bound: ${groups.size} groups", groups.size >= 3)
                assertTrue("bound $bound: the like began before group 2's", like > groups[1])
                assertTrue("bound $bound: the like waited past group 3's begin", like < groups[2])
                assertEquals(1L, db.longOf("SELECT COUNT(*) FROM liked_track"))
                // Every write the save made ran inside a transaction.
                val writes = ran.filter { (it.label == SAVE_APPLY || it.label == SAVE_FIND || it.label == SAVE_UNMERGES) && it.rowsChanged > 0 }
                assertTrue("bound $bound: no save write recorded", writes.isNotEmpty())
                assertTrue("bound $bound: ${writes.filterNot { it.inTransaction }.take(3)}", writes.all { it.inTransaction })
            } finally {
                db.close()
            }
        }
    }
}

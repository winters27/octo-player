package app.winters.octo.playback

import androidx.room.withTransaction
import app.winters.octo.catalog.SAVE_GROUP_ENTRIES
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.ArrayDeque

// Room's transaction queue is first come, first served for a save and a
// write beside it: a write already waiting in the queue while a group of
// the save holds its transaction enters before the save's next group.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QueuedWriteFirstTest {
    @Test
    fun aWriteQueuedDuringAGroupEntersBeforeTheSavesNextGroup() {
        ParkedSave(SAVE_GROUP_ENTRIES).use { p ->
            val db = p.db
            db.openHelper.writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS queued_write (n INTEGER PRIMARY KEY NOT NULL)")
            // Parked inside a page of a run, the page's transaction held.
            p.park()
            val turn = p.turn
            assertTrue("parked inside a run", p.hooks.inRun(turn - 1))
            assertEquals("the groups begun at the park", turn, p.hooks.groups.size.toLong())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val entered = CompletableDeferred<Int>()
                val write = scope.async {
                    db.withTransaction {
                        entered.complete(p.hooks.groups.size)
                        db.openHelper.writableDatabase.execSQL("INSERT INTO queued_write (n) VALUES (1)")
                    }
                }
                // The write waits in Room's queue behind the held group.
                val queue = db.transactionExecutor.let { exec ->
                    exec.javaClass.getDeclaredField("tasks").apply { isAccessible = true }.get(exec) as ArrayDeque<*>
                }
                val end = System.nanoTime() + 10_000_000_000L
                while (queue.isEmpty()) {
                    assertTrue("the write never reached Room's queue", System.nanoTime() < end)
                    Thread.sleep(1)
                }
                assertTrue("the write entered while the group held its transaction", !entered.isCompleted)
                val groups = p.release()
                runBlocking { withTimeout(10_000) { write.await() } }
                val at = runBlocking { entered.await() }
                assertTrue("the save went on after the park: $groups groups, parked at $turn", groups > turn)
                assertEquals("the groups begun when the write entered: none after the held one", turn, at.toLong())
                println("RESULT queue-1b parked at group $turn of $groups; the queued write entered with $at groups begun")
            } finally {
                scope.cancel()
            }
        }
    }
}

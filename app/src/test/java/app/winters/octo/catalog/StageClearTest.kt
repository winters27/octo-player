package app.winters.octo.catalog

import app.winters.octo.listening.timed
import app.winters.octo.server.StagedCopy
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

private const val ACCOUNT = "acc-1"

// A copy swapped in leaves the stage after the merge has let the gate go,
// in the background, a page at a time: the merge's waiters, a write to the
// sources and a read of the library each go on while it runs, and the
// source's next copy waits for it (the source is marked clearing) before it
// stages anything. A clear the app stopped in the middle of is finished by
// the next copy first, a page at a time.
@RunWith(RobolectricTestRunner::class)
class StageClearTest {
    private val db = openTestCatalog()
    private val server = "server:clear.test"

    @After
    fun close() = db.close()

    // Holds a swapped copy's clear after its first page until let go, and
    // notes whether the gate was held then and when the clear ended.
    private class Held : MergeHooks() {
        val at = Pause()
        val events: MutableList<String> = Collections.synchronizedList(ArrayList())
        @Volatile var gateHeld: Boolean? = null
        lateinit var merge: CatalogMerge

        override suspend fun stageClearPage(sourceId: String) {
            if (gateHeld == null) gateHeld = merge.gateHeld
            at.hit()
        }

        override fun stageCleared(sourceId: String, rows: Long, millis: Long) {
            events += "cleared"
        }
    }

    private fun stageRows(): Long =
        listOf("stage_track", "stage_album", "stage_artist").sumOf { db.longOf("SELECT COUNT(*) FROM $it WHERE sourceId = ?", server) }

    @Test
    fun noScreenWaitsForAStageClearAndTheNextCopyWaitsForIt() = timed(60) {
        coroutineScope {
            val hooks = Held()
            val merge = db.testMerge(server, hooks).also { hooks.merge = it }
            val copy = StagedCopy(db, merge, FreeSpace { Long.MAX_VALUE })
            db.stage(library(server, 3_000), generation = 1)
            val swap = async(Dispatchers.Default) {
                merge.rebuild()
                merge.awaitStage(server, 1)
            }
            hooks.at.reached.await()
            assertEquals("the clear ran while the gate was held", false, hooks.gateHeld)
            // The merge's waiters are answered, and a write to the sources
            // and a read of the library go on, while the clear is held.
            swap.await()
            merge.sourceWrite { }
            merge.betweenRebuilds(HoldKind.Read) { }
            assertEquals(3_000L, db.longOf("SELECT COUNT(*) FROM track"))
            assertTrue("the clear is held part way: ${stageRows()} rows left", stageRows() in 1 until 3_301)
            assertEquals(true, db.workDao().stageClearing(server))
            // The source's next copy waits for the clear before it stages.
            val next = launch(start = CoroutineStart.UNDISPATCHED) {
                copy.start(ACCOUNT, server)
                hooks.events += "started"
            }
            assertFalse("the next copy started during the clear", next.isCompleted)
            hooks.at.release()
            next.join()
            assertEquals(listOf("cleared", "started"), hooks.events.toList())
            assertEquals(false, db.workDao().stageClearing(server))
            assertEquals(0L, stageRows())
            assertEquals(STAGE_BUILDING, db.stringOf("SELECT stage FROM source_meta WHERE sourceId = ?", server))
        }
    }

    // The app stopped part way through a clear: rows left in the stage, the
    // source still marked clearing. The next copy clears them first, a page
    // of rows a transaction, and the mark goes.
    @Test
    fun aClearCutOffIsFinishedByTheNextCopyAPageAtATime() = timed(60) {
        val merge = db.testMerge(server)
        val copy = StagedCopy(db, merge, FreeSpace { Long.MAX_VALUE })
        val left = library(server, 2 * STAGE_CLEAR_ROWS + 300)
        db.stage(left, generation = 4)
        db.exec("UPDATE source_meta SET stage = '$STAGE_NONE', stageClearing = 1 WHERE sourceId = ?", server)
        val staged = (left.tracks.size + left.albums.size + left.artists.size).toLong()
        db.counted.record { runBlocking { copy.start(ACCOUNT, server) } }
        val deletes = db.counted.transactions.filter { it.rowsChanged > 0 && it.label == STAGE_CLEAR }
        assertTrue("${deletes.size} clear transactions", deletes.size >= 3)
        assertTrue("${deletes.maxOf { it.rowsChanged }} rows in one", deletes.all { it.rowsChanged <= STAGE_CLEAR_ROWS })
        assertEquals(staged, deletes.sumOf { it.rowsChanged })
        assertEquals(false, db.workDao().stageClearing(server))
        assertEquals(0L, stageRows())
    }
}

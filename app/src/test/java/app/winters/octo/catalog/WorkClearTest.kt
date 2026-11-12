package app.winters.octo.catalog

import app.winters.octo.listening.timed
import app.winters.octo.server.CopyPoint
import app.winters.octo.server.StagedCopy
import app.winters.octo.subsonic.Starred
import app.winters.octo.testing.FakeSubsonic
import app.winters.octo.testing.GenShape
import app.winters.octo.testing.LibraryGen
import app.winters.octo.testing.CountedTransaction
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

// The rows a merge or a copy leaves behind go after it, in the background,
// a page at a time, while nothing holds the source-write gate: a copy the
// merge dropped (its stage), the merge's own work rows, and a copy's pages
// of the server's library. The merge's waiters, a write to the sources and
// a read of the library go on meanwhile, and the next work that needs those
// rows gone waits for the clear: the source's next copy for its stage and
// for the pages, the next merge for its work rows.
@RunWith(RobolectricTestRunner::class)
class WorkClearTest {
    private val db = openTestCatalog()
    private val server = "server:clears.test"

    @After
    fun close() = db.close()

    // Holds one clear after its first page until let go, and notes whether
    // the gate was held then and when the clear ended.
    private class Held(private val what: String) : MergeHooks() {
        val at = Pause()
        val events: MutableList<String> = Collections.synchronizedList(ArrayList())
        @Volatile var gateHeld: Boolean? = null
        lateinit var merge: CatalogMerge

        private suspend fun page() {
            if (gateHeld == null) gateHeld = merge.gateHeld
            at.hit()
        }

        override suspend fun stageClearPage(sourceId: String) {
            if (what == STAGE_CLEAR) page()
        }

        override fun stageCleared(sourceId: String, rows: Long, millis: Long) {
            if (what == STAGE_CLEAR) events += "cleared"
        }

        override suspend fun clearPage(what: String) {
            if (what == this.what) page()
        }

        override fun cleared(what: String, rows: Long) {
            if (what == this.what) events += "cleared"
        }
    }

    private fun stageRows(): Long =
        listOf("stage_track", "stage_album", "stage_artist").sumOf { db.longOf("SELECT COUNT(*) FROM $it WHERE sourceId = ?", server) }

    private fun workRows(): Long =
        listOf("merge_candidate", "merged_id_next", "album_next", "artist_next").sumOf { db.longOf("SELECT COUNT(*) FROM $it") }

    private fun syncRows(): Long =
        listOf("sync_song", "sync_album", "sync_artist").sumOf { db.longOf("SELECT COUNT(*) FROM $it") }

    // The merge, its waiter, a write to the sources and a read of the
    // library all go on while the held clear runs, the gate let go.
    private suspend fun goesOnDuringTheClear(hooks: Held, merge: CatalogMerge) {
        hooks.at.reached.await()
        assertEquals("the clear ran while the gate was held", false, hooks.gateHeld)
        merge.sourceWrite { }
        merge.betweenRebuilds(HoldKind.Read) { }
        db.longOf("SELECT COUNT(*) FROM track")
    }

    // A server's copy built only up to its pages: the copy stops as its
    // build starts, leaving every page of the server's library.
    private suspend fun stoppedCopy(copy: StagedCopy, songs: Int) {
        val library = LibraryGen.server(3, GenShape(songs = songs, albums = songs / 30, artists = 20))
        val fake = FakeSubsonic(library, Starred())
        copy.reached = { point -> if (point == CopyPoint.Build) throw IllegalStateException("stopped as the build starts") }
        try {
            assertTrue(runCatching { copy.copy(ACCOUNT, server, fake.client()) }.isFailure)
        } finally {
            copy.reached = {}
            fake.server.close()
        }
    }

    @Test
    fun aDroppedCopyLeavesTheStageAfterTheMergeAndTheNextCopyWaits() = timed(60) {
        coroutineScope {
            val hooks = Held(STAGE_CLEAR)
            // No room for the swap: the merge drops the copy it claimed.
            val merge = db.testMerge(server, hooks, space = 1).also { hooks.merge = it }
            val copy = StagedCopy(db, merge, FreeSpace { Long.MAX_VALUE })
            db.stage(library(server, 3_000), generation = 1)
            val outcome = async(Dispatchers.Default) { runCatching { merge.rebuild(); merge.awaitStage(server, 1) } }.await()
            assertTrue("the copy was not swapped in: $outcome", outcome.exceptionOrNull() is StageAborted)
            assertTrue("the dropped copy leaves the stage after the merge: ${stageRows()} rows left", stageRows() in 1 until 3_301)
            goesOnDuringTheClear(hooks, merge)
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
            assertEquals(0L, stageRows())
        }
    }

    @Test
    fun theMergesWorkRowsLeaveAfterItAndTheNextMergeWaits() = timed(60) {
        coroutineScope {
            val hooks = Held(MERGE_WORK_CLEAR)
            val merge = db.testMerge(server, hooks).also { hooks.merge = it }
            db.stage(library(server, 3_000), generation = 1)
            async(Dispatchers.Default) {
                merge.rebuild()
                merge.awaitStage(server, 1)
            }.await()
            assertEquals(3_000L, db.longOf("SELECT COUNT(*) FROM track"))
            assertTrue("the merge's work rows leave after it: ${workRows()} rows left", workRows() > 0)
            goesOnDuringTheClear(hooks, merge)
            // The next merge waits for the clear before it takes the gate.
            val next = launch(Dispatchers.Default) {
                merge.rebuild()
                hooks.events += "merged"
            }
            assertFalse("the next merge ended during the clear", next.isCompleted)
            hooks.at.release()
            next.join()
            assertEquals(listOf("cleared", "merged"), hooks.events.toList())
            assertEquals(0L, workRows())
        }
    }

    @Test
    fun aCopysPagesLeaveAfterItAndTheNextCopyWaits() = timed(60) {
        coroutineScope {
            val hooks = Held(COPY_PAGES_CLEAR)
            val merge = db.testMerge(server, hooks).also { hooks.merge = it }
            val copy = StagedCopy(db, merge, FreeSpace { Long.MAX_VALUE })
            stoppedCopy(copy, 3_000)
            assertTrue("the copy's pages leave after it: ${syncRows()} rows left", syncRows() > 0)
            goesOnDuringTheClear(hooks, merge)
            // Every next copy waits for the clear before it reads.
            val next = launch(start = CoroutineStart.UNDISPATCHED) {
                copy.start(ACCOUNT, server)
                hooks.events += "started"
            }
            assertFalse("the next copy started during the clear", next.isCompleted)
            hooks.at.release()
            next.join()
            assertEquals(listOf("cleared", "started"), hooks.events.toList())
            assertEquals(0L, syncRows())
        }
    }

    // Each clear deletes at most STAGE_CLEAR_ROWS rows a transaction, under
    // its own label, every row it leaves. The stage's clear and the merge's
    // work clear each cost at most SAVE_GROUP_ENTRIES a transaction, measured
    // from what it ran as the save's groups are (Measure).
    @Test
    fun eachClearGoesAPageATransaction() = timed(120) {
        val rows = 2 * STAGE_CLEAR_ROWS + 300
        // A dropped copy's stage.
        val dropping = db.testMerge(server, space = 1)
        db.stage(library(server, rows), generation = 1)
        val measure = Measure(db.openHelper.writableDatabase)
        fun labeled(label: String, block: suspend () -> Unit): List<Pair<CountedTransaction, SaveCost>> {
            val (_, statements) = db.counted.record { runBlocking { block() } }
            val all = db.counted.transactions.sortedBy { it.beginSeq }
            val begins = all.map { it.beginSeq } + Long.MAX_VALUE
            return all.withIndex().filter { (_, t) -> t.rowsChanged > 0 && t.label == label }.map { (i, t) ->
                t to measure.of(statements.filter { it.inTransaction && !it.bookkeeping && it.label == label && it.seq > begins[i] && it.seq < begins[i + 1] })
            }
        }
        val stage = labeled(STAGE_CLEAR) {
            runCatching { dropping.rebuild() }
            dropping.awaitStageClear(server)
            dropping.awaitClear(MERGE_WORK_CLEAR)
        }
        // A merge's work rows, and a stopped copy's pages.
        val merge = db.testMerge(server)
        db.stage(library(server, rows), generation = 2)
        val work = labeled(MERGE_WORK_CLEAR) {
            merge.rebuild()
            merge.awaitClear(MERGE_WORK_CLEAR)
        }
        val copy = StagedCopy(db, merge, FreeSpace { Long.MAX_VALUE })
        val pages = labeled(COPY_PAGES_CLEAR) {
            stoppedCopy(copy, rows)
            merge.awaitClear(COPY_PAGES_CLEAR)
        }
        for ((name, deletes) in listOf("stage" to stage, "work" to work, "pages" to pages)) {
            assertTrue("$name: ${deletes.size} clear transactions", deletes.size >= 3)
            assertTrue("$name: ${deletes.maxOf { it.first.rowsChanged }} rows in one", deletes.all { it.first.rowsChanged <= STAGE_CLEAR_ROWS })
        }
        for ((name, deletes) in listOf("stage" to stage, "work" to work)) {
            val most = deletes.maxBy { it.second.total }
            println("WorkClearTest $name clear: ${deletes.size} transactions, largest cost ${most.second.total} (${most.second.reads} reads, ${most.second.writes} writes, ${most.first.rowsChanged} rows)")
            assertTrue("$name: a transaction costs ${most.second.total}, over $SAVE_GROUP_ENTRIES", most.second.total <= SAVE_GROUP_ENTRIES)
            assertTrue("$name: no cost measured", most.second.total > 0)
        }
        assertEquals(0L, stageRows() + workRows() + syncRows())
    }
}

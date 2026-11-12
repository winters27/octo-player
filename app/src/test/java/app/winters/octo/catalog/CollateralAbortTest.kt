package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.testing.FakeMediaStore
import app.winters.octo.testing.PhoneFiles
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.phoneLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// The phone's copy and a server's copy claimed by one merge: a copy that
// fails its check before the save's first group takes only itself out,
// nothing of the save written, and the other is claimed again in the same
// run, once at most. A save that fails after its first group resumes.
@RunWith(RobolectricTestRunner::class)
class CollateralAbortTest {
    private val server = "server:home.test"
    private val db = openTestCatalog()

    @After
    fun close() {
        db.close()
    }

    private fun <T> run(block: suspend CoroutineScope.() -> T): T = runBlocking { withTimeout(10_000) { block() } }

    // How a copy ended: null when it was swapped in, else why not.
    private suspend fun CatalogMerge.outcomeOf(sourceId: String, generation: Long): String? =
        try {
            awaitStage(sourceId, generation)
            null
        } catch (e: StageAborted) {
            e.reason
        }

    // The server's copy fails the swap's check (one staged song more than it
    // holds) the first `times` times it is checked.
    private inner class BreaksServer(times: Int) : WorkDao by db.workDao() {
        private val inner = db.workDao()
        private val left = AtomicInteger(times)

        override suspend fun stageTrackCount(sourceId: String): Int {
            val count = inner.stageTrackCount(sourceId)
            return if (sourceId == server && left.getAndDecrement() > 0) count + 1 else count
        }
    }

    private suspend fun libraryBefore(): CatalogMerge {
        db.put(library(DEVICE, 4))
        db.put(library(server, 4, msShift = 100))
        return CatalogMerge(db, { Long.MAX_VALUE }, { server }, db.workDao()).also { it.rebuild() }
    }

    private fun phoneRows() = db.longOf("SELECT COUNT(*) FROM source_track WHERE sourceId = ?", DEVICE)

    @Test
    fun theServersFailedCheckLeavesThePhonesCopyToBeSwappedIn() = run {
        libraryBefore()
        val hooks = TestHooks()
        val merge = CatalogMerge(db, { Long.MAX_VALUE }, { server }, BreaksServer(1)).also { it.hooks = hooks }
        // New copies of both, the phone's first: the phone has two new songs.
        db.stage(library(DEVICE, 6), generation = 1)
        db.stage(library(server, 5, msShift = 100), generation = 2)
        assertEquals(DEVICE, db.workDao().metas().first().sourceId)
        merge.rebuild()
        assertNull("the phone's copy was dropped with the server's", merge.outcomeOf(DEVICE, 1))
        assertNotNull(merge.outcomeOf(server, 2))
        assertEquals(6L, phoneRows())
        assertEquals(6L, db.longOf("SELECT COUNT(*) FROM track"))
        // Both checks were in the one merge the ask ran; only the second
        // claim's save wrote a group.
        assertEquals(2, hooks.checks.get())
        assertEquals(listOf("swap"), hooks.events.toList())
        assertEquals(1, hooks.merges.get())
        // The phone's copy leaves the stage after the merge lets the gate go.
        merge.awaitStageClear(DEVICE)
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM stage_track"))
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM source_meta WHERE stage != ?", STAGE_NONE))
        // The server's live rows are as they were.
        assertEquals(4L, db.longOf("SELECT COUNT(*) FROM source_track WHERE sourceId = ?", server))
    }

    @Test
    fun aCopyPutBackOnceIsDroppedAtTheSecondFailure() = run {
        libraryBefore()
        lateinit var merge: CatalogMerge
        lateinit var restage: Job
        val swaps = AtomicInteger()
        val scope = this
        val hooks = object : MergeHooks() {
            override suspend fun checking() {
                // At the first check, a new server copy waits for the gate,
                // so it is ready before the merge claims again.
                if (swaps.incrementAndGet() == 1) {
                    restage = scope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        merge.sourceWrite { db.stage(library(server, 5, msShift = 100), generation = 3) }
                    }
                }
            }
        }
        merge = CatalogMerge(db, { Long.MAX_VALUE }, { server }, BreaksServer(2)).also { it.hooks = hooks }
        db.stage(library(DEVICE, 6), generation = 1)
        db.stage(library(server, 5, msShift = 100), generation = 2)
        merge.rebuild()
        restage.join()
        assertTrue("swaps ${swaps.get()}", swaps.get() >= 2)
        assertNotNull("the phone's copy was put back twice", merge.outcomeOf(DEVICE, 1))
        assertNotNull(merge.outcomeOf(server, 2))
        assertNotNull(merge.outcomeOf(server, 3))
        assertEquals(4L, phoneRows())
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM stage_track"))
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM source_meta WHERE stage != ?", STAGE_NONE))
    }

    // The phone's own copy fails the swap's check the first `times` times it
    // is checked.
    private inner class BreaksPhone(times: Int) : WorkDao by db.workDao() {
        private val inner = db.workDao()
        private val left = AtomicInteger(times)

        override suspend fun stageTrackCount(sourceId: String): Int {
            val count = inner.stageTrackCount(sourceId)
            return if (sourceId == DEVICE && left.getAndDecrement() > 0) count + 1 else count
        }
    }

    // A scan whose copy the merge drops gets one more try; when that one's
    // is dropped too, the scan stops with nothing marked as scanned, and the
    // next ask scans again from the start.
    @Test
    fun aScanWhoseCopyIsDroppedTwiceStopsUntilAskedAgain() = run {
        val merge = CatalogMerge(db, { Long.MAX_VALUE }, { null }, BreaksPhone(2))
        val phone = db.phoneLibrary(PhoneFiles.albums(files = 40, perAlbum = 10), merge)
        phone.rescan()
        assertEquals("the scan and its one more try", 2, FakeMediaStore.current!!.queries.get())
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM track"))
        assertNull(db.stringOf("SELECT digest FROM phone_scan_state WHERE id = 0"))
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM stage_track"))

        phone.rescan()
        assertEquals(3, FakeMediaStore.current!!.queries.get())
        assertEquals(40L, db.longOf("SELECT COUNT(*) FROM track"))
    }

    // A save of both copies that fails after its first group is never put
    // back or dropped: each copy hears its save waits (ApplyPending), the
    // stop starts one resume, and the resume swaps both copies in.
    @Test
    fun aCopyThatFailsAfterTheFirstGroupResumes() = run {
        libraryBefore()
        val hooks = GroupHooks()
        val broke = AtomicBoolean(false)
        hooks.inside = { turn -> if (turn == 2L && broke.compareAndSet(false, true)) throw IllegalStateException("a group broke") }
        val merge = db.boundMerge(server, SAVE_GROUP_FLOOR, hooks)
        db.stage(library(DEVICE, 30), generation = 1)
        db.stage(library(server, 30, msShift = 100), generation = 2)
        assertNotNull("the save did not fail", runCatching { merge.rebuild() }.exceptionOrNull())
        assertTrue(broke.get())
        for ((source, generation) in listOf(DEVICE to 1L, server to 2L)) {
            val outcome = runCatching { merge.awaitStage(source, generation) }.exceptionOrNull()
            assertTrue("$source: $outcome", outcome is ApplyPending)
        }
        // The stop's resume ends the save; a merge ask waits for it.
        merge.rebuild()
        assertNull(db.stringOf("SELECT applyClaim FROM merge_state"))
        assertEquals(30L, phoneRows())
        assertEquals(30L, db.longOf("SELECT COUNT(*) FROM source_track WHERE sourceId = ?", server))
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM source_track WHERE mergedId = ''"))
        merge.awaitStageClear(DEVICE)
        merge.awaitStageClear(server)
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM stage_track"))
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM source_meta WHERE stage != ?", STAGE_NONE))
    }

    @Test
    fun aSpaceAbortDropsEveryCopyWithTheCopysWords() = run {
        libraryBefore()
        val merge = CatalogMerge(db, { 10 }, { server }, db.workDao())
        db.stage(library(DEVICE, 6), generation = 1)
        db.stage(library(server, 5, msShift = 100), generation = 2)
        merge.rebuild()
        val phone = merge.outcomeOf(DEVICE, 1)
        assertNotNull(phone)
        assertTrue(phone!!, phone.startsWith("Not enough space"))
        assertEquals(phone, merge.outcomeOf(server, 2))
        assertEquals(4L, phoneRows())
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM stage_track"))
    }
}

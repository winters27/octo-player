package app.winters.octo.device

import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.GroupHooks
import app.winters.octo.catalog.MergeHooks
import app.winters.octo.catalog.SAVE_GROUP_FLOOR
import app.winters.octo.catalog.WorkDao
import app.winters.octo.catalog.boundMerge
import app.winters.octo.catalog.library
import app.winters.octo.catalog.longOf
import app.winters.octo.catalog.put
import app.winters.octo.catalog.stage
import app.winters.octo.catalog.stringOf
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

// The phone's scan beside a server's copy that fails its check before the
// save's first group: once, the phone's copy is claimed again in the same
// merge and no second scan runs; twice in one merge, the phone's copy is
// dropped and the one more scan brings its files in. A save failing after
// its first group is resumed instead.
@RunWith(RobolectricTestRunner::class)
class PhoneCollateralAbortTest {
    private val server = "server:home.test"
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private fun <T> run(block: suspend CoroutineScope.() -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private inner class BreaksServer(times: Int) : WorkDao by db.workDao() {
        private val inner = db.workDao()
        private val left = AtomicInteger(times)

        override suspend fun stageTrackCount(sourceId: String): Int {
            val count = inner.stageTrackCount(sourceId)
            return if (sourceId == server && left.getAndDecrement() > 0) count + 1 else count
        }
    }

    private fun phoneSongs() = db.longOf("SELECT COUNT(*) FROM track WHERE sourceId = 'device'")

    @Test
    fun aServerCopyFailingOnceLeavesThePhonesScanToBeSwappedIn() = run {
        db.put(library(server, 4, msShift = 100))
        val merge = CatalogMerge(db, { Long.MAX_VALUE }, { server }, BreaksServer(1))
        val phone = db.phoneLibrary(PhoneFiles.albums(files = 30, perAlbum = 10), merge)
        // The server's copy is ready when the phone's is, so one merge claims both.
        phone.scan.reached = { if (it == ScanPoint.Ready) db.stage(library(server, 5, msShift = 100), generation = 99) }
        phone.rescan()
        assertEquals("no second scan", 1, FakeMediaStore.current!!.queries.get())
        assertEquals(30L, phoneSongs())
        assertNotNull(db.stringOf("SELECT digest FROM phone_scan_state WHERE id = 0"))
        assertEquals(4L, db.longOf("SELECT COUNT(*) FROM source_track WHERE sourceId = ?", server))
    }

    // The phone's scan and a server's copy saved together, the save failing
    // after its first group: the scan ends with no second scan, the stop's
    // one resume swaps both copies in, and the next scan settles on them.
    @Test
    fun aSaveFailingAfterItsFirstGroupIsResumedWithThePhonesCopy() = run {
        db.put(library(server, 4, msShift = 100))
        val hooks = GroupHooks()
        val broke = AtomicBoolean(false)
        hooks.inside = { turn -> if (turn == 2L && broke.compareAndSet(false, true)) throw IllegalStateException("a group broke") }
        val merge = db.boundMerge(server, SAVE_GROUP_FLOOR, hooks)
        val phone = db.phoneLibrary(PhoneFiles.albums(files = 30, perAlbum = 10), merge)
        phone.scan.reached = { if (it == ScanPoint.Ready) db.stage(library(server, 5, msShift = 100), generation = 99) }
        runCatching { phone.rescan() }
        assertTrue("the save never broke", broke.get())
        assertEquals("no second scan", 1, FakeMediaStore.current!!.queries.get())
        // A merge ask waits for the stop's resume.
        merge.rebuild()
        assertNull(db.stringOf("SELECT applyClaim FROM merge_state"))
        assertEquals(30L, phoneSongs())
        assertEquals(5L, db.longOf("SELECT COUNT(*) FROM source_track WHERE sourceId = ?", server))
        phone.rescan()
        assertEquals(30L, phoneSongs())
        assertNotNull(db.stringOf("SELECT digest FROM phone_scan_state WHERE id = 0"))
    }

    @Test
    fun aServerCopyFailingTwiceInOneMergeCostsThePhoneOneMoreScan() = run {
        db.put(library(server, 4, msShift = 100))
        lateinit var restage: Job
        lateinit var merge: CatalogMerge
        val swaps = AtomicInteger()
        val scope = this
        val hooks = object : MergeHooks() {
            override suspend fun checking() {
                // At the first check, a new server copy waits for the gate,
                // so it is ready before the merge claims again.
                if (swaps.incrementAndGet() == 1) {
                    restage = scope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        merge.sourceWrite { db.stage(library(server, 5, msShift = 100), generation = 98) }
                    }
                }
            }
        }
        merge = CatalogMerge(db, { Long.MAX_VALUE }, { server }, BreaksServer(2)).also { it.hooks = hooks }
        val phone = db.phoneLibrary(PhoneFiles.albums(files = 30, perAlbum = 10), merge)
        var staged = false
        phone.scan.reached = {
            if (it == ScanPoint.Ready && !staged) {
                staged = true
                db.stage(library(server, 5, msShift = 100), generation = 99)
            }
        }
        phone.rescan()
        restage.join()
        assertEquals("the scan and its one more", 2, FakeMediaStore.current!!.queries.get())
        assertEquals(30L, phoneSongs())
        assertNotNull(db.stringOf("SELECT digest FROM phone_scan_state WHERE id = 0"))
    }
}

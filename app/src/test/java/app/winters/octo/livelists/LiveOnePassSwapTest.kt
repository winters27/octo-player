package app.winters.octo.livelists

import app.winters.octo.catalog.HoldKind
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.listening.growAndSwap
import app.winters.octo.listening.longOf
import app.winters.octo.listening.mergedLibrary
import app.winters.octo.listening.timed
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QuerySort
import app.winters.octo.sort.SongSort
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A swap at page 7 of a walk: the walk never takes the merge lock, ends in
// one pass and publishes, storing the swap count it began at, so a copy of
// it is dated before the swap; the swap's own change of the library walks
// the list again, which leaves it today's with the new count. Guard: the
// base read in memory, with no swap count.
@RunWith(RobolectricTestRunner::class)
class LiveOnePassSwapTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aSwapMidWalkIsWalkedAgainAfter() = timed(90) {
        val merge = db.mergedLibrary(300)
        val rig = LiveRig(db, merge, page = 13).started()
        val query = LibraryQuery(sort = QuerySort(SongSort.Title.id, true))
        val before = db.longOf("SELECT swapCount FROM merge_state")
        var heldLock = false
        var swapped = false
        rig.picks.hooks = object : LiveHooks() {
            override suspend fun page(owner: String, n: Int) {
                if (merge.holdsMergeLock()) heldLock = true
                if (n == 7 && !swapped) {
                    swapped = true
                    db.growAndSwap(merge, 330)
                }
            }
        }
        coroutineScope {
            val watching = Watching(this, rig.picks, WorkOwners.marked("live:s"), "s", MutableStateFlow(query))
            watching.after(0)
            assertEquals("the walk stored the count it began at", before, db.longOf("SELECT swapCount FROM live_state WHERE owner = ?", WorkOwners.marked("live:s")))
            val now = db.longOf("SELECT swapCount FROM merge_state")
            assertTrue(now > before)
            watching.until { rig.rows(WorkOwners.marked("live:s")) == rig.today(query).map { it.id } && db.longOf("SELECT swapCount FROM live_state WHERE owner = ?", WorkOwners.marked("live:s")) == now }
            assertEquals(330, rig.rows(WorkOwners.marked("live:s")).size)
            assertFalse("a walk held the merge lock", heldLock)
            watching.job.cancel()
        }
        // Nothing a walk does holds a swap up: a Read hold enters at once.
        merge.betweenRebuilds(HoldKind.Read) { }
    }
}

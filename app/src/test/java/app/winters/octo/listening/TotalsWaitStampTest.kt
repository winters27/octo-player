package app.winters.octo.listening

import app.winters.octo.catalog.HoldKind
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

// A reader waits only for its own stamp, never for a rebuild a save's
// groups need not: a rebuild meets a group after every page of its diff,
// each group keeps the totals of what it touched and marks them built
// through its stamp at once, so a reader that starts meanwhile reads at once,
// and the rebuild ends in one pass with no other after it.
@RunWith(RobolectricTestRunner::class)
class TotalsWaitStampTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aReaderNeverWaitsForARebuild() = timed(20) {
        val merge = db.mergedLibrary(30)
        repeat(10) { db.playOn("device:s$it", NOW - it) }
        val rig = TotalsRig(db, merge, page = 2).started()
        val swaps = AtomicInteger(0)
        val opened = AtomicInteger()
        val first = Gate()
        rig.totals.hooks = object : TotalsHooks() {
            override suspend fun opened() {
                if (opened.incrementAndGet() == 1) first.hit()
            }

            override suspend fun page(stream: PlayStream) {
                if (opened.get() == 1 && swaps.get() < 6) db.growAndSwap(merge, 32 + swaps.getAndIncrement())
            }
        }
        db.playOn("device:s3", NOW + 5)
        rig.totals.rebuild()
        first.reached.await()
        coroutineScope {
            val applying = rig.totals.applying.value
            first.open()
            // Groups land on the rebuild's pages; a reader starting after
            // the first reads at once, built through its own stamp.
            rig.totals.builtThrough.first { it >= merge.swapStamp.value && swaps.get() > 0 }
            val reader = async { rig.totals.read(HoldKind.Read) { rig.dao.totalOf("device:s1")?.plays } }
            assertEquals(1, reader.await())
            rig.totals.applying.first { it >= applying + 2 }
        }
        assertEquals(6, swaps.get())
        assertEquals("one rebuild, no second", 1, opened.get())
        assertTrue(rig.totals.builtThrough.value >= merge.swapStamp.value)
        assertEquals(2, rig.dao.totalOf("device:s3")?.plays)
    }
}

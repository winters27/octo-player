package app.winters.octo.listening

import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.HoldKind
import app.winters.octo.catalog.stableRead
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

// Every restart-on-swap loop ends, even with a swap after every page: a
// walk of History and a read of the totals each end in three passes, the
// last inside betweenRebuilds; a rebuild of the totals ends in one pass
// every time, and the totals are built through the last swap once the
// swaps stop.
@RunWith(RobolectricTestRunner::class)
class ThreeAttemptTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    // Runs a skipped merge after every page, except inside the merge lock,
    // where no swap can land.
    private class SwapEveryPage(private val merge: CatalogMerge) : PlayWalkHooks() {
        override suspend fun page(stream: PlayStream) {
            if (!merge.holdsMergeLock()) merge.rebuild()
        }
    }

    private suspend fun played(): CatalogMerge {
        val merge = db.mergedLibrary(30)
        repeat(20) { db.playOn("device:s$it", NOW - it * DAY) }
        return merge
    }

    @Test
    fun historysRangedWalkEndsInThreePasses() = timed {
        val merge = played()
        val passes = AtomicInteger()
        var lastInside = false
        val top = stableRead(merge, HoldKind.Walk) {
            passes.incrementAndGet()
            lastInside = merge.holdsMergeLock()
            mostPlayedSince(db.playTotalsDao(), null, NOW - 28 * DAY, 100, SwapEveryPage(merge), page = 2)
        }
        assertEquals(3, passes.get())
        assertTrue(lastInside)
        assertEquals(TodayPlays(db, null).mostPlayed(NOW - 28 * DAY, 100).map { it.track.id }, top.map { it.track.id })
    }

    @Test
    fun aTotalsReadEndsInThreePassesHoldingTheTotals() = timed {
        val merge = played()
        val rig = TotalsRig(db, merge).started()
        val passes = AtomicInteger()
        var lastInside = false
        val shelves = rig.totals.read(HoldKind.Walk) {
            passes.incrementAndGet()
            lastInside = merge.holdsMergeLock()
            shelvesOf(rig.dao, NOW, SwapEveryPage(merge), page = 2)
        }
        assertEquals(3, passes.get())
        assertTrue(lastInside)
        assertEquals(TodayPlays(db, "home").shelves(NOW).ids(), shelves.ids())
    }

    @Test
    fun aRebuildEndsInOnePassAndTheTotalsCatchUp() = timed(20) {
        val merge = played()
        val rig = TotalsRig(db, merge, page = 3)
        val swaps = AtomicInteger(4)
        val opened = AtomicInteger()
        val applied = AtomicInteger()
        rig.totals.hooks = object : TotalsHooks() {
            override suspend fun opened() {
                opened.incrementAndGet()
            }

            // A swap that changes the library after each of the first pages.
            override suspend fun page(stream: PlayStream) {
                if (swaps.getAndDecrement() > 0) db.growAndSwap(merge, 30 + 4 - swaps.get())
            }

            override suspend fun writing(write: TotalsWrite) {
                if (write == TotalsWrite.Apply) applied.incrementAndGet()
            }
        }
        rig.started()
        rig.totals.builtThrough.first { it >= merge.swapStamp.value && swaps.get() <= 0 }
        rig.built()
        // The rebuild opened once and applied once, and the saves that
        // crossed it asked none of their own: their groups kept the totals.
        assertEquals(opened.get(), applied.get())
        assertEquals("rebuilds", 1, opened.get())
        assertEquals(merge.swapStamp.value, rig.totals.builtThrough.value)
        assertEquals(TodayPlays(db, "home").tracks().associate { it.track.id to it.plays }, db.query("SELECT trackId, plays FROM play_total WHERE plays > 0", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
        })
    }
}

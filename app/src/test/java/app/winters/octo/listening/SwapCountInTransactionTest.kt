package app.winters.octo.listening

import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// A save's group that lands just before a rebuild's diff page begins keeps
// its songs' totals itself; the rebuild goes on in one pass, counting its
// diff's songs again as it applies them, and asks for no other: the totals
// are built through the group's stamp, and are today's.
@RunWith(RobolectricTestRunner::class)
class SwapCountInTransactionTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aGroupJustBeforeADiffPageNeedsNoSecondRebuild() = timed(20) {
        val merge = db.mergedLibrary(30)
        repeat(10) { db.playOn("device:s$it", NOW - it) }
        val rig = TotalsRig(db, merge, page = 2).started()
        val began = merge.swapStamp.value
        val opened = AtomicInteger()
        val swapBeforeBegin = AtomicBoolean(true)
        rig.totals.hooks = object : TotalsHooks() {
            override suspend fun opened() {
                opened.incrementAndGet()
            }

            override suspend fun beforeDiffWrite() {
                if (swapBeforeBegin.getAndSet(false)) db.growAndSwap(merge, 31)
            }
        }
        repeat(10) { db.playOn("device:s$it", NOW + it) }
        val applying = rig.totals.applying.value
        rig.totals.rebuild()
        rig.totals.applying.first { it >= applying + 2 }
        assertTrue(merge.swapStamp.value > began)
        rig.totals.builtThrough.first { it >= merge.swapStamp.value }
        assertEquals("one rebuild, no second", 1, opened.get())
        assertEquals(TodayPlays(db, "home").tracks().associate { it.track.id to it.plays }, db.query("SELECT trackId, plays FROM play_total", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
        })
    }
}

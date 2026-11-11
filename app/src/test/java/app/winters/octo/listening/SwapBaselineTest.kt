package app.winters.octo.listening

import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// A save's group that lands right after a rebuild's opening and before its
// first page keeps the totals of the songs it touched itself, and the
// rebuild counts each song of its diff again as it applies it: the rebuild
// ends in one pass, asks for no other, and the totals are today's.
@RunWith(RobolectricTestRunner::class)
class SwapBaselineTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aGroupRightAfterTheOpeningNeedsNoSecondRebuild() = timed {
        val merge = db.mergedLibrary(30)
        repeat(10) { db.playOn("device:s$it", NOW - it) }
        val rig = TotalsRig(db, merge).started()
        val opened = AtomicInteger()
        val swapAfterOpening = AtomicBoolean(true)
        rig.totals.hooks = object : TotalsHooks() {
            override suspend fun opened() {
                opened.incrementAndGet()
                if (swapAfterOpening.getAndSet(false)) db.growAndSwap(merge, 31)
            }
        }
        db.playOn("device:s1", NOW + 1)
        val applying = rig.totals.applying.value
        rig.totals.rebuild()
        rig.totals.applying.first { it >= applying + 2 }
        rig.totals.builtThrough.first { it >= merge.swapStamp.value }
        rig.built()
        assertEquals("one rebuild, no second", 1, opened.get())
        assertEquals(TodayPlays(db, "home").tracks().associate { it.track.id to it.plays }, db.query("SELECT trackId, plays FROM play_total", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
        })
    }
}

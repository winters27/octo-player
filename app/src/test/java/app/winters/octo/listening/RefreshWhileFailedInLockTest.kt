package app.winters.octo.listening

import app.winters.octo.catalog.HoldKind
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

// A refresh asked for inside betweenRebuilds while the totals failed (as a
// sent play landing during a sync's step asks) returns at once: the full
// rebuild it becomes runs on the totals' own runner, outside the merge
// lock, never in the caller.
@RunWith(RobolectricTestRunner::class)
class RefreshWhileFailedInLockTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aRefreshInsideTheLockOnlyAsks() = timed {
        db.fillPlays(seed = 81, songs = 100, albums = 5)
        val merge = db.testMerge(HOME)
        db.playOn("t00001", NOW + 1)
        val plays = db.longOf("SELECT COUNT(*) FROM play_event WHERE trackId = 't00001'")
        val rig = TotalsRig(db, merge, space = 0)
        val opened = AtomicInteger()
        var underLock = false
        rig.totals.hooks = object : TotalsHooks() {
            override suspend fun opened() {
                opened.incrementAndGet()
                if (merge.holdsMergeLock()) underLock = true
            }
        }
        rig.totals.start()
        rig.totals.failed.first { it }
        rig.space = Long.MAX_VALUE
        val walks = opened.get()
        merge.betweenRebuilds(HoldKind.Page) {
            rig.totals.refresh(listOf("t00001"))
        }
        rig.totals.failed.first { !it }
        assertEquals(walks + 1, opened.get())
        assertFalse(underLock)
        assertEquals(plays, db.longOf("SELECT phonePlays FROM play_total WHERE trackId = 't00001'"))
    }
}

package app.winters.octo.listening

import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Until the totals are first built in a process, Home's shelves and
// History's all-time list give nothing, so they show they are loading,
// never an empty shelf; once built they give the whole shelves.
@RunWith(RobolectricTestRunner::class)
class ShelvesLoadingTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun nothingShowsBeforeTheFirstRebuild() = timed {
        db.fillPlays(seed = 41, songs = 300, albums = 20)
        val first = Gate()
        val rig = TotalsRig(db, db.testMerge(HOME))
        rig.totals.hooks = object : TotalsHooks() {
            override suspend fun opened() = first.hit()
        }
        rig.totals.start()
        first.reached.await()
        assertNull(withTimeoutOrNull(500) { rig.history.shelves.first() })
        assertNull(withTimeoutOrNull(200) { rig.history.mostPlayed(null, 100).first() })
        assertEquals(-1L, rig.totals.builtThrough.value)
        first.open()
        assertEquals(TodayPlays(db, "home").shelves(NOW).ids(), value(rig.history.shelves.first()).ids())
    }
}

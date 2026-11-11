package app.winters.octo.ui.library.health

import app.winters.octo.listening.growAndSwap
import app.winters.octo.listening.longOf
import app.winters.octo.listening.mergedLibrary
import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A swap at page 7 of a health build: the build never takes the merge lock,
// ends in one pass and publishes, storing the swap count it began at; the
// report shown before stays until then, and the next build reads the new
// library and stores the new count. Guard: the base checked in memory,
// with no swap count.
@RunWith(RobolectricTestRunner::class)
class HealthOnePassSwapTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aSwapMidBuildIsBuiltAgainAfter() = timed(90) {
        val merge = db.mergedLibrary(300)
        val rig = HealthRig(db, merge, page = 13).keys()
        val first = rig.built()
        assertEquals(300, first.checked)
        val before = db.longOf("SELECT swapCount FROM merge_state")
        var heldLock = false
        var shownMidway: Long? = null
        var swapped = false
        rig.build.hooks = object : HealthHooks() {
            override suspend fun page(n: Int) {
                if (merge.holdsMergeLock()) heldLock = true
                if (n == 7 && !swapped) {
                    swapped = true
                    shownMidway = rig.build.shown()?.gen
                    db.growAndSwap(merge, 330)
                }
            }
        }
        val crossed = rig.built()
        assertEquals("readers kept the report shown before", first.gen, shownMidway)
        assertTrue(crossed.gen > first.gen)
        assertEquals("the build stored the count it began at", before, crossed.swapCount)
        assertFalse("a build held the merge lock", heldLock)
        val after = rig.built()
        assertEquals(330, after.checked)
        assertEquals(db.longOf("SELECT swapCount FROM merge_state"), after.swapCount)
        // The reports before are gone once a newer one is published.
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM health_song WHERE gen < ?", after.gen))
    }
}

package app.winters.octo.ui.library.health

import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A fix that finds the set it was asked for gone (the report went stale)
// has the report built again at once, with no change to the library, so
// the pages showing it read the new one and the line goes. The base's
// report was built only on a change of the library.
@RunWith(RobolectricTestRunner::class)
class HealthGoneBuildsTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aSetFoundGoneBuildsTheReportAgain() = timed(60) {
        db.healthLibrary(albums = 10)
        val rig = HealthRig(db).keys()
        val recheck = HealthRecheck(db, rig.merge)
        // Shared only while a page looks, so no build outlives the database.
        val pages = HealthPages(rig.build, recheck, keepMillis = 0)
        coroutineScope {
            val shown = launch { pages.shown.collect {} }
            val first = pages.shown.first { it is HealthShown.Shown } as HealthShown.Shown
            recheck.missing()
            val next = withTimeoutOrNull(10_000) { pages.shown.first { (it as? HealthShown.Shown)?.gen?.let { gen -> gen > first.gen } == true } }
            assertNotNull("no build after a set was found gone", next)
            assertTrue((next as HealthShown.Shown).counts == first.counts)
            shown.cancel()
        }
    }
}

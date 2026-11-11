package app.winters.octo.ui.library.health

import app.winters.octo.listening.longOf
import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// With no merge at all (no audio permission, no server) and copies whose
// song keys are stale at open, Library health waits for the keys, shows
// its loading state meanwhile, and builds once the refill the app runs at
// start makes them current. Guard: the base refilled keys only inside a
// merge, so here it never would.
@RunWith(RobolectricTestRunner::class)
class HealthKeysAtStartTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun theReportLoadsWithNoMerge() = timed(60) {
        db.healthLibrary(albums = 10)
        val rig = HealthRig(db)
        val shown = MutableStateFlow<HealthShown?>(null)
        coroutineScope {
            val watching = launch { rig.build.watch().collect { shown.value = it } }
            shown.first { it != null }
            repeat(20) { yield() }
            assertEquals(HealthShown.Loading, shown.value)
            assertFalse(rig.merge.keysReady.value)
            assertEquals(0L, db.longOf("SELECT COUNT(*) FROM health_run"))
            rig.merge.start()
            val report = shown.first { it is HealthShown.Shown } as HealthShown.Shown
            assertEquals(db.longOf("SELECT COUNT(*) FROM track").toInt(), report.checked)
            watching.cancel()
        }
    }
}

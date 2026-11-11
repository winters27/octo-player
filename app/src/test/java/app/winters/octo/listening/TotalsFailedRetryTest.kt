package app.winters.octo.listening

import android.database.sqlite.SQLiteFullException
import app.winters.octo.catalog.HoldKind
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean

// Totals that failed in the middle of an apply load again: the failure is
// kept with its words and `applying` stays odd, so readers end with the
// words instead of reading the half-applied table; a swap that moved no
// row walks instead of taking the fast path; the app coming to the front
// and Try again each start a rebuild, and once there is room readers load.
@RunWith(RobolectricTestRunner::class)
class TotalsFailedRetryTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private val words = "Not enough space to update play counts. Free up some space, then try again."

    @Test
    fun failedTotalsBuildAgainOnTheFrontAndOnTryAgain() = timed(20) {
        val merge = db.mergedLibrary(30)
        repeat(10) { db.playOn("device:s$it", NOW - it) }
        val rig = TotalsRig(db, merge, page = 2).started()
        // Rebuilds that failed in their apply, so far.
        val failures = MutableStateFlow(0)
        val full = AtomicBoolean(true)
        rig.totals.hooks = object : TotalsHooks() {
            override suspend fun applied() {
                if (full.get()) {
                    failures.value += 1
                    throw SQLiteFullException("database or disk is full")
                }
            }
        }
        repeat(10) { db.playOn("device:s$it", NOW + it) }
        rig.totals.rebuild()
        rig.totals.failed.first { it }
        assertEquals(1L, rig.totals.applying.value % 2)
        assertEquals(words, rig.totals.problem.value)
        assertEquals(TotalsAnswer.Problem(words), answer { rig.totals.read(HoldKind.Read) { "read" } })
        assertEquals(TotalsAnswer.Problem(words), rig.history.shelves.first())

        // A skipped merge's stamp move walks while the totals failed.
        merge.rebuild()
        assertEquals(4L, merge.swapStamp.value)
        failures.first { it >= 2 }
        assertEquals(2L, rig.totals.builtThrough.value)
        // Coming to the front again tries again.
        rig.totals.foreground()
        failures.first { it >= 3 }
        assertTrue(rig.totals.failed.value)

        // Try again, with room: the totals load.
        full.set(false)
        rig.totals.tryAgain()
        rig.totals.failed.first { !it }
        assertEquals(0L, rig.totals.applying.value % 2)
        assertEquals(TodayPlays(db, "home").tracks().associate { it.track.id to it.plays }, db.query("SELECT trackId, plays FROM play_total", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
        })
        assertEquals("read", value(answer { rig.totals.read(HoldKind.Read) { "read" } }))
    }
}

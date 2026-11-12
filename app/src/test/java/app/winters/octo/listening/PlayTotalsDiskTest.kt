package app.winters.octo.listening

import android.database.sqlite.SQLiteFullException
import app.winters.octo.catalog.HoldKind
import app.winters.octo.catalog.megabytes
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean

// A full disk never stops the app or leaves readers on a half-built table:
// short of room before the first build, readers get the play-count words
// and load once there is room; a full disk in the diff keeps the last good
// totals, which readers go on reading; one in the apply keeps `applying`
// odd and readers off the table, with the words; the next try rebuilds.
@RunWith(RobolectricTestRunner::class)
class PlayTotalsDiskTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private val full = "Not enough space to update play counts. Free up some space, then try again."

    private fun stored(): Map<String, Int> = db.query("SELECT trackId, plays FROM play_total", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
    }

    @Test
    fun shortOfRoomBeforeTheFirstBuild() = timed {
        db.fillPlays(seed = 91, songs = 300, albums = 10)
        val rig = TotalsRig(db, db.testMerge(HOME), space = 10)
        rig.totals.start()
        rig.totals.failed.first { it }
        val needed = (db.longOf("SELECT COUNT(*) FROM track") + 0) * PLAY_TOTAL_DIFF_BYTES_PER_ROW * 2
        val words = "Not enough space to update play counts. Free up ${megabytes(needed)} MB, then try again."
        assertEquals(words, rig.totals.problem.value)
        assertEquals(-1L, rig.totals.builtThrough.value)
        assertEquals(emptyMap<String, Int>(), stored())
        assertEquals(TotalsAnswer.Problem(words), rig.history.shelves.first())
        rig.space = Long.MAX_VALUE
        rig.totals.tryAgain()
        rig.totals.builtThrough.first { it >= 0 }
        assertEquals(TodayPlays(db, "home").shelves(NOW).ids(), value(rig.history.shelves.first { it is TotalsAnswer.Value }).ids())
    }

    @Test
    fun aFullDiskInTheDiffKeepsTheLastGoodTotals() = timed {
        db.fillPlays(seed = 92, songs = 300, albums = 10)
        val rig = TotalsRig(db, db.testMerge(HOME), page = 5).started()
        val good = stored()
        val failing = AtomicBoolean(true)
        rig.totals.hooks = object : TotalsHooks() {
            override suspend fun inDiffWrite() {
                if (failing.get()) throw SQLiteFullException("database or disk is full")
            }
        }
        repeat(20) { db.playOn("t%05d".format(it), NOW + it) }
        rig.totals.rebuild()
        rig.totals.failed.first { it }
        assertEquals(full, rig.totals.problem.value)
        assertEquals(good, stored())
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM play_total_diff"))
        assertEquals(0L, rig.totals.applying.value % 2)
        // The last good totals stay readable.
        assertEquals(good["t00000"], rig.totals.read(HoldKind.Read) { rig.dao.totalOf("t00000")?.plays })
        failing.set(false)
        rig.totals.tryAgain()
        rig.totals.failed.first { !it }
        assertEquals(TodayPlays(db, "home").tracks().associate { it.track.id to it.plays }, stored().filterValues { it > 0 })
    }

    @Test
    fun aFullDiskInTheApplyKeepsReadersOff() = timed {
        db.fillPlays(seed = 93, songs = 300, albums = 10)
        val rig = TotalsRig(db, db.testMerge(HOME), page = 5).started()
        val failing = AtomicBoolean(true)
        rig.totals.hooks = object : TotalsHooks() {
            override suspend fun applied() {
                if (failing.get()) throw SQLiteFullException("database or disk is full")
            }
        }
        repeat(20) { db.playOn("t%05d".format(it), NOW + it) }
        rig.totals.rebuild()
        rig.totals.failed.first { it }
        assertEquals(1L, rig.totals.applying.value % 2)
        assertEquals(TotalsAnswer.Problem(full), answer { rig.totals.withReadHold { "held" } })
        assertEquals(TotalsAnswer.Problem(full), rig.history.shelves.first())
        failing.set(false)
        rig.totals.tryAgain()
        rig.totals.failed.first { !it }
        assertEquals(0L, rig.totals.applying.value % 2)
        assertTrue(rig.history.shelves.first { it is TotalsAnswer.Value } is TotalsAnswer.Value)
        assertEquals(TodayPlays(db, "home").tracks().associate { it.track.id to it.plays }, stored().filterValues { it > 0 })
    }
}

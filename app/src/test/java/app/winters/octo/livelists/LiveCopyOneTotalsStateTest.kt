package app.winters.octo.livelists

import app.winters.octo.catalog.WorkOwners
import app.winters.octo.listening.NOW
import app.winters.octo.listening.TotalsHooks
import app.winters.octo.listening.TotalsWrite
import app.winters.octo.listening.playOn
import app.winters.octo.listening.timed
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QuerySort
import app.winters.octo.sort.SongSort
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

// A queue fill's copy of a live list that has nothing shown to copy (a
// fill resumed after the work tables were emptied) walks the list's rules
// itself, and a list ordered by plays holds the play counts still for that
// whole walk: a rebuild of the totals asked for at page 2 waits, its apply
// lands only after the copy is published, and the copy is in the order of
// the totals as they were when it began. Guard: the base had no copy.
@RunWith(RobolectricTestRunner::class)
class LiveCopyOneTotalsStateTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aWalkingCopyIsOneStateOfThePlayCounts() = timed(60) {
        val tracks = db.liveLibrary(120)
        val rig = LiveRig(db, page = 10).started()
        val query = LibraryQuery(sort = QuerySort(SongSort.MostPlayed.id, true))
        val before = rig.today(query).map { it.id }
        val events = CopyOnWriteArrayList<String>()
        val writeWaits = CompletableDeferred<Unit>()
        val applied = CompletableDeferred<Unit>()
        val totals = rig.totals.totals
        totals.holds.writeWaits = {
            events += "write waits"
            writeWaits.complete(Unit)
        }
        totals.hooks = object : TotalsHooks() {
            override suspend fun writing(write: TotalsWrite) {
                events += "write $write"
                if (write == TotalsWrite.Apply) applied.complete(Unit)
            }
        }
        rig.picks.hooks = object : LiveHooks() {
            override suspend fun page(owner: String, n: Int) {
                events += "page $n"
                if (n == 2) {
                    // The quietest song becomes the most played.
                    repeat(40) { db.playOn(tracks[1].id, NOW - it * 1_000L) }
                    totals.rebuild()
                    writeWaits.await()
                }
            }

            override suspend fun published(owner: String, gen: Long) {
                events += "published"
            }
        }
        val copy = rig.picks.copy(null, query, WorkOwners.marked("queue:1"))
        assertEquals(before.size, copy.count)
        assertEquals(before, rig.rows(WorkOwners.marked("queue:1")))
        applied.await()
        rig.totals.built()
        assertTrue("$events", events.indexOf("write Apply") > events.indexOf("published"))
        assertTrue("$events", events.indexOf("write waits") in 0 until events.indexOf("published"))
        assertTrue("the play changed nothing", rig.today(query).map { it.id } != before)
    }
}

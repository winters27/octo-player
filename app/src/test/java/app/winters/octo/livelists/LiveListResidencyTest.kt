package app.winters.octo.livelists

import app.winters.octo.catalog.WorkOwners
import app.winters.octo.listening.timed
import app.winters.octo.playback.putTracks
import app.winters.octo.playback.radioTrack
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QuerySort
import app.winters.octo.sort.SongSort
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A live list of 20,000 songs, sorted, and one limited to 500, hold a page:
// no statement reads more than 900 rows (counted at the database, below
// Room) or binds more than 900 values. The base read every song at once.
@RunWith(RobolectricTestRunner::class)
class LiveListResidencyTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aLiveListHoldsAPage() = timed(180) {
        db.putTracks((0 until 20_000).map { radioTrack("t%05d".format(it), sortKey = "k%05d".format(20_000 - it), title = "Song ${it % 977}", year = 1950 + it % 70) })
        val rig = LiveRig(db).started()
        db.counted.reset()
        coroutineScope {
            val all = Watching(this, rig.picks, WorkOwners.marked("live:a"), "a", MutableStateFlow(LibraryQuery(sort = QuerySort(SongSort.Title.id))))
            assertEquals(20_000, (all.after(0) as LiveShown.Shown).count)
            val top = Watching(this, rig.picks, WorkOwners.marked("live:t"), "t", MutableStateFlow(LibraryQuery(sort = QuerySort(SongSort.Year.id, true), limit = 500)))
            assertEquals(500, (top.after(0) as LiveShown.Shown).count)
            all.job.cancel()
            top.job.cancel()
        }
        assertTrue("${db.counted.maxRead}", db.counted.maxRead!!.rowsRead <= 900)
        assertTrue("${db.counted.maxBound}", db.counted.maxBound!!.boundArgs <= 900)
    }
}

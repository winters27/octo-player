package app.winters.octo.livelists

import app.winters.octo.catalog.WorkOwners
import app.winters.octo.listening.longOf
import app.winters.octo.listening.timed
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QueryField
import app.winters.octo.query.QueryOp
import app.winters.octo.query.QueryRule
import app.winters.octo.query.QuerySort
import app.winters.octo.sort.SongSort
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A change of a list's rules builds the new list beside the shown one:
// readers read the old rules' songs until the new generation is whole,
// then the new, never half of each, and the old generation's rows go.
// Guard: the base worked the whole list out in memory at once.
@RunWith(RobolectricTestRunner::class)
class LiveListRulesChangeTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun readersKeepTheOldListUntilTheFlip() = timed(60) {
        db.liveLibrary(150)
        val rig = LiveRig(db, page = 11).started()
        val first = LibraryQuery(sort = QuerySort(SongSort.Title.id))
        val second = LibraryQuery(rules = listOf(QueryRule(QueryField.Genre, QueryOp.Is, text = "rock")), sort = QuerySort(SongSort.Year.id, true))
        val oldRows = rig.today(first).map { it.id }
        val seenMidway = ArrayList<List<String>>()
        coroutineScope {
            val query = MutableStateFlow(first)
            val watching = Watching(this, rig.picks, WorkOwners.marked("live:r"), "r", query)
            val before = watching.after(0) as LiveShown.Shown
            rig.picks.hooks = object : LiveHooks() {
                override suspend fun page(owner: String, n: Int) {
                    if (n == 3 && seenMidway.isEmpty()) seenMidway += rig.rows(WorkOwners.marked("live:r"))
                }
            }
            query.value = second
            val after = watching.until { (it as? LiveShown.Shown)?.gen != before.gen } as LiveShown.Shown
            assertEquals(listOf(oldRows), seenMidway)
            assertEquals(rig.today(second).map { it.id }, rig.rows(WorkOwners.marked("live:r")))
            assertTrue(after.gen > before.gen)
            assertEquals(0L, db.longOf("SELECT COUNT(*) FROM live_song WHERE owner = ? AND gen = ?", WorkOwners.marked("live:r"), before.gen))
            watching.job.cancel()
        }
    }
}

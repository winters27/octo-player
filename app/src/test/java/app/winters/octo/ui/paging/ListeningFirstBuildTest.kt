package app.winters.octo.ui.paging

import androidx.paging.LoadState
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.listening.NOW
import app.winters.octo.listening.TotalsRig
import app.winters.octo.listening.mergedLibrary
import app.winters.octo.listening.playOn
import app.winters.octo.listening.timed
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.Presented
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A list by plays opened before the play totals' first build shows its
// loading state, never an empty list or every song as unplayed, and its
// rows by plays once the build lands. Title, which reads no totals, shows
// at once meanwhile.
@RunWith(RobolectricTestRunner::class)
class ListeningFirstBuildTest {
    private val db = openTestCatalog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        // The list's shared pages stop before the database closes.
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        db.close()
    }

    @Test
    fun mostPlayedWaitsForTheFirstBuildThenShowsItsRows() = timed(60) {
        val merge = db.mergedLibrary(40)
        repeat(12) { i -> repeat(1 + i % 4) { db.playOn("device:s$i", NOW - i * 1_000L - it) } }
        val rig = TotalsRig(db, merge)
        val reads = LibraryReads(merge, rig.totals)
        val most = ListHandle<SortOrder, TrackEntity>(scope, SortOrder(SongSort.MostPlayed, true), { SongLists(db.listsDao()).loader(SongScope.All, it) }, reads, { _, l -> db.tableChanges(l.tables) })
        val title = ListHandle<SortOrder, TrackEntity>(scope, SortOrder(SongSort.Title, false), { SongLists(db.listsDao()).loader(SongScope.All, it) }, reads, { _, l -> db.tableChanges(l.tables) })
        Presented(most.pages, scope).use { byPlays ->
            Presented(title.pages, scope).use { byTitle ->
                byTitle.settle()
                assertEquals("Title reads no totals and shows at once", 40, byTitle.size())
                delay(300)
                assertEquals("nothing shown before the first build", 0, byPlays.size())
                assertNull("no count published", most.shown.value)
                assertTrue("the list shows its loading state", byPlays.presenter.loadStateFlow.value?.refresh is LoadState.Loading)

                rig.totals.start()
                byPlays.settle(until = { most.presented >= 1 })
                assertEquals(40, byPlays.size())
                val rows = (0 until 40).map { byPlays.get(it)!!.row.id }
                // Most plays first, of equal plays the latest, then the rest.
                val playedFirst = (0 until 12).sortedWith(compareByDescending<Int> { 1 + it % 4 }.thenBy { it }).map { "device:s$it" }
                assertEquals(playedFirst, rows.take(12))
                assertEquals(40, most.metaFor(most.presented)!!.count)
            }
        }
    }
}

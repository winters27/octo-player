package app.winters.octo.ui.paging

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.listening.timed
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.Presented
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.common.railStops
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A tap on the letter rail lands on the letter's first row: the rail's stop
// (a lazy index with a heading before each run) goes back to the run's
// start, a jump past the loaded rows reads around that place by one probe,
// and the row there is the first under the letter in today's order, both
// ways, for a letter that comes back ("#") as well.
@RunWith(RobolectricTestRunner::class)
class RailJumpTest {
    private val db = openTestCatalog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        // The list's shared pages stop before the database closes.
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        db.close()
    }

    @Test
    fun aJumpLandsOnItsLettersFirstRow() = timed(60) {
        val titles = EDGE_TITLES + ('A'..'Z').map { "$it song" } + ('a'..'z').map { "$it tune" }
        db.edgeSongs(5_000, seed = 3, titles = titles)
        for (down in listOf(false, true)) {
            val order = SortOrder(SongSort.Title, down)
            val today = db.todaySongs(SongSort.Title, down, SongScope.All)
            val handle = ListHandle<SortOrder, TrackEntity>(scope, order, { SongLists(db.listsDao()).loader(SongScope.All, it) }, ListReads.Plain, { _, l -> db.tableChanges(l.tables) })
            val anchors = java.util.concurrent.CopyOnWriteArrayList<Reanchor>()
            val watch = scope.launch { handle.reanchors.collect { anchors += it } }
            Presented(handle.pages, scope).use { shown ->
                shown.settle()
                val runs = handle.shown.value!!.runs!!
                assertEquals(today.size, shown.size())
                val stops = railStops(runs, headed = true)
                // From the far end first, then back up, so each lands past
                // the rows already read.
                val picked = listOf(stops.last(), stops[stops.size / 2], stops.first { it.letter == 'M' }, stops[1])
                for (stop in picked) {
                    val at = positionOf(stop.index, runs)
                    val run = runs.first { it.letter == stop.letter }
                    assertEquals("${stop.letter} stop goes to its run's start", run.start, at)
                    shown.get(at)
                    shown.settle()
                    val row = shown.peek(at) ?: throw AssertionError("row $at not read after the jump to ${stop.letter}")
                    assertEquals("${stop.letter} ${if (down) "desc" else "asc"}", today[at], row.row.id)
                    assertEquals(stop.letter, app.winters.octo.ui.common.indexLetter(row.row.sortKey))
                    if (at > 0) shown.peek(at - 1)?.let { assertTrue("the row before ${stop.letter} files elsewhere", app.winters.octo.ui.common.indexLetter(it.row.sortKey) != stop.letter) }
                }
            }
            watch.cancel()
            // A jump's read never moves the screen: its key is only the row
            // Paging touched last (on the phone, the row above the letter's
            // heading), so the screen keeps the place it jumped to.
            assertTrue("a jump read near its place", anchors.isNotEmpty())
            assertTrue("no jump read moves the screen: $anchors", anchors.none { it.follow })
        }
    }
}

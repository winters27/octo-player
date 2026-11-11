package app.winters.octo.ui.playlist

import app.winters.octo.catalog.NewItem
import app.winters.octo.catalog.PlaylistRowView
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.playlistOf
import app.winters.octo.listening.timed
import app.winters.octo.testing.Presented
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.paging.ListHandle
import app.winters.octo.ui.paging.ListReads
import app.winters.octo.ui.paging.PlaylistLoader
import app.winters.octo.ui.paging.Reanchor
import app.winters.octo.ui.paging.followReanchors
import app.winters.octo.ui.paging.tableChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

// A sync's write-back that shows a new set of a playlist while row 5,000 of
// 20,000 is first visible keeps that row first: a family pull keeps every
// row's key, so the next generation finds the row by it even though 100
// songs came in above it; a pull that is not the family's gives fresh keys,
// and the next generation reads around the place the row stood.
@RunWith(RobolectricTestRunner::class)
class PlaylistFlipScrollTest {
    private val db = openTestCatalog()
    private val sets = PlaylistSets(db)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        // The list's shared pages stop before the database closes.
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        db.close()
    }

    @Test
    fun aFlipMidScrollKeepsTheRowInView() = timed(120) {
        db.songs(0 until 20_100)
        sets.playlistOf("p", 20_000)
        val owners = WorkOwners()
        val handle = ListHandle<Unit, PlaylistRowView>(scope, Unit, { PlaylistLoader(db, sets, "p", owners.walk()) }, ListReads.Plain, { _, l -> db.tableChanges(l.tables) })
        val anchors = CopyOnWriteArrayList<Reanchor>()
        Presented(handle.pages, scope).use { shown ->
            scope.followReanchors(handle, shown, anchors)
            shown.settle()
            shown.get(5_000)
            shown.settle()
            val inView = shown.peek(5_000)!!
            assertEquals("song5000", inView.row.track.id)
            handle.visible(inView.key)

            // A family pull: 100 songs come in at the top, every row keeps its key.
            val before = handle.presented
            sets.writeBack(db, "p", (20_000 until 20_100).map { NewItem("song$it", "k$it") } + db.shownRows("p").kept())
            shown.settle(until = { handle.presented > before && handle.presented == handle.generationNow })
            // Generations made while the new set is written still read the
            // old one; the one after the flip finds the row at its new place.
            val family = anchors.filter { it.generation > before }
            family.forEach { assertEquals("the row is found by its key", inView.key.id, it.key.id) }
            assertEquals(5_100, family.last().index)
            assertEquals("song5000", shown.peek(5_100)!!.row.track.id)

            // A pull that is not the family's: the same songs, fresh keys.
            val beforeFresh = handle.presented
            sets.writeBack(db, "p", db.shownRows("p").map { NewItem(it.trackId, it.relinkKey) })
            shown.settle(until = { handle.presented > beforeFresh && handle.presented == handle.generationNow })
            val fresh = anchors.last { it.generation > beforeFresh }
            assertEquals("the screen keeps the row's place", 5_100, fresh.index)
            assertEquals("song5000", shown.peek(5_100)!!.row.track.id)
        }
    }
}

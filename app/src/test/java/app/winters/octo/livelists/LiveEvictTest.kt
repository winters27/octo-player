package app.winters.octo.livelists

import app.winters.octo.catalog.WorkOwners
import app.winters.octo.listening.longOf
import app.winters.octo.listening.timed
import app.winters.octo.playback.CarLive
import app.winters.octo.playback.CarNode
import app.winters.octo.playback.CarOwned
import app.winters.octo.playback.CarRig
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QuerySort
import app.winters.octo.sort.SongSort
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Four readers on four lists at once (a page, an editor's preview, a car
// page and a queue fill's copy) are all kept while they read: none is let
// go for the others, and the fill's copy holds every song. Once nothing
// reads them, LIVE_KEPT saved lists are kept, the most recently used.
// Guard: the base kept nothing.
@RunWith(RobolectricTestRunner::class)
class LiveEvictTest {
    private val db = openTestCatalog()

    // The owners in the form the app writes them, after this launch's mark.
    private fun live(n: Int) = WorkOwners.marked("live:$n")
    private val savedLists = WorkOwners.marked("live:")
    private val draft = WorkOwners.marked("draft:9")
    private val queue = WorkOwners.marked("queue:1")

    @After
    fun close() = db.close()

    @Test
    fun noListInUseIsLetGo() = timed(90) {
        db.liveLibrary(120)
        val rig = LiveRig(db, page = 17).started()
        val sorts = listOf(SongSort.Title, SongSort.Artist, SongSort.Year, SongSort.Length, SongSort.Album)
        coroutineScope {
            val readers = sorts.take(4).mapIndexed { n, sort ->
                Watching(this, rig.picks, live(n), "$n", MutableStateFlow(LibraryQuery(sort = QuerySort(sort.id))))
            }
            readers.forEach { it.after(0) }
            val editor = Watching(this, rig.picks, draft, null, MutableStateFlow(LibraryQuery(sort = QuerySort(sorts[4].id))))
            editor.after(0)
            // The list being made with these rules is the one a play of them reads.
            assertEquals(draft, rig.picks.sourceOf(null, LibraryQuery(sort = QuerySort(sorts[4].id))))
            assertEquals(4L, db.longOf("SELECT COUNT(*) FROM live_state WHERE owner LIKE '$savedLists%'"))
            readers.indices.forEach { assertEquals(rig.today(LibraryQuery(sort = QuerySort(sorts[it].id))).map { t -> t.id }, rig.rows(live(it))) }
            val copy = rig.picks.copy(live(3), LibraryQuery(sort = QuerySort(sorts[3].id)), queue)
            assertEquals(120, copy.count)
            assertEquals(rig.rows(live(3)), rig.rows(queue))
            readers.forEach { it.job.cancel() }
            editor.job.cancel()
        }
        assertEquals(LIVE_KEPT.toLong(), db.longOf("SELECT COUNT(*) FROM live_state WHERE owner LIKE '$savedLists%'"))
        assertEquals(LIVE_KEPT.toLong(), db.longOf("SELECT COUNT(DISTINCT owner) FROM live_song WHERE owner LIKE '$savedLists%'"))
        // The copy and the preview stay until they are cleared.
        assertEquals(1L, db.longOf("SELECT COUNT(*) FROM live_state WHERE owner = '$queue'"))
        rig.picks.clear(queue)
        rig.picks.clear(draft)
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM live_song WHERE owner IN ('$queue', '$draft')"))
    }

    // The car's reader keeps its list under an owner of its own while the
    // car keeps it: pages of more lists than LIVE_KEPT opening and closing
    // never push it out, it holds the whole list, and it goes once the car
    // lets it go.
    @Test
    fun theCarsListStaysWhilePagesComeAndGoAndGoesWithTheCar() = timed(90) {
        CarRig().use { car ->
            car.db.liveLibrary(120)
            val query = LibraryQuery(sort = QuerySort(SongSort.Year.id))
            car.liveLists.value = mapOf("c" to query)
            val node = CarNode.LiveList("c")
            car.answer(node)
            val owner = car.lists.keptFor<CarOwned<CarLive>>(node)!!.owner
            fun carRows() = car.db.longOf("SELECT COUNT(*) FROM live_song WHERE owner = '$owner'")
            coroutineScope {
                listOf(SongSort.Title, SongSort.Artist, SongSort.Album, SongSort.Length, SongSort.RecentlyAdded).forEachIndexed { n, sort ->
                    val page = Watching(this, car.live, live(n), "$n", MutableStateFlow(LibraryQuery(sort = QuerySort(sort.id))))
                    page.after(0)
                    page.job.cancel()
                }
            }
            assertEquals(LIVE_KEPT.toLong(), car.db.longOf("SELECT COUNT(*) FROM live_state WHERE owner LIKE '$savedLists%'"))
            assertEquals(120L, carRows())
            val lists = LiveRig(car.db, car.merge)
            assertEquals(lists.today(query).map { it.id }, lists.rows(owner))
            car.lists.clear()
            withTimeout(10_000) { while (carRows() > 0L) delay(20) }
            assertEquals(0L, car.db.longOf("SELECT COUNT(*) FROM live_state WHERE owner = '$owner'"))
        }
    }
}

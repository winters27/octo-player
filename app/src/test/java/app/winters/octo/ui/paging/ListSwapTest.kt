package app.winters.octo.ui.paging

import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.HoldKind
import app.winters.octo.catalog.SourceCatalog
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.albumRow
import app.winters.octo.catalog.artistRow
import app.winters.octo.catalog.put
import app.winters.octo.catalog.song
import app.winters.octo.catalog.stableRead
import app.winters.octo.device.DEVICE
import app.winters.octo.listening.timed
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.Presented
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

// A merge in the middle of a scroll keeps the row in view: a save renames
// 1,000 songs from below the screen's first visible row to above it, a group
// at a time, so that row moves down by the songs each group renamed, 1,000
// places in all; every later generation reads around it by its key and asks
// the screen to keep it first, at its new place, with the count and the
// rows of that generation. A list re-read by place would show another row
// there.
@RunWith(RobolectricTestRunner::class)
class ListSwapTest {
    private val db = openTestCatalog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        // The list's shared pages stop before the database closes.
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        db.close()
    }

    private fun phone(renamed: Boolean) = SourceCatalog(
        DEVICE,
        true,
        (0 until 3_000).map { i ->
            val title = if (renamed && i >= 2_000) "Aaa %04d".format(i) else "Song %04d".format(i)
            song(DEVICE, "s$i", title, "album${i / 10}", no = i % 10 + 1, order = i % 10)
        },
        (0 until 300).map { albumRow(DEVICE, "album$it", "Album $it") },
        listOf(artistRow(DEVICE)),
    )

    private class Reads(private val merge: CatalogMerge) : ListReads {
        override suspend fun <T> read(totals: Boolean, prepare: suspend () -> Unit, block: suspend () -> T): T = stableRead(merge, HoldKind.Read, prepare, block)
    }

    @Test
    fun aSwapAboveTheScreenKeepsTheRowInView() = timed(90) {
        db.put(phone(renamed = false))
        val merge = db.testMerge(null).also { it.rebuild() }
        val handle = ListHandle<SortOrder, TrackEntity>(
            scope,
            SortOrder(SongSort.Title, false),
            { SongLists(db.listsDao()).loader(SongScope.All, it) },
            Reads(merge),
            { _, loader -> db.tableChanges(loader.tables) },
        )
        val anchors = CopyOnWriteArrayList<Reanchor>()
        Presented(handle.pages, scope).use { shown ->
            scope.followReanchors(handle, shown, anchors)
            shown.settle()
            shown.get(1_500)
            shown.settle()
            val inView = shown.peek(1_500)!!
            assertEquals("Song 1500", inView.row.title)
            handle.visible(inView.key)
            val before = handle.presented

            db.put(phone(renamed = true))
            merge.rebuild()
            // Until the generation that read the last group is shown.
            shown.settle(until = {
                handle.presented > before && handle.presented == handle.generationNow &&
                    anchors.lastOrNull()?.index == 2_500
            })

            val now = handle.presented
            // The save lands a group at a time, so a generation between two
            // groups finds part of the songs renamed above the row; the last
            // finds them all.
            val later = anchors.filter { it.generation > before }
            val kept = later.maxBy { it.generation }
            assertEquals(inView.key, kept.key)
            assertEquals("the row moved down by the songs renamed above it", 2_500, kept.index)
            later.forEach { assertEquals("every later generation keeps it", inView.key, it.key) }
            later.forEach { assertTrue("a generation put the row at ${it.index}", it.index in 1_500..2_500) }
            assertEquals(inView.row.id, shown.peek(2_500)!!.row.id)
            assertEquals("the count came with the rows", 3_000, handle.metaFor(now)!!.count)
            assertEquals(3_000, shown.size())
            assertEquals("Song 1499", shown.peek(2_499)?.row?.title ?: shown.get(2_499)!!.row.title)
        }
    }
}

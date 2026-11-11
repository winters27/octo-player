package app.winters.octo.ui.album

import androidx.paging.ItemSnapshotList
import androidx.paging.PagingConfig
import app.winters.octo.ui.paging.lineIndexOf
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.listening.timed
import app.winters.octo.testing.Presented
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.paging.LIST_PAGING
import app.winters.octo.ui.paging.ListHandle
import app.winters.octo.ui.paging.ListReads
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.albumLibraryLoader
import app.winters.octo.ui.paging.tableChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A big album's pages show the held album's disc headings: one over the
// first row of each disc, once, in place, whichever way the pages are read
// and wherever a page ends; none on an album of one disc.
@RunWith(RobolectricTestRunner::class)
class AlbumDiscsTest {
    private val db = openTestCatalog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        // The list's shared pages stop before the database closes.
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        db.close()
    }

    private fun handle(config: PagingConfig, at: RowKey? = null) = ListHandle<Boolean, TrackEntity>(
        scope,
        false,
        { albumLibraryLoader(db.listsDao(), "al") },
        ListReads.Plain,
        { _, loader -> db.tableChanges(loader.tables) },
        initialKey = at,
        config = config,
    )

    private fun lines(handle: ListHandle<Boolean, TrackEntity>) = Presented(handle.pages.withDiscs { libraryOtherDisc(db, "al", it) }, scope)

    // Every heading stands over a row of its disc, after a row of another
    // disc or at the very top; every change of disc between two rows read
    // has one; no disc is headed twice.
    private fun assertHeadedInPlace(lines: List<AlbumLine>) {
        lines.forEachIndexed { at, line ->
            when (line) {
                is AlbumLine.Disc -> {
                    val next = lines.getOrNull(at + 1) as? AlbumLine.Song ?: throw AssertionError("heading $at has no row after it")
                    assertEquals("heading $at names its row's disc", line.disc, discOf(next.item.row))
                    assertEquals("heading $at is that row's", line.before, next.item)
                    val prev = lines.getOrNull(at - 1)
                    if (prev != null) {
                        assertTrue("heading $at follows a row", prev is AlbumLine.Song)
                        assertTrue("heading $at follows another disc", discOf((prev as AlbumLine.Song).item.row) != line.disc)
                    }
                }
                is AlbumLine.Song -> {
                    val prev = lines.getOrNull(at - 1) as? AlbumLine.Song ?: return@forEachIndexed
                    assertEquals("rows $at and before share a disc with no heading", discOf(prev.item.row), discOf(line.item.row))
                }
            }
        }
        val discs = lines.filterIsInstance<AlbumLine.Disc>().map { it.disc }
        assertEquals("a disc headed twice", discs.distinct(), discs)
    }

    @Test
    fun eachDiscIsHeadedOnceInPlaceAcrossPages() = timed(60) {
        // Three discs of a thousand: each change of disc falls between two
        // pages of a hundred.
        db.albumOnServer(3_000, null) { it / 1_000 + 1 }
        lines(handle(PagingConfig(pageSize = 100, prefetchDistance = 100, initialLoadSize = 300, enablePlaceholders = true))).use { shown ->
            shown.settle()
            var at = 0
            while (at < shown.size()) {
                shown.get(at)
                shown.settle()
                at += 100
            }
            val lines = shown.loaded()
            assertEquals(3_003, lines.size)
            assertEquals(listOf(0, 1_001, 2_002), lines.indices.filter { lines[it] is AlbumLine.Disc })
            assertEquals(listOf(1, 2, 3), lines.filterIsInstance<AlbumLine.Disc>().map { it.disc })
            assertHeadedInPlace(lines)
            assertEquals((0 until 3_000).map { "t%05d".format(it) }, lines.mapNotNull { it.row()?.row?.id })
        }
    }

    @Test
    fun headingsComeInPlaceScrollingUpFromTheMiddleWithTheScreensPaging() = timed(60) {
        db.albumOnServer(3_000, null) { it / 1_000 + 1 }
        val middle = albumLibraryLoader(db.listsDao(), "al").keyAt(1_500)!!
        lines(handle(LIST_PAGING, middle)).use { shown ->
            shown.settle()
            assertTrue("the rows around row 1,500 are all on disc 2", shown.loaded().none { it is AlbumLine.Disc })
            var crossed = false
            var at = shown.snapshot().placeholdersBefore
            while (at > 0) {
                at = maxOf(0, at - 50)
                shown.get(at)
                shown.settle()
                val lines = shown.loaded()
                assertHeadedInPlace(lines)
                val ids = lines.mapNotNull { it.row()?.row?.id }
                if (!crossed && "t00999" in ids && "t01000" in ids) {
                    crossed = true
                    val second = lines.indexOfFirst { it is AlbumLine.Disc && it.disc == 2 }
                    assertEquals("disc 2's heading sits between its last row of disc 1 and its first", "t00999", lines[second - 1].row()!!.row.id)
                    assertEquals("t01000", lines[second + 1].row()!!.row.id)
                }
            }
            assertTrue("the pages read across disc 2's start", crossed)
            val top = shown.loaded()
            assertEquals(AlbumLine.Disc::class, top.first()::class)
            assertEquals(1, (top.first() as AlbumLine.Disc).disc)
            assertEquals("t00000", top[1].row()!!.row.id)
        }
    }

    // The screen keeps a row in view by its lazy index among the lines read:
    // rows not read yet stand for themselves, each heading read before a
    // row moves it on by one.
    @Test
    fun aRowsLazyIndexCountsTheHeadingsReadBeforeIt() {
        val lines = ItemSnapshotList(100, 50, listOf("row", "disc", "row", "row", "disc", "row"))
        val isRow: (String) -> Boolean = { it == "row" }
        assertEquals(40, lineIndexOf(lines, 40, isRow))
        assertEquals(100, lineIndexOf(lines, 100, isRow))
        assertEquals(102, lineIndexOf(lines, 101, isRow))
        assertEquals(103, lineIndexOf(lines, 102, isRow))
        assertEquals(105, lineIndexOf(lines, 103, isRow))
        assertEquals(108, lineIndexOf(lines, 106, isRow))
    }

    @Test
    fun anAlbumOfOneDiscHasNoHeading() = timed(30) {
        db.albumOnServer(2_500, null) { if (it % 2 == 0) null else 1 }
        lines(handle(PagingConfig(pageSize = 100, prefetchDistance = 100, initialLoadSize = 300, enablePlaceholders = true))).use { shown ->
            shown.settle()
            var at = 0
            while (at < shown.size()) {
                shown.get(at)
                shown.settle()
                at += 100
            }
            assertEquals(2_500, shown.loaded().size)
            assertTrue(shown.loaded().none { it is AlbumLine.Disc })
        }
    }
}

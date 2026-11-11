package app.winters.octo.ui.album

import app.winters.octo.catalog.SourceCatalog
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.albumRow
import app.winters.octo.catalog.artistRow
import app.winters.octo.catalog.isFind
import app.winters.octo.catalog.put
import app.winters.octo.catalog.song
import app.winters.octo.catalog.stringOf
import app.winters.octo.ui.paging.followReanchors
import app.winters.octo.data.Drains
import app.winters.octo.listening.timed
import app.winters.octo.testing.Presented
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import app.winters.octo.ui.paging.AlbumListing
import app.winters.octo.ui.paging.ListHandle
import app.winters.octo.ui.paging.ListReads
import app.winters.octo.ui.paging.Reanchor
import app.winters.octo.ui.paging.albumCopyLoader
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// A big album's listing copy is written again when a find it shows is taken
// into the library: the find's row then shows the song it became, in its
// place, and the row in view stays in view. The new copy is written under
// the page's other owner while the page reads the first.
@RunWith(RobolectricTestRunner::class)
class AlbumCopiesTest {
    private val db = openTestCatalog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = AlbumServer()
    private val drainDir = Files.createTempDirectory("drain").toFile()

    @After
    fun close() {
        // The list's shared pages stop before the database closes.
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        server.close()
        db.close()
        drainDir.deleteRecursively()
    }

    @Test
    fun anAdoptedFindShowsAsItsSongInPlaceAndTheRowInViewStays() = timed(60) {
        val n = 2_400
        db.albumOnServer(n, server.sourceId) { 1 }
        // One find at place 5, another at the end, which stays a find.
        server.listing = (0 until n).map { listed(it) }.let { it.take(5) + found(5) + it.drop(5) + found(n) }
        val adoptions = ConcurrentHashMap<String, String>()
        val listing = AlbumListing(db, db.testMerge(server.sourceId), Drains(drainDir), { Long.MAX_VALUE }, { adoptions.toMap() })
        val copies = AlbumCopies(
            listOf(WorkOwners.marked("vm:1"), WorkOwners.marked("vm:2")),
            { owner -> listing.build(owner, "al", server.client, runsOcto = true) },
            { owner -> albumCopyLoader(db.listsDao(), owner) },
            { owner, finds -> copyShowsFinds(db, owner, finds) },
            { owner -> copyIsStale(db, owner, "al") },
        )
        assertTrue(copies.build(emptySet()))
        val first = copies.loader.walkedIds()
        val find = first[5]
        assertTrue("row 5 is the find", isFind(find))
        assertEquals(n + 2, first.size)

        val handle = ListHandle<Boolean, TrackEntity>(scope, true, { copies.loader }, ListReads.Plain, { _, loader -> db.tableChanges(loader.tables) })
        val anchors = CopyOnWriteArrayList<Reanchor>()
        scope.launch { handle.reanchors.collect { anchors += it } }
        Presented(handle.pages, scope).use { shown ->
            shown.settle()
            shown.get(2_000)
            shown.settle()
            val inView = shown.peek(2_000)!!
            handle.visible(inView.key)

            // Another find taken in changes nothing here.
            assertNull(copies.adopted(setOf("find:elsewhere")))
            assertEquals(WorkOwners.marked("vm:1"), copies.owner)

            // The find is downloaded: the library has its song, in this album.
            db.dao().insertTracks(listOf(albumTrack(9_000, 1, id = "t-taken", title = "Taken")))
            adoptions[find] = "t-taken"
            shown.settle()
            val before = handle.generationNow
            assertEquals("the new copy still holds the other find", true, copies.adopted(setOf("find:elsewhere", find)))
            handle.refresh()
            shown.settle(until = { handle.presented > before })

            assertEquals("the page reads the new copy", WorkOwners.marked("vm:2"), copies.owner)
            val now = copies.loader.walkedIds()
            assertEquals("t-taken", now[5])
            assertTrue(find !in now)
            assertEquals(first.filterIndexed { i, _ -> i != 5 }, now.filterIndexed { i, _ -> i != 5 })
            assertEquals("the first copy is left as it was", first, albumCopyLoader(db.listsDao(), WorkOwners.marked("vm:1")).walkedIds())

            val kept = anchors.last { it.generation == handle.presented }
            assertEquals(inView.key, kept.key)
            assertEquals(2_000, kept.index)
            assertEquals(inView.key, shown.peek(2_000)!!.key)
            assertEquals("t-taken", shown.peek(5)?.row?.id ?: copies.loader.after(null, 6)[5].row.id)

            // The same finds again write nothing.
            assertNull(copies.adopted(setOf("find:elsewhere", find)))
        }
    }

    // A swap that renames 50 of a 3,000-song album's songs: the copy, built
    // before, shows each song once under its new id once the swap is told,
    // and the row in view stays in view.
    @Test
    fun aSwapRenamingSongsShowsEachOnceUnderItsNewId() = timed(120) {
        val source = server.sourceId
        fun catalog(renamed: Set<Int>) = SourceCatalog(
            source,
            false,
            (0 until 3_000).map { i ->
                if (i in renamed) {
                    song(source, "r$i", "Renamed %05d".format(i), "srv", no = i % 100 + 1, order = i)
                } else {
                    song(source, "s$i", "Song %05d".format(i), "srv", no = i % 100 + 1, order = i)
                }
            },
            listOf(albumRow(source, "srv", "Big")),
            listOf(artistRow(source)),
        )
        db.put(catalog(emptySet()))
        val merge = db.testMerge(source).also { it.rebuild() }
        val albumId = db.stringOf("SELECT albumId FROM track LIMIT 1")!!
        server.listing = (0 until 3_000).map { listed(it) } + found(3_000)
        val listing = AlbumListing(db, merge, Drains(drainDir), { Long.MAX_VALUE }, { emptyMap() })
        val copies = AlbumCopies(
            listOf(WorkOwners.marked("vm:1"), WorkOwners.marked("vm:2")),
            { owner -> listing.build(owner, albumId, server.client, runsOcto = true) },
            { owner -> albumCopyLoader(db.listsDao(), owner) },
            { owner, finds -> copyShowsFinds(db, owner, finds) },
            { owner -> copyIsStale(db, owner, albumId) },
        )
        assertTrue(copies.build(emptySet()))
        assertNull("a copy as the library is needs nothing", copies.swapped())
        val renamedBefore = (0 until 50).map { db.stringOf("SELECT mergedId FROM source_track WHERE nativeId = ?", "s$it")!! }

        val handle = ListHandle<Boolean, TrackEntity>(scope, true, { copies.loader }, ListReads.Plain, { _, loader -> db.tableChanges(loader.tables) })
        val anchors = CopyOnWriteArrayList<Reanchor>()
        Presented(handle.pages, scope).use { shown ->
            scope.followReanchors(handle, shown, anchors)
            shown.settle()
            shown.get(2_000)
            shown.settle()
            val inView = shown.peek(2_000)!!
            handle.visible(inView.key)

            db.put(catalog((0 until 50).toSet()))
            merge.rebuild()
            server.listing = (0 until 3_000).map { if (it < 50) listed(it).copy(id = "r$it", title = "Renamed %05d".format(it)) else listed(it) } + found(3_000)
            shown.settle()
            val before = handle.generationNow
            assertEquals("the new copy still holds the find", true, copies.swapped())
            handle.refresh()
            shown.settle(until = { handle.presented > before && handle.presented == handle.generationNow })

            val rows = copies.loader.walkedIds()
            assertEquals(3_001, rows.size)
            assertEquals("each song once", rows.size, rows.toSet().size)
            val renamed = (0 until 50).map { db.stringOf("SELECT mergedId FROM source_track WHERE nativeId = ?", "r$it")!! }
            assertTrue("under their new ids, in their places", renamed == rows.take(50))
            assertTrue("none under its old id", renamedBefore.none { it in rows })
            // The old copy hid the renamed rows meanwhile; the row in view
            // kept its key throughout, and the new copy puts it back at 2,000.
            anchors.filter { it.generation > before }.forEach { assertEquals(inView.key, it.key) }
            assertEquals(2_000, anchors.last().index)
            assertEquals(inView.key, shown.peek(2_000)!!.key)
            assertNull(copies.swapped())
        }
    }
}

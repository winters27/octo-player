package app.winters.octo.ui.album

import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.data.Drains
import app.winters.octo.data.Session
import app.winters.octo.listening.PlayTotals
import app.winters.octo.listening.timed
import app.winters.octo.playback.ListSpec
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import app.winters.octo.ui.paging.AlbumListing
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.ListLoaders
import app.winters.octo.ui.paging.albumLibraryLoader
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files

// A library album whose listing on the server holds no find writes no copy:
// the listing is drained to its file and its finds counted there first, and
// with none, the album page and any other reader read the library's songs,
// so the album pages and plays exactly as the library album. A queue's fill,
// which walks only a copy of its own, still has the album's library songs
// written for it, in the library album's order.
@RunWith(RobolectricTestRunner::class)
class AlbumNoFindsTest {
    private val db = openTestCatalog()
    private val server = AlbumServer()
    private val drainDir = Files.createTempDirectory("drain").toFile()
    private val n = 3_000

    @After
    fun close() {
        server.close()
        db.close()
        drainDir.deleteRecursively()
    }

    private fun rows(owner: String) = db.query("SELECT COUNT(*) FROM lists_album_entry WHERE owner = ?", arrayOf(owner)).use { it.moveToFirst(); it.getInt(0) }

    private fun loaders(): ListLoaders {
        val merge = db.testMerge(server.sourceId)
        val totals = PlayTotals(db, merge, { Long.MAX_VALUE }, MutableStateFlow("home"), {}, 500)
        val session = Session(server.client, "octo", null, isOcto = true, adminReachable = false, extensions = emptySet())
        return ListLoaders(
            db, merge, { Long.MAX_VALUE }, PlaylistSets(db), WorkOwners(), Drains(drainDir), totals, { session },
            { error("no downloads") }, { error("no sorted library") }, { error("no health") }, { error("no live") },
            adoptions = { emptyMap() },
        )
    }

    @Test
    fun aListingWithNoFindWritesNoCopyAndTheAlbumReadsAsTheLibrary() = timed(90) {
        db.albumOnServer(n, server.sourceId) { it / 1_000 + 1 }
        server.listing = (0 until n).map { listed(it, disc = it / 1_000 + 1) }
        val library = albumLibraryLoader(db.listsDao(), "al").walkedIds(page = 97)
        assertEquals(n, library.size)

        // The page's own build: no find, nothing written.
        val listing = AlbumListing(db, db.testMerge(server.sourceId), Drains(drainDir), { Long.MAX_VALUE }, { emptyMap() })
        db.counted.reset()
        db.counted.recording = true
        assertFalse(listing.build(WorkOwners.marked("vm:1"), "al", server.client, runsOcto = true, keepLibrary = false))
        db.counted.recording = false
        val own = db.counted.own
        assertTrue("the build was recorded", own.isNotEmpty())
        val copyWrites = own.filter { "lists_album_entry" in it.sql && it.sql.trimStart().startsWith("INSERT") }.sumOf { it.rowsChanged }
        assertEquals("copy rows written", 0, copyWrites)
        assertEquals(0, rows(WorkOwners.marked("vm:1")))
        assertTrue(drainDir.listFiles().isNullOrEmpty())

        // A page or the car asking for the album with its finds reads the
        // library's songs; a fill gets its own copy of them, in the same order.
        val loaders = loaders()
        @Suppress("UNCHECKED_CAST")
        val page = loaders.forSpec(ListSpec.Album("al", withFinds = true), WorkOwners.marked("vm:2")) as ListLoader<Any>
        assertEquals(library, page.walkedIds(page = 89))
        assertEquals(0, rows(WorkOwners.marked("vm:2")))
        @Suppress("UNCHECKED_CAST")
        val fill = loaders.forSpec(ListSpec.Album("al", withFinds = true), WorkOwners.marked("queue:1")) as ListLoader<Any>
        assertEquals(library, fill.walkedIds(page = 89))
        assertEquals(n, rows(WorkOwners.marked("queue:1")))
    }

    // With finds, the copy is written as it was: the listing, then the
    // library's songs it left out.
    @Test
    fun aListingWithFindsIsCopied() = timed(90) {
        db.albumOnServer(n, server.sourceId) { 1 }
        server.listing = (0 until n).flatMap { i -> listOfNotNull(listed(i), if (i % 500 == 3) found(i) else null) }
        val listing = AlbumListing(db, db.testMerge(server.sourceId), Drains(drainDir), { Long.MAX_VALUE }, { emptyMap() })
        assertTrue(listing.build(WorkOwners.marked("vm:1"), "al", server.client, runsOcto = true, keepLibrary = false))
        assertEquals(n + 6, rows(WorkOwners.marked("vm:1")))
    }

    // A song of the server's own the copy lacks, with no mark and nothing
    // marked beside it, is in its place too, and handed on to be brought in.
    @Test
    fun aListingWithTheServersOwnSongTheCopyLacksShowsItInPlace() = timed(90) {
        db.albumOnServer(n, server.sourceId) { 1 }
        server.listing = (0 until n).map { listed(it) } + app.winters.octo.subsonic.Song(id = "u1", title = "Unmarked", artist = "Band", albumId = "srv")
        val handed = mutableListOf<String>()
        val listing = AlbumListing(db, db.testMerge(server.sourceId), Drains(drainDir), { Long.MAX_VALUE }, { emptyMap() }) { _, songs -> handed += songs.map { it.id } }
        assertTrue(listing.build(WorkOwners.marked("vm:1"), "al", server.client, runsOcto = true, keepLibrary = false))
        assertEquals(n + 1, rows(WorkOwners.marked("vm:1")))
        assertEquals(listOf("u1"), handed)
    }
}

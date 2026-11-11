package app.winters.octo.ui.paging

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.deleteOwned
import app.winters.octo.data.Drains
import app.winters.octo.listening.timed
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import app.winters.octo.ui.album.albumTrack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files

// Two pages in the back stack each read their own owner's copy: the top one
// built again (another order, the library changed, then let go) leaves the
// lower one's rows and keys as they were, for folder levels and for big
// albums alike.
@RunWith(RobolectricTestRunner::class)
class TwoOwnersTest {
    private val db = openTestCatalog()
    private val drainDir = Files.createTempDirectory("drain").toFile()

    @After
    fun close() {
        db.close()
        drainDir.deleteRecursively()
    }

    private fun song(folder: String, i: Int) = TrackEntity(
        id = "device:$folder$i", sourceId = "device", nativeId = "$folder$i", title = "Song ${i % 50}", searchKey = "song", sortKey = "song %02d".format(i % 50),
        artist = "Artist ${i % 7}", artistId = "ar", album = "Album ${i % 3}", albumId = "al$folder${i % 3}", trackNo = null, discNo = null, year = null,
        durationMs = 0, addedAt = i.toLong(), mimeType = null, sizeBytes = null, artwork = null, uri = null, albumOrder = i,
    )

    private suspend fun keysOf(loader: ListLoader<*>): List<RowKey> {
        val out = ArrayList<RowKey>()
        var after: RowKey? = null
        while (true) {
            val rows = loader.after(after, 100)
            out += rows.map { it.key }
            if (rows.size < 100) return out
            after = rows.last().key
        }
    }

    @Test
    fun aRebuiltFolderLevelLeavesTheLowerOnesRows() = timed(60) {
        val lower = db.phoneFolder("A", (0 until 600).map { song("a", it) })
        val top = db.phoneFolder("B", (0 until 700).map { song("b", it) })
        val folders = PhoneFolders(db, db.testMerge(null), { Long.MAX_VALUE })
        folders.copy(WorkOwners.marked("vm:1"), listOf(lower), SortOrder(SongSort.Title, false), ranked = false)
        folders.copy(WorkOwners.marked("vm:2"), listOf(top), SortOrder(SongSort.Title, false), ranked = false)
        val before = keysOf(folders.loader(WorkOwners.marked("vm:1")))
        val ids = folders.loader(WorkOwners.marked("vm:1")).walked()
        assertEquals(600, before.size)

        folders.copy(WorkOwners.marked("vm:2"), listOf(top), SortOrder(SongSort.Artist, true), ranked = false)
        db.openHelper.writableDatabase.execSQL("UPDATE track SET title = 'Renamed', sortKey = 'aaa' WHERE id = 'device:b0'")
        folders.copy(WorkOwners.marked("vm:2"), listOf(top), SortOrder(SongSort.RecentlyAdded, false), ranked = false)
        assertEquals(700, folders.loader(WorkOwners.marked("vm:2")).walked().size)
        db.deleteOwned("lists_folder_order", WorkOwners.marked("vm:2"))

        assertEquals(before, keysOf(folders.loader(WorkOwners.marked("vm:1"))))
        assertEquals(ids, folders.loader(WorkOwners.marked("vm:1")).walked())
        assertEquals(0, folders.loader(WorkOwners.marked("vm:2")).count())
    }

    @Test
    fun aRebuiltAlbumCopyLeavesTheLowerOnesRows() = timed(60) {
        db.dao().insertTracks((0 until 2_100).map { albumTrack(it, 1, albumId = "x", id = "x%05d".format(it)) } + (0 until 2_200).map { albumTrack(it, 2, albumId = "y", id = "y%05d".format(it)) })
        val listing = AlbumListing(db, db.testMerge(null), Drains(drainDir), { Long.MAX_VALUE }, { emptyMap() })
        listing.build(WorkOwners.marked("vm:3"), "x", null, runsOcto = false)
        listing.build(WorkOwners.marked("vm:4"), "y", null, runsOcto = false)
        val lower = albumCopyLoader(db.listsDao(), WorkOwners.marked("vm:3"))
        val before = keysOf(lower)
        assertEquals(2_100, before.size)

        db.dao().insertTracks(listOf(albumTrack(5_000, 2, albumId = "y", id = "y-new")))
        listing.build(WorkOwners.marked("vm:4"), "y", null, runsOcto = false)
        assertEquals(2_201, albumCopyLoader(db.listsDao(), WorkOwners.marked("vm:4")).count())
        db.deleteOwned("lists_album_entry", WorkOwners.marked("vm:4"))

        assertEquals(before, keysOf(albumCopyLoader(db.listsDao(), WorkOwners.marked("vm:3"))))
        assertTrue(albumCopyLoader(db.listsDao(), WorkOwners.marked("vm:3")).walked().all { it.startsWith("x") })
    }
}

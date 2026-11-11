package app.winters.octo.ui.paging

import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.data.Drains
import app.winters.octo.data.userMessage
import app.winters.octo.listening.timed
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import app.winters.octo.ui.album.albumTrack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

// Every writer of a list's copy checks for room before it starts and runs
// into a full disk as the list's own problem: too little room stops it
// with the megabytes to free, a full disk on its third page stops it with
// no figure, each in the list's words; the copy the page shows meanwhile
// keeps its rows, and the next build once there is room again succeeds.
@RunWith(RobolectricTestRunner::class)
class ListSpaceTest {
    private val db = openTestCatalog()
    private val drainDir = Files.createTempDirectory("drain").toFile()

    @Volatile private var room = Long.MAX_VALUE

    @After
    fun close() {
        db.counted.fullDisk = null
        db.close()
        drainDir.deleteRecursively()
    }

    // Refuses the third insert into `table` from now on, as a full disk
    // would.
    private fun fullOnThirdPage(table: String) {
        val inserts = AtomicInteger()
        db.counted.fullDisk = { sql -> sql.trimStart().startsWith("INSERT") && table in sql && inserts.incrementAndGet() >= 3 }
    }

    // The megabytes the words ask to free for `bytes`, rounded up.
    private fun megabytesFor(bytes: Long): Long = (bytes + 999_999) / 1_000_000

    private suspend fun failsWith(words: String, block: suspend () -> Unit) {
        try {
            block()
            fail("no problem for: $words")
        } catch (e: NotEnoughSpace) {
            assertEquals(SpaceFor.List, e.doing)
            assertEquals(words, e.userMessage())
        }
    }

    private fun song(i: Int) = TrackEntity(
        id = "device:s%05d".format(i), sourceId = "device", nativeId = "$i", title = "Song $i", searchKey = "song $i", sortKey = "song %05d".format(i),
        artist = "Band", artistId = "ar", album = "One", albumId = "al", trackNo = null, discNo = null, year = null, durationMs = 0, addedAt = 0,
        mimeType = null, sizeBytes = null, artwork = null, uri = null, albumOrder = i, rating = i % 6,
    )

    @Test
    fun aFolderCopyKeepsTheShownCopyAndSaysWhatToFree() = timed(60) {
        val folder = db.phoneFolder("A", (0 until 1_300).map(::song))
        val folders = PhoneFolders(db, db.testMerge(null)) { room }
        val order = SortOrder(SongSort.Title, false)
        folders.copy(WorkOwners.marked("vm:1"), listOf(folder), order, ranked = false)
        val shown = folders.loader(WorkOwners.marked("vm:1")).walked()

        room = 1_000
        failsWith("Not enough space to show this list. Free up ${megabytesFor(1_300 * FOLDER_ORDER_ROW_BYTES * 2)} MB, then try again.") {
            folders.copy(WorkOwners.marked("vm:2"), listOf(folder), order, ranked = false)
        }
        room = Long.MAX_VALUE
        fullOnThirdPage("lists_folder_order")
        failsWith("Not enough space to show this list. Free up some space, then try again.") { folders.copy(WorkOwners.marked("vm:2"), listOf(folder), order, ranked = false) }
        assertEquals("the copy cut short left no rows", 0, folders.loader(WorkOwners.marked("vm:2")).count())
        assertEquals("the shown copy kept its rows", shown, folders.loader(WorkOwners.marked("vm:1")).walked())

        db.counted.fullDisk = null
        folders.copy(WorkOwners.marked("vm:2"), listOf(folder), order, ranked = false)
        assertEquals(shown, folders.loader(WorkOwners.marked("vm:2")).walked())
    }

    @Test
    fun anAlbumCopyStopsOnAFullDiskAndBuildsOnceThereIsRoom() = timed(60) {
        db.dao().insertTracks((0 until 2_400).map { albumTrack(it, 1) })
        val listing = AlbumListing(db, db.testMerge(null), Drains(drainDir), { room }, { emptyMap() })
        listing.build(WorkOwners.marked("vm:1"), "al", null, runsOcto = false)
        val shown = albumCopyLoader(db.listsDao(), WorkOwners.marked("vm:1")).walked()
        assertEquals(2_400, shown.size)

        fullOnThirdPage("lists_album_entry")
        failsWith("Not enough space to show this list. Free up some space, then try again.") { listing.build(WorkOwners.marked("vm:2"), "al", null, runsOcto = false) }
        assertEquals("the shown copy kept its rows", shown, albumCopyLoader(db.listsDao(), WorkOwners.marked("vm:1")).walked())

        db.counted.fullDisk = null
        listing.build(WorkOwners.marked("vm:2"), "al", null, runsOcto = false)
        assertEquals(shown, albumCopyLoader(db.listsDao(), WorkOwners.marked("vm:2")).walked())
    }

    @Test
    fun aSortCopySaysWhatToFreeAndBuildsOnceThereIsRoom() = timed(60) {
        db.dao().insertTracks((0 until 1_300).map(::song))
        val copies = SortCopies(db, db.testMerge(null)) { room }
        val order = SortOrder(SongSort.Rating, true)
        room = 1_000
        failsWith("Not enough space to show this list. Free up ${megabytesFor(1_300 * SORT_COPY_ROW_BYTES * 2)} MB, then try again.") {
            copies.fillSongs(WorkOwners.marked("queue:1"), SongScope.All, order) { it }
        }
        room = Long.MAX_VALUE
        fullOnThirdPage("lists_sort_copy")
        failsWith("Not enough space to show this list. Free up some space, then try again.") { copies.fillSongs(WorkOwners.marked("queue:1"), SongScope.All, order) { it } }
        db.counted.fullDisk = null
        copies.fillSongs(WorkOwners.marked("queue:1"), SongScope.All, order) { it }
        assertEquals(SongLists(db.listsDao()).loader(SongScope.All, order).walked(), copies.loader(WorkOwners.marked("queue:1"), SongSort.Rating, downloads = false, down = true).walked { it.trackId })
    }
}

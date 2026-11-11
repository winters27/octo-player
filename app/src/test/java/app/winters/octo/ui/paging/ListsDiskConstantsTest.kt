package app.winters.octo.ui.paging

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.data.Drains
import app.winters.octo.listening.timed
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files

// A row of each list copy takes what its constant says, its keys and
// indexes included, under an owner as the app writes it (the launch's mark
// first): a sort copy's (SORT_COPY_ROW_BYTES) and an album's entries
// (ALBUM_ENTRY_ROW_BYTES) with a server's long ids, a phone folder's order
// (FOLDER_ORDER_ROW_BYTES) with the phone's.
@RunWith(RobolectricTestRunner::class)
class ListsDiskConstantsTest {
    private val rows = 3_000
    private val server = "server:music.home-server.example.net:4533"

    // Bytes the file's pages in use take, every write checkpointed in: the
    // log's share is the second half of each copy's ask, and how long the
    // log grows before it starts again depends on who reads meanwhile.
    private fun used(db: CatalogDatabase): Long {
        val w = db.openHelper.writableDatabase
        w.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        fun pragma(name: String) = w.query("PRAGMA $name").use { it.moveToFirst(); it.getLong(0) }
        return (pragma("page_count") - pragma("freelist_count")) * pragma("page_size")
    }

    private fun song(i: Int, id: String, sourceId: String) = TrackEntity(
        id = id, sourceId = sourceId, nativeId = id.substringAfterLast(':'), title = "Untitled song number $i", searchKey = "untitled song number $i",
        sortKey = "untitled song number %05d".format(i), artist = "The Royal Philharmonic Orchestra and Chorus", artistId = "$server:ar1",
        album = "Greatest Hits", albumId = "al", trackNo = i % 100 + 1, discNo = 1, year = 1971, durationMs = 213_000, addedAt = i.toLong(),
        mimeType = null, sizeBytes = null, artwork = null, uri = null, albumOrder = i, rating = i % 6,
    )

    private fun serverSong(i: Int) = song(i, "$server:" + "%032x".format(i.toLong() * 7_919 + 12_345), server)

    // What `copy` adds to the file a row, measured on a database of its own,
    // held to `constant` from both sides.
    private suspend fun measure(label: String, table: String, constant: Long, fill: suspend (CatalogDatabase) -> Unit, copy: suspend (CatalogDatabase, String) -> Unit) {
        val dir = Files.createTempDirectory("catalog").toFile()
        val db = openTestCatalog(dir)
        try {
            fill(db)
            val before = used(db)
            val owner = WorkOwners.marked("vm:1")
            copy(db, owner)
            val written = db.query("SELECT COUNT(*) FROM $table WHERE owner = ?", arrayOf(owner)).use { it.moveToFirst(); it.getInt(0) }
            assertEquals("$label rows", rows, written)
            val perRow = (used(db) - before) / rows
            println("MEASURED lists $label rows=$rows perRow=$perRow constant=$constant")
            assertTrue("$label: measured $perRow, constant $constant", perRow <= constant)
            assertTrue("$label: measured $perRow, constant $constant", constant <= perRow * 11 / 10 + 10)
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aSortCopysRowTakesWhatItsConstantSays() = timed(60) {
        measure("sort copy", "lists_sort_copy", SORT_COPY_ROW_BYTES, { db -> db.dao().insertTracks((0 until rows).map(::serverSong)) }) { db, owner ->
            SortCopies(db, db.testMerge(null)) { Long.MAX_VALUE }.fillSongs(owner, SongScope.All, SortOrder(SongSort.Rating, true)) { it }
        }
    }

    @Test
    fun aFolderOrdersRowTakesWhatItsConstantSays() = timed(60) {
        val songs = (0 until rows).map { song(it, "device:${1_000_000 + it}", "device") }
        measure("folder order", "lists_folder_order", FOLDER_ORDER_ROW_BYTES, { db -> db.phoneFolder("A", songs) }) { db, owner ->
            // The folder phoneFolder made, as the page's tree holds it.
            val folder = PhoneFolder("A", emptyList(), rows, listOf(FolderPlace("primary", "Music/A")), null)
            PhoneFolders(db, db.testMerge(null)) { Long.MAX_VALUE }.copy(owner, listOf(folder), SortOrder(SongSort.Title, false), ranked = false)
        }
    }

    @Test
    fun anAlbumEntrysRowTakesWhatItsConstantSays() = timed(60) {
        measure("album entries", "lists_album_entry", ALBUM_ENTRY_ROW_BYTES, { db -> db.dao().insertTracks((0 until rows).map(::serverSong)) }) { db, owner ->
            val drains = Files.createTempDirectory("drain").toFile()
            try {
                AlbumListing(db, db.testMerge(null), Drains(drains), { Long.MAX_VALUE }, { emptyMap() }).build(owner, "al", null, runsOcto = false)
            } finally {
                drains.deleteRecursively()
            }
        }
    }
}

package app.winters.octo.offline

import app.winters.octo.listening.timed
import app.winters.octo.sort.DownloadSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.ui.paging.DownloadsLists
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.OldSqlite
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Every finished download has a key of its own in each order of the
// Downloads page: each done index leads with the server and ends in the
// song and the download's own key after it, so two downloaded copies of one
// song with one title, and a third with the same server id on another
// server, are three keys, and a walk of each order to its last column is
// led by its index with no sort.
@RunWith(RobolectricTestRunner::class)
class DownloadsDuplicateSongKeyTest {
    private val db = openTestCatalog()
    private val book = DownloadBook(db.downloadDao())

    private val indexes = mapOf(
        "index_download_done_title" to listOf("sourceId", "state", "titleKey", "trackId", "serverId"),
        "index_download_done_size" to listOf("sourceId", "state", "sizeBytes", "titleKey", "trackId", "serverId"),
        "index_download_done_added" to listOf("sourceId", "state", "addedAt", "titleKey", "trackId", "serverId"),
    )

    @After
    fun close() = db.close()

    @Test
    fun eachDoneIndexEndsInTheDownloadsOwnKey() {
        val w = db.openHelper.writableDatabase
        for ((index, columns) in indexes) {
            val found = w.query("PRAGMA index_info(`$index`)").use { c -> buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) } }
            assertEquals(index, columns, found)
        }
    }

    @Test
    fun twoCopiesOfOneSongHaveTwoKeys() = timed {
        book.upsert(
            listOf(
                downloadRow("device:song", serverId = "n1", state = DownloadStatus.Done, path = "/m/1.flac", title = "Same"),
                downloadRow("device:song", serverId = "n2", state = DownloadStatus.Done, path = "/m/2.flac", title = "Same"),
                downloadRow("device:song", serverId = "n1", sourceId = "server:o", state = DownloadStatus.Done, path = "/m/3.flac", title = "Same"),
            ),
        )
        val w = db.openHelper.writableDatabase
        for ((index, columns) in indexes) {
            val keys = w.query("SELECT ${columns.joinToString { "quote($it)" }} FROM download INDEXED BY `$index` WHERE state = 'Done'").use { c ->
                buildList { while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { c.getString(it) }) }
            }
            assertEquals(index, 3, keys.toSet().size)
        }
    }

    // The page walks the same three rows with three keys in every order,
    // a page of one at a time, each key found again by its song.
    @Test
    fun thePageListsEachCopyOnce() = timed {
        book.upsert(
            listOf(
                downloadRow("device:song", serverId = "n1", state = DownloadStatus.Done, path = "/m/1.flac", title = "Same"),
                downloadRow("device:song", serverId = "n2", state = DownloadStatus.Done, path = "/m/2.flac", title = "Same"),
                downloadRow("device:song", serverId = "n1", sourceId = "server:o", state = DownloadStatus.Done, path = "/m/3.flac", title = "Same"),
            ),
        )
        for (sort in DownloadSort.entries) {
            for (down in listOf(false, true)) {
                val loader = DownloadsLists(db.listsDao()).loader("server:h", SortOrder(sort, down))
                val keys = ArrayList<RowKey>()
                var after: RowKey? = null
                while (true) {
                    val page = loader.after(after, 1)
                    if (page.isEmpty()) break
                    keys += page.single().key
                    after = page.single().key
                }
                assertEquals("$sort $down", 2, keys.size)
                assertEquals("$sort $down", 2, keys.map { it.token }.toSet().size)
                assertEquals("$sort $down", 2, loader.count())
                keys.forEach { key -> assertEquals(key, loader.rekey(key)) }
            }
        }
    }

    @Test
    fun eachOrderIsWalkedToItsLastColumnByItsIndex() {
        val problems = mutableListOf<String>()
        for ((index, columns) in indexes) {
            val order = columns.drop(2)
            val sql = "SELECT * FROM download WHERE sourceId = ? AND state = 'Done' ORDER BY ${order.joinToString()} LIMIT 50"
            val lines = OldSqlite.open().use { OldSqlite.plan(it, sql, listOf("server:h")) } + CurrentSqlite.plan(sql, listOf("server:h"))
            if (lines.none { index in it }) problems += "$index not used: $lines"
            lines.filter { "TEMP B-TREE" in it }.forEach { problems += "$index: $it" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}

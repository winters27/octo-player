package app.winters.octo.ui.library.health

import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.read
import app.winters.octo.health.HealthCheck
import app.winters.octo.listening.timed
import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.OldSqlite
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.paging.RowKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// No length's lines read the songs with no length from the length index
// and sort them while there are few (at most the threshold, counted by a
// statement that stops one past it), and walk the library's own order when
// there are more, so a page never walks the whole library for a few songs
// nor sorts many. Both read the same lines in library order, page by page,
// across the threshold while songs gain or lose a length; each plans as
// it should on 3.21 and a current SQLite. The base always walked the
// library's order.
@RunWith(RobolectricTestRunner::class)
class NoLengthBranchTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    // Library order's songs with no length, as the check lists them.
    private fun today(): List<String> =
        db.read("SELECT id FROM track WHERE durationMs < 1000 ORDER BY sortKey, id") { it.getString(0) }

    private suspend fun walk(loader: HealthLoader, page: Int, between: suspend (Int) -> Unit = {}): List<String> {
        val all = ArrayList<String>()
        var after: RowKey? = null
        var n = 0
        while (true) {
            val lines = loader.after(after, page)
            all += lines.map { it.row }
            if (lines.size < page) return all
            after = lines.last().key
            between(++n)
        }
    }

    private fun linesRead(): List<String> = db.counted.own.map { it.sql }.filter { it.startsWith("SELECT sortKey, id FROM track") }

    @Test
    fun fewAreSortedFromTheLengthIndexManyWalkTheLibrary() = timed(120) {
        val tracks = db.healthLibrary(albums = 60, seed = 2)
        val rig = HealthRig(db).keys()
        rig.built()
        val none = today()
        assertTrue("${none.size} songs with no length", none.size >= 20 && none.size < tracks.size)

        // At or under the threshold: the length index, sorted.
        val few = HealthLoader(rig.build, db, HealthCheck.NoLength, WorkOwners.marked("queue:1"), sortedMost = none.size)
        db.counted.reset()
        db.counted.recording = true
        assertEquals(none, walk(few, 7))
        db.counted.recording = false
        assertTrue(linesRead().joinToString("\n"), linesRead().isNotEmpty() && linesRead().all { "INDEXED BY index_track_length" in it })
        val counts = db.counted.own.filter { "LIMIT ?)" in it.sql && it.sql.startsWith("SELECT COUNT(*) FROM (SELECT 1 FROM track") }
        assertTrue("$counts", counts.isNotEmpty() && counts.all { it.rowsRead == 1 })

        // Past it: the library's order, walked.
        val many = HealthLoader(rig.build, db, HealthCheck.NoLength, WorkOwners.marked("queue:2"), sortedMost = none.size - 1)
        db.counted.reset()
        db.counted.recording = true
        assertEquals(none, walk(many, 7))
        db.counted.recording = false
        assertTrue(linesRead().joinToString("\n"), linesRead().isNotEmpty() && linesRead().all { "INDEXED BY index_track_sortKey_id" in it })

        // One song's line stays on its key.
        val one = none[3]
        db.counted.reset()
        db.counted.recording = true
        assertEquals(one, few.keyOf(one)?.id)
        db.counted.recording = false
        assertTrue(linesRead().joinToString("\n"), linesRead().single().let { "INDEXED BY" !in it && "id = ?" in it })
    }

    @Test
    fun linesPageInOrderAcrossTheThreshold() = timed(120) {
        db.healthLibrary(albums = 60, seed = 3)
        val rig = HealthRig(db).keys()
        rig.built()
        val none = today()
        val threshold = none.size + 2
        val loader = HealthLoader(rig.build, db, HealthCheck.NoLength, WorkOwners.marked("queue:3"), sortedMost = threshold)
        val lengths = db.read("SELECT id FROM track WHERE durationMs >= 1000 ORDER BY sortKey, id") { it.getString(0) }
        db.counted.reset()
        db.counted.recording = true
        // After the second page, five songs lose their length (past the
        // threshold now); after the fourth, they get it back.
        val walked = walk(loader, 5) { n ->
            when (n) {
                2 -> lengths.take(5).forEach { db.openHelper.writableDatabase.execSQL("UPDATE track SET durationMs = 0 WHERE id = ?", arrayOf(it)) }
                4 -> lengths.take(5).forEach { db.openHelper.writableDatabase.execSQL("UPDATE track SET durationMs = 200000 WHERE id = ?", arrayOf(it)) }
            }
        }
        db.counted.recording = false
        // Each page read on the walk then current: in library order, each
        // song once, the songs before the cut as they were.
        assertEquals(walked, walked.distinct())
        assertEquals(walked.sortedWith(libraryOrderOf()), walked)
        assertEquals(none.filter { it in walked }, walked.filter { it in none })
        val reads = linesRead()
        assertTrue(reads.joinToString("\n"), reads.any { "index_track_length" in it } && reads.any { "index_track_sortKey_id" in it })
    }

    // Both branches' statements on SQLite 3.21 and a current one: the few
    // read the length index alone and sort them; the many walk the order's
    // index and never sort; the count stops on the length index.
    @Test
    fun bothBranchesPlanAsTheyShould() {
        val few = "SELECT sortKey, id FROM track INDEXED BY index_track_length WHERE durationMs < 1000 AND (sortKey, id) > (?, ?) ORDER BY sortKey, id LIMIT ?"
        val many = "SELECT sortKey, id FROM track INDEXED BY index_track_sortKey_id WHERE durationMs < 1000 AND (sortKey, id) > (?, ?) ORDER BY sortKey, id LIMIT ?"
        val count = "SELECT COUNT(*) FROM (SELECT 1 FROM track WHERE durationMs < 1000 LIMIT ?)"
        fun plans(sql: String) = OldSqlite.open().use { OldSqlite.plan(it, sql, listOf("a", "b", 60).take(sql.count { c -> c == '?' })) } + CurrentSqlite.plan(sql)
        plans(few).let { lines ->
            assertTrue(lines.joinToString(), lines.any { "COVERING INDEX index_track_length" in it })
            assertTrue(lines.joinToString(), lines.all { "TEMP B-TREE" !in it || it == "USE TEMP B-TREE FOR ORDER BY" })
        }
        plans(many).let { lines ->
            assertTrue(lines.joinToString(), lines.any { "index_track_sortKey_id" in it })
            assertTrue(lines.joinToString(), lines.none { "TEMP B-TREE" in it })
        }
        plans(count).let { lines ->
            assertTrue(lines.joinToString(), lines.any { "COVERING INDEX index_track_length" in it })
            assertTrue(lines.joinToString(), lines.none { "TEMP B-TREE" in it || (it.startsWith("SCAN") && "track" in it && "USING" !in it) })
        }
    }

    // Two song ids in library order, as the database orders them.
    private fun libraryOrderOf(): Comparator<String> {
        val keys = db.read("SELECT id, sortKey FROM track") { it.getString(0) to it.getString(1) }.toMap()
        return Comparator { a, b -> app.winters.octo.ui.paging.SqlOrder.text(keys.getValue(a), keys.getValue(b)).takeIf { it != 0 } ?: app.winters.octo.ui.paging.SqlOrder.text(a, b) }
    }
}

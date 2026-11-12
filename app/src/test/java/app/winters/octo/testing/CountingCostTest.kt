package app.winters.octo.testing

import androidx.room.withTransaction
import app.winters.octo.catalog.WriteLabels
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// What the counting harness counts for a write's cost and a start's BEGINs
// (CountingOpenHelperFactory).
@RunWith(RobolectricTestRunner::class)
class CountingCostTest {
    // Inside a transaction, a write counts every row it changed with its
    // triggers' writes, where changes() counts only its own: a like written
    // to a renamed id is two changed rows, one of them the trigger's. The
    // transaction sums them. Outside a transaction nothing is counted.
    @Test
    fun aWriteCountsTheRowsItsTriggersWrote() = runBlocking {
        val db = openTestCatalog()
        val w = db.openHelper.writableDatabase
        w.execSQL("INSERT INTO save_rename (kind, old, new, byKey, `key`) VALUES ('track', 'old', 'new', 0, '')")
        val (_, ran) = db.counted.record {
            w.beginTransaction()
            try {
                w.execSQL("INSERT INTO liked_track (trackId, relinkKey, likedAt) VALUES ('old', 'k', 1)")
                w.execSQL("INSERT INTO liked_track (trackId, relinkKey, likedAt) VALUES ('plain', 'k', 2)")
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
        }
        val likes = ran.filter { it.sql.startsWith("INSERT INTO liked_track") }
        assertEquals(2, likes.size)
        assertEquals("changes() leaves the trigger's write out", listOf(1, 1), likes.map { it.rowsChanged })
        assertEquals("the trigger's write is counted", listOf(2L, 1L), likes.map { it.totalChanges })
        assertEquals(3L, db.counted.transactions.single { it.thread == Thread.currentThread().name && it.rowsChanged > 0 }.totalChanges)
        w.query("SELECT trackId FROM liked_track WHERE likedAt = 1").use { it.moveToFirst(); assertEquals("new", it.getString(0)) }

        val (_, outside) = db.counted.record {
            w.execSQL("INSERT INTO liked_track (trackId, relinkKey, likedAt) VALUES ('alone', 'k', 3)")
        }
        assertNull(outside.single { it.sql.startsWith("INSERT INTO liked_track") }.totalChanges)
        db.close()
    }

    // Each outermost BEGIN is kept as it runs, with how it began, its
    // thread and its place among the statements; a nested begin runs none
    // of its own. Room's own begin under write-ahead logging is IMMEDIATE.
    // Room may begin transactions of its own meanwhile (its change
    // tracking), so this test's begins are told by thread and by label.
    @Test
    fun eachBeginIsKeptWithItsKindInTheOrderItRan() = runBlocking {
        val db = openTestCatalog()
        val w = db.openHelper.writableDatabase
        val me = Thread.currentThread().name
        val before = db.counted.mark()
        val (_, ran) = db.counted.record {
            w.query("SELECT 1").use { it.moveToFirst() }
            w.beginTransaction()
            w.endTransaction()
            w.beginTransactionNonExclusive()
            w.beginTransaction()
            w.endTransaction()
            w.endTransaction()
            w.beginTransactionReadOnly()
            w.endTransaction()
            runBlocking { withContext(WriteLabels.of(ROOM_BEGIN)) { db.withTransaction { db.workDao().ensureMergeState() } } }
        }
        val mine = db.counted.begins.filter { it.thread == me }
        assertEquals(listOf(BeginKind.Exclusive, BeginKind.Immediate, BeginKind.ReadOnly), mine.map { it.kind })
        assertEquals(listOf(true, true, false), mine.map { it.kind.asksForWriteLock })
        assertTrue(mine.zipWithNext().all { (a, b) -> a.beginSeq < b.beginSeq })
        val room = db.counted.begins.single { it.label == ROOM_BEGIN }
        assertEquals(BeginKind.Immediate, room.kind)
        assertTrue("Room's transaction runs on a thread of its own", room.thread != me)
        assertTrue(mine.last().beginSeq < room.beginSeq)
        val select = ran.first { it.sql == "SELECT 1" }
        assertTrue(select.seq > before)
        assertTrue(select.seq < mine.first().beginSeq)
        assertTrue(db.counted.mark() >= room.beginSeq)
        // Reset forgets them.
        db.counted.reset()
        assertTrue(db.counted.begins.isEmpty())
        assertFalse(db.counted.recording)
        db.close()
    }

    private companion object {
        const val ROOM_BEGIN = "room begin"
    }
}

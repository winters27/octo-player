package app.winters.octo.testing

import androidx.room.withTransaction
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WriteLabels
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CountingHarnessTest {
    private fun track(i: Int, album: String = "a") = TrackEntity(
        id = "device:$i", sourceId = "device", nativeId = "$i", title = "T$i", searchKey = "t$i", sortKey = "t$i",
        artist = "", artistId = "", album = album, albumId = album, trackNo = null, discNo = null, year = null,
        durationMs = 0, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null, relinkKey = "k$i",
    )

    // A raw query and a DAO call reading the same rows count the same rows,
    // each as one statement of the app's own.
    @Test
    fun aRawQueryAndADaoCallAreCountedAlike() = runBlocking {
        val db = openTestCatalog()
        db.dao().insertTracks((1..30).map(::track))
        val (_, raw) = db.counted.record {
            db.openHelper.writableDatabase.query("SELECT id FROM track ORDER BY id").use { c -> while (c.moveToNext()) Unit }
        }
        val rawOwn = raw.filterNot { it.bookkeeping }
        assertEquals(1, rawOwn.size)
        assertEquals(30, rawOwn.single().rowsRead)

        db.counted.reset()
        db.counted.recording = true
        assertEquals(30, db.dao().allTrackIds().size)
        db.counted.recording = false
        val dao = db.counted.own
        assertEquals(dao.toString(), 1, dao.size)
        assertEquals(30, dao.single().rowsRead)
        assertEquals(30, db.counted.maxRead!!.rowsRead)
        db.close()
    }

    // Bound arguments and changed rows are counted for DAO calls, raw SQL and
    // compiled statements alike; a cursor read halfway counts what it read.
    @Test
    fun boundArgumentsAndChangedRowsAreCounted() = runBlocking {
        val db = openTestCatalog()
        db.dao().insertTracks((1..30).map { track(it, album = if (it <= 20) "a" else "b") })
        db.counted.reset()
        db.dao().tracksByIdsUnordered((1..25).map { "device:$it" })
        assertEquals(25, db.counted.maxBound!!.boundArgs)
        assertEquals(25, db.counted.maxRead!!.rowsRead)

        db.counted.reset()
        db.openHelper.writableDatabase.execSQL("UPDATE track SET title = ? WHERE albumId = ?", arrayOf<Any?>("x", "a"))
        assertEquals(20, db.counted.maxChanged!!.rowsChanged)
        assertEquals(2, db.counted.maxBound!!.boundArgs)

        db.counted.reset()
        db.openHelper.writableDatabase.compileStatement("DELETE FROM track WHERE albumId = ?1").use {
            it.bindString(1, "b")
            it.execute()
        }
        assertEquals(10, db.counted.maxChanged!!.rowsChanged)

        db.counted.reset()
        db.openHelper.writableDatabase.query("SELECT id FROM track ORDER BY id").use { c -> repeat(7) { c.moveToNext() } }
        assertEquals(7, db.counted.maxRead!!.rowsRead)
        db.close()
    }

    // A writer's label travels with its coroutine onto Room's transaction
    // thread; each transaction is told with its rows and how it ended.
    @Test
    fun transactionsCarryTheirWritersLabel() = runBlocking {
        val db = openTestCatalog()
        db.counted.recording = true
        withContext(WriteLabels.of("harness")) {
            db.withTransaction { db.dao().insertTracks((1..12).map(::track)) }
        }
        runCatching {
            withContext(WriteLabels.of("failing")) {
                db.withTransaction {
                    db.dao().insertTracks(listOf(track(99)))
                    error("rolled back")
                }
            }
        }
        db.counted.recording = false
        val written = db.counted.transactions.filter { it.rowsChanged > 0 }
        assertEquals(written.toString(), listOf("harness", "failing"), written.map { it.label })
        assertEquals(12L, written[0].rowsChanged)
        assertTrue(written[0].committed)
        assertFalse(written[1].committed)
        assertTrue(written.all { it.heldNanos > 0 })
        assertEquals(12, db.dao().allTrackIds().size)
        db.close()
    }

    // Room's own trigger upkeep is bookkeeping: a table's triggers are
    // dropped at the next watch's start once its last watcher went, and
    // neither that drop nor the new watch's triggers are the app's.
    @Test
    fun roomsTriggerUpkeepIsBookkeeping() = runBlocking {
        val db = openTestCatalog()
        db.invalidationTracker.createFlow("album").first()
        db.counted.reset()
        db.counted.recording = true
        db.invalidationTracker.createFlow("track").first()
        db.counted.recording = false
        val triggers = db.counted.statements.filter { "room_table_modification_trigger" in it.sql }
        assertTrue("album's triggers dropped: $triggers", triggers.any { it.sql.contains("DROP TRIGGER", ignoreCase = true) && "_album_" in it.sql })
        assertTrue("track's triggers made: $triggers", triggers.any { it.sql.contains("CREATE TEMP TRIGGER", ignoreCase = true) && "_track_" in it.sql })
        assertEquals(emptyList<String>(), db.counted.own.map { it.sql }.filter { "room_table_modification_trigger" in it })
        db.close()
    }
}

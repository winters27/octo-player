package app.winters.octo.listening

import app.winters.octo.catalog.PlayEventEntity
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// One play costs one song's rows: after the first rebuild, a play and its
// refresh read that song's phone plays, its copies and its stored row
// (counted at the database) and write its one row, however many songs
// were played. Today every play reads every played song again.
@RunWith(RobolectricTestRunner::class)
class PlayTotalsRefreshResidencyTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun onePlayReadsOneSongAndWritesOneRow() = timed(30) {
        db.fillPlays(seed = 31, songs = 4_000, albums = 200)
        val rig = TotalsRig(db, db.testMerge(HOME)).started()
        val song = "t00004"
        val before = rig.dao.totalOf(song)
        val version = rig.totals.version.value
        db.counted.reset()
        db.counted.recording = true
        db.userDao().addPlay(PlayEventEntity(trackId = song, relinkKey = "", startedAt = NOW + 1, playedMs = 1_000, durationMs = 1_000))
        rig.totals.refresh(listOf(song))
        rig.totals.version.first { it > version }
        db.counted.recording = false
        val own = db.counted.own.filterNot { it.sql.startsWith("INSERT INTO `play_event`") }
        val read = own.sumOf { it.rowsRead }
        assertTrue("read $read rows: ${own.joinToString("\n")}", read <= 8)
        val written = own.filter { "play_total" in it.sql && it.rowsChanged > 0 }
        assertEquals(own.joinToString("\n"), 1, written.sumOf { it.rowsChanged })
        assertEquals((before?.plays ?: 0) + 1, rig.dao.totalOf(song)?.plays)
        assertEquals(NOW + 1, rig.dao.totalOf(song)?.phoneLastAt)
    }

    // A refreshed song the library no longer has loses its row.
    @Test
    fun aRefreshOfASongTheLibraryLacksDeletesItsRow() = timed {
        db.fillPlays(seed = 32, songs = 200, albums = 10)
        val rig = TotalsRig(db, db.testMerge(HOME)).started()
        val song = db.query("SELECT trackId FROM play_total ORDER BY trackId LIMIT 1", null).use { c -> c.moveToFirst(); c.getString(0) }
        db.exec("DELETE FROM track WHERE id = ?", song)
        val version = rig.totals.version.value
        rig.totals.refresh(listOf(song))
        rig.totals.version.first { it > version }
        assertEquals(null, rig.dao.totalOf(song))
    }
}

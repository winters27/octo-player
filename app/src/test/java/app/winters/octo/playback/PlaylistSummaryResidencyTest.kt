package app.winters.octo.playback

import app.winters.octo.listening.timed
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// However long a playlist, its summary holds one page of its rows at a
// time, counted at the database: its count and length come from one row
// each, and its covers from a walk of pages that stops at four. A playlist
// with no art walks to its end, one page at a time. Today every entry of
// every playlist is read as one list.
@RunWith(RobolectricTestRunner::class)
class PlaylistSummaryResidencyTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aLongPlaylistIsReadAPageAtATime() = timed(60) {
        val songs = 20_000
        // Art only on the last song of the list, so the walk goes to its end.
        db.dao().insertTracks((0 until songs).map { songRow("device:s%05d".format(it), "al${it / 10}", if (it == songs - 1) "art:last" else null, ms = 1_000) })
        db.playlist("long", (0 until songs).map { "device:s%05d".format(it) }, updatedAt = 2)
        db.playlist("short", (0 until 30).map { "device:s%05d".format(it * 3) }, updatedAt = 1)
        db.counted.reset()
        val (summaries, read) = db.counted.record { kotlinx.coroutines.runBlocking { db.summaries().all.first() } }
        val long = summaries.first { it.id == "long" }
        assertEquals(songs, long.songCount)
        assertEquals(songs * 1_000L, long.durationMs)
        assertEquals(listOf("art:last"), long.covers)
        assertTrue("${db.counted.maxRead}", db.counted.maxRead!!.rowsRead <= COVER_PAGE)
        val pages = read.count { "playlist_item i LEFT JOIN track" in it.sql }
        assertTrue("the walk read $pages pages", pages >= songs / COVER_PAGE)
        assertTrue("the totals are one row each", read.filter { "COUNT(*) AS n" in it.sql }.all { it.rowsRead <= 1 })
    }
}

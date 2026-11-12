package app.winters.octo.offline

import app.winters.octo.listening.timed
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// An album downloads a page at a time: a 3,000-song album, its songs
// sharing a handful of places in the album as an "Unknown album" does,
// downloads in pages of 500 read in the album's order, no statement
// yielding more than a page, with today's reasons: a song already kept is
// asked for by hand too, and a song on the phone is not downloaded. Today's
// download read the album's ids whole.
@RunWith(RobolectricTestRunner::class)
class DownloadAlbumPagedTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun anAlbumDownloadsAPageAtATime() = timed(60) {
        val ids = (0 until 3_000).map { "$IN_USE:%04d".format(it) }
        db.putLibrary(
            ids.mapIndexed { i, id -> librarySong(id, album = "unknown", order = i % 4, onPhone = i % 10 == 0) },
            ids.mapIndexed { i, id -> serverCopy(id, "n$i") },
        )
        val liked = ids.filterIndexed { i, _ -> i % 7 == 0 }
        db.putKept(liked.map { id -> keptRow(id, "n${ids.indexOf(id)}", setOf(Reasons.LIKED)) })
        val rig = RulesRig(db)

        db.counted.reset()
        db.counted.recording = true
        rig.rules.downloadAlbum("unknown", refused = null)
        db.counted.recording = false

        val most = db.counted.maxRead!!
        assertTrue("$most", most.rowsRead <= RULES_PAGE)
        val pages = db.counted.own.filter { "ORDER BY albumOrder, id" in it.sql }
        assertEquals(7, pages.size)
        assertTrue(pages.all { it.rowsRead <= RULES_PAGE })

        // Today: download(albumTrackIds): kept songs gain the hand reason,
        // the rest download by hand when they can.
        val expected = ids.withIndex().mapNotNull { (i, id) ->
            when {
                i % 7 == 0 -> id to setOf(Reasons.LIKED, Reasons.MANUAL)
                i % 10 == 0 -> null
                else -> id to setOf(Reasons.MANUAL)
            }
        }.toMap()
        assertEquals(expected, db.heldNow())
    }
}

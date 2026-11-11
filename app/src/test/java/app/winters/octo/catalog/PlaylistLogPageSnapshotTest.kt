package app.winters.octo.catalog

import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A walk of a pinned set reads it as it stood when it was pinned, whatever
// single edits land between its pages: rows moved across its cursor either
// way, rows removed ahead of it, a removal and its Undo on either side, a
// row removed before the pin and put back during the walk, and songs added
// meanwhile. Each page is one statement over the set and its edit log.
@RunWith(RobolectricTestRunner::class)
class PlaylistLogPageSnapshotTest {
    private val db = openTestCatalog()
    private val sets = PlaylistSets(db)
    private val edits = Edits(db, sets, "p")

    @After
    fun close() = db.close()

    @Test
    fun aWalkReadsTheSetAsItStood() = timed(60) {
        sets.playlistOf("p", 3_000)
        // Removed before the pin, put back during the walk: not in the set then.
        val early = edits.remove(edits.rowAt(2_500).itemKey)
        val baseline = edits.rows().map { it.trackId }
        val set = sets.pin(WorkOwners.marked("queue:1"), "p")!!
        var ahead: PlaylistItemEntity? = null
        val walked = sets.walked(set, 7) { page ->
            if (page == 20) {
                // Removed ahead of the cursor at page 7, put back now the walk has passed its place.
                edits.restore(ahead!!)
                return@walked
            }
            if (page != 7) return@walked
            val rows = edits.rows()
            // Ahead of the cursor, moved to the front; behind it, moved far ahead.
            edits.move(rows[2_000].itemKey, rows[0].ord - ORD_STEP)
            edits.move(rows[10].itemKey, rows[2_600].ord + 1)
            // Ahead of the cursor, removed; and one removed and put back at once.
            edits.remove(rows[1_500].itemKey)
            edits.restore(edits.remove(rows[1_700].itemKey))
            ahead = edits.remove(rows[100].itemKey)
            // Behind the cursor, removed and put back.
            edits.restore(edits.remove(rows[20].itemKey))
            edits.restore(early)
            edits.add("added")
            // A row moved twice keeps its place as it stood.
            edits.move(rows[2_200].itemKey, rows[5].ord + 1)
            edits.move(rows[2_200].itemKey, rows[2_900].ord + 1)
        }
        assertEquals(baseline, walked)
        // A walk pinned now reads the set as it is now.
        val now = sets.pin(WorkOwners.marked("queue:2"), "p")!!
        assertEquals(edits.rows().map { it.trackId }, sets.walked(now, 500))
    }
}

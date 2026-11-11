package app.winters.octo.catalog

import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A Play's start row is found inside the pin's own transaction, where the
// set's rows are the walk's baseline: by its key, or when a sync gave every
// row a fresh key meanwhile, by its song's first row. A move of that row
// during the walk then leaves the song once in the walk, at its place as
// it stood.
@RunWith(RobolectricTestRunner::class)
class PlaylistStartKeyInPinTest {
    private val db = openTestCatalog()
    private val sets = PlaylistSets(db)
    private val edits = Edits(db, sets, "p")

    @After
    fun close() = db.close()

    @Test
    fun theStartRowIsFoundInThePin() = timed(30) {
        sets.playlistOf("p", 1_000)
        val tapped = edits.rowAt(5)
        // A pull that gives every row a fresh key: a new set, shown.
        sets.withSet("p") {
            val set = sets.writeSet("p") { emit -> emit(edits.rows().map { NewItem(it.trackId, it.relinkKey) }) }!!
            db.exec("UPDATE playlist SET itemSet = ? WHERE id = 'p'", set)
            sets.deleteSet("p", 1)
        }
        val (set, start) = sets.pin(WorkOwners.marked("queue:1"), "p") { pinned -> sets.rekey(pinned, tapped.itemKey) to sets.keyOf(pinned, tapped.trackId) }!!
        assertNull("the tapped row's key is gone", start.first)
        val at = start.second!!
        assertEquals(edits.rowAt(5).let { RowAt(it.ord, it.itemKey) }, at)
        val walked = sets.walked(set, 7) { page ->
            if (page == 2) {
                val rows = edits.rows()
                edits.move(at.itemKey, rows.last().ord + 1)
            }
        }
        assertEquals(1, walked.count { it == tapped.trackId })
        assertEquals(5, walked.indexOf(tapped.trackId))
    }
}

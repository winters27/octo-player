package app.winters.octo.ui.playlist

import app.winters.octo.catalog.NewItem
import app.winters.octo.catalog.PlaylistEdits
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.SongToAdd
import app.winters.octo.catalog.playlistOf
import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// An add's Undo takes back exactly the rows the add wrote, by the range of
// row keys it took: 1,200 rows in three transactions into P, with adds to
// another playlist landing between its pages, go and nothing else does; a
// family pull after the add keeps the rows' keys, so they can still be
// taken back; a pull that is not the family's gives them fresh keys and
// leaves nothing to take back.
@RunWith(RobolectricTestRunner::class)
class PlaylistAddUndoKeysTest {
    private val db = openTestCatalog()
    private val sets = PlaylistSets(db)
    private val edits = PlaylistEdits(db, sets)

    @After
    fun close() = db.close()

    private suspend fun addBetweenOthers(): app.winters.octo.catalog.AddedRows {
        var page = 0
        return edits.add("p", { emit ->
            for (chunk in (1_000 until 2_200).chunked(400)) {
                emit(chunk.map { SongToAdd("song$it", "k$it") })
                // Another playlist's add lands between this add's pages.
                edits.add("q", { it(listOf(SongToAdd("song${3_000 + page}", "k"), SongToAdd("song${3_100 + page++}", "k"))) }, distinct = false, newOnly = false, now = 2L)
            }
        }, distinct = false, newOnly = false, now = 1L)!!
    }

    private suspend fun setup(): Pair<List<String>, List<String>> {
        db.songs(0 until 4_000)
        sets.playlistOf("p", 500)
        sets.playlistOf("q", 50, prefix = "song")
        return db.shownRows("p").map { it.trackId } to db.shownRows("q").map { it.trackId }
    }

    @Test
    fun theUndoTakesBackExactlyTheAdd() = timed(60) {
        val (p, q) = setup()
        val added = addBetweenOthers()
        assertEquals(1_200, added.count)
        assertEquals(p + (1_000 until 2_200).map { "song$it" }, db.shownRows("p").map { it.trackId })
        val qAfter = db.shownRows("q").map { it.trackId }
        assertEquals(q.size + 6, qAfter.size)
        edits.undoAdd("p", added, now = 3L)
        assertEquals(p, db.shownRows("p").map { it.trackId })
        assertEquals("the other playlist's adds stay", qAfter, db.shownRows("q").map { it.trackId })
    }

    @Test
    fun aFamilyPullKeepsTheAddUndoable() = timed(60) {
        val (p, _) = setup()
        val added = addBetweenOthers()
        // The server sends the playlist back with a song of its own first.
        sets.writeBack(db, "p", listOf(NewItem("song3999", "k")) + db.shownRows("p").kept())
        edits.undoAdd("p", added, now = 3L)
        assertEquals(listOf("song3999") + p, db.shownRows("p").map { it.trackId })
    }

    @Test
    fun aPullThatIsNotTheFamilysLeavesNothingToUndo() = timed(60) {
        val (p, _) = setup()
        val added = addBetweenOthers()
        val pulled = db.shownRows("p").map { NewItem(it.trackId, it.relinkKey) }
        sets.writeBack(db, "p", pulled)
        edits.undoAdd("p", added, now = 3L)
        assertEquals(p + (1_000 until 2_200).map { "song$it" }, db.shownRows("p").map { it.trackId })
    }
}

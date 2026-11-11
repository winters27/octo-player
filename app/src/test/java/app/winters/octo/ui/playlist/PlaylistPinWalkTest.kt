package app.winters.octo.ui.playlist

import app.winters.octo.catalog.NewItem
import app.winters.octo.catalog.PlaylistEdits
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.SongToAdd
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.playlistOf
import app.winters.octo.listening.timed
import app.winters.octo.playback.ListSpec
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.paging.ListLoaders
import app.winters.octo.ui.paging.PlaylistLoader
import app.winters.octo.ui.paging.RowKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Every walk of a playlist reads the set it pinned: a sync's write-back
// that shows a new set of a 3,000-row playlist at page 7 of a walk leaves
// the walk reading the old set to its end, whether the walk is an add from
// the playlist, the picker's count of it (into another playlist or into
// itself), a duplicate of it or a page of its own screen. The old set's
// rows stay while a pin holds them and go once the last pin is let go.
@RunWith(RobolectricTestRunner::class)
class PlaylistPinWalkTest {
    private val db = openTestCatalog()
    private val sets = PlaylistSets(db)
    private val edits = PlaylistEdits(db, sets)

    @After
    fun close() = db.close()

    // A write-back of 1,000 other songs.
    private suspend fun flip() = sets.writeBack(db, "p", (5_000 until 6_000).map { NewItem("song$it", "k$it") })

    private suspend fun setup(): List<String> {
        db.songs(0 until 6_000)
        sets.playlistOf("p", 3_000)
        return db.shownRows("p").map { it.trackId }
    }

    @Test
    fun anAddAndTheScreensPagesReadTheSetTheyPinned() = timed(60) {
        val old = setup()
        val oldSet = db.shownRows("p").first().itemSet
        sets.playlistOf("q", 0)
        val loader = PlaylistLoader(db, sets, "p", WorkOwners.marked("walk:add"))
        var page = 0
        var flipped = 0L
        edits.add("q", { emit ->
            var after: RowKey? = null
            while (true) {
                val rows = loader.after(after, 7)
                emit(rows.map { SongToAdd(it.row.track.id, it.row.track.relinkKey) })
                if (++page == 7) flipped = flip()
                if (rows.size < 7) break
                after = rows.last().key
            }
        }, distinct = false, newOnly = false, now = 1L)
        assertTrue(flipped > oldSet)
        assertEquals("the add brought the set as it stood", old, db.shownRows("q").map { it.trackId })
        assertEquals("the pinned set stays", 3_000L, db.rowsOfSet("p", oldSet))

        // A screen's generation reads its own pinned set across pages too.
        val screen = PlaylistLoader(db, sets, "p", WorkOwners.marked("walk:screen"))
        val read = ArrayList(screen.after(null, 100))
        sets.writeBack(db, "p", (0 until 10).map { NewItem("song$it", "k$it") })
        while (true) {
            val next = screen.after(read.last().key, 100)
            read += next
            if (next.size < 100) break
        }
        assertEquals((5_000 until 6_000).map { "song$it" }, read.map { it.row.track.id })

        loader.retire()
        sets.idle()
        assertEquals("the old set goes with its last pin", 0L, db.rowsOfSet("p", oldSet))
        screen.retire()
        sets.idle()
    }

    @Test
    fun aDuplicateReadsTheSetItPinned() = timed(60) {
        val old = setup()
        val oldSet = db.shownRows("p").first().itemSet
        val pinned = sets.pin(WorkOwners.marked("walk:copy"), "p")!!
        try {
            sets.create(PlaylistEntity("copy", "Copy", 1, 1)) { emit ->
                var page = 0
                sets.walk(pinned, 7) { rows ->
                    emit(rows.map { NewItem(it.trackId, it.relinkKey, it.serverSongId) })
                    if (++page == 7) flip()
                }
            }
            assertEquals("pinned while the copy reads", 3_000L, db.rowsOfSet("p", oldSet))
        } finally {
            sets.releasePin(WorkOwners.marked("walk:copy"), "p")
        }
        assertEquals(old, db.shownRows("copy").map { it.trackId })
        sets.idle()
        assertEquals(0L, db.rowsOfSet("p", oldSet))
    }

    @Test
    fun thePickersCountReadsTheSetItPinnedIntoAnotherPlaylistAndIntoItself() = timed(60) {
        setup()
        val oldSet = db.shownRows("p").first().itemSet
        sets.playlistOf("q", 0)
        val sources = AddSources(db, dagger.Lazy<ListLoaders> { error("a playlist is counted from its set") }, WorkOwners(), sets)
        var flips = 0
        sources.counted = { chunk -> if (chunk == 1 && flips++ == 0) flip() }
        val (intoOther, _) = sources.check("q", AddSource.Spec(ListSpec.Playlist("p")))
        assertEquals("every song of the old set is new to q", AddCounts(3_000, 3_000, 0), intoOther)

        // Into itself: the target's pin and the source's pin both hold the
        // set they took, while a write-back shows another between chunks.
        sets.writeBack(db, "p", (0 until 3_000).map { NewItem("song$it", "k$it") })
        val held = db.shownRows("p").first().itemSet
        flips = 0
        val (intoItself, _) = sources.check("p", AddSource.Spec(ListSpec.Playlist("p")))
        assertEquals("the old set's songs, all held already", AddCounts(3_000, 0, 3_000), intoItself)
        sets.idle()
        assertEquals(0L, db.rowsOfSet("p", oldSet))
        assertEquals(0L, db.rowsOfSet("p", held))
    }
}

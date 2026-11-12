package app.winters.octo.catalog

import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A set's edit log goes, a page at a time, when its last pin is let go.
// A walk that pins the set while the log is going (a Play, with a baseline
// of its own) stops the rest from going, so an edit after its pin is still
// read as it stood; the log goes once that walk lets go too.
@RunWith(RobolectricTestRunner::class)
class PlaylistLogClearPinTest {
    private val db = openTestCatalog()
    private val sets = PlaylistSets(db)
    private val edits = Edits(db, sets, "p")

    @After
    fun close() = db.close()

    private fun logRows() = db.longOf("SELECT COUNT(*) FROM playlist_edit_log")

    @Test
    fun aPinDuringTheClearKeepsWhatItNeeds() = timed(60) {
        sets.playlistOf("p", 700)
        sets.pin(WorkOwners.marked("vm:1"), "p")
        // More than a page of log rows: every one of 600 rows moved behind the last.
        val rows = edits.rows()
        for (i in 0 until 600) edits.move(rows[i].itemKey, rows.last().ord + 1 + i)
        assertEquals(600L, logRows())
        var played: PinnedSet? = null
        var asItStood: List<String>? = null
        sets.hooks = object : SetHooks() {
            override suspend fun clearedLogPage(playlistId: String, itemSet: Long) {
                if (played != null) return
                played = sets.pin(WorkOwners.marked("queue:1"), "p")
                asItStood = edits.rows().map { it.trackId }
                val now = edits.rows()
                edits.move(now[50].itemKey, now[0].ord - 1)
            }
        }
        sets.releasePin(WorkOwners.marked("vm:1"), "p")
        assertEquals("the clear stopped at the new pin, with its edit kept", 101L, logRows())
        assertEquals(asItStood, sets.walked(played!!, 7))
        sets.releasePin(WorkOwners.marked("queue:1"), "p")
        assertEquals(0L, logRows())
    }

    // With no pin, an edit writes no log row; with one of any owner, it does.
    @Test
    fun anEditLogsOnlyWhileASetIsPinned() = timed(30) {
        sets.playlistOf("p", 10)
        val rows = edits.rows()
        edits.move(rows[0].itemKey, rows[9].ord + 1)
        edits.restore(edits.remove(rows[1].itemKey))
        assertEquals(0L, logRows())
        sets.pin(WorkOwners.marked("export:1"), "p")
        edits.move(rows[2].itemKey, rows[9].ord + 2)
        val removed = edits.remove(rows[3].itemKey)
        edits.restore(removed)
        edits.add("new")
        assertEquals(
            listOf("0|${rows[2].itemKey}|${rows[2].ord}|song2", "1|${removed.itemKey}|${removed.ord}|song3", "2|${removed.itemKey}|${removed.ord}|song3"),
            db.query("SELECT op || '|' || itemKey || '|' || ord || '|' || trackId FROM playlist_edit_log ORDER BY seq", arrayOf<Any?>())
                .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } },
        )
        sets.releasePin(WorkOwners.marked("export:1"), "p")
        assertEquals(0L, logRows())
    }

    // Two walks of one set: the log stays until the last lets go.
    @Test
    fun theLogStaysUntilTheLastPin() = timed(30) {
        sets.playlistOf("p", 10)
        sets.pin(WorkOwners.marked("vm:1"), "p")
        sets.pin(WorkOwners.marked("walk:2"), "p")
        val rows = edits.rows()
        edits.move(rows[0].itemKey, rows[9].ord + 1)
        sets.releasePin(WorkOwners.marked("walk:2"), "p")
        assertEquals(1L, logRows())
        sets.releasePin(WorkOwners.marked("vm:1"), "p")
        assertEquals(0L, logRows())
    }
}

package app.winters.octo.offline

import app.winters.octo.catalog.Edits
import app.winters.octo.catalog.NewItem
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.longOf
import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A playlist's download reads its pinned set as it stood when the download
// began, through the edit log: a song moved from ahead of its cursor to
// behind it, and a song removed ahead of it, at its third page, are still
// downloaded, as the playlist held them when the download was asked for.
// Read as the set is now, page by page, both were missed.
@RunWith(RobolectricTestRunner::class)
class DownloadPlaylistDuringEditTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aDownloadIsThePlaylistAsItStood() = timed(30) {
        val songs = (0 until 100).map { "server:h:t$it" }
        db.putServerSongs(songs)
        val rig = RulesRig(db, page = 10)
        rig.sets.create(PlaylistEntity("p", "P", 1, 1)) { emit -> emit(songs.map { NewItem(it, "key:$it") }) }
        val edits = Edits(db, rig.sets, "p")
        rig.rules.hooks = object : RulesHooks() {
            override suspend fun downloading(page: Int) {
                if (page != 3) return
                val rows = edits.rows()
                edits.move(rows[80].itemKey, rows[0].ord - 1)
                edits.remove(rows[90].itemKey)
            }
        }
        rig.rules.downloadPlaylist("p", refused = null)
        assertEquals(songs.associateWith { setOf(Reasons.MANUAL) }, db.heldNow())
        assertEquals("the log went with the download's pin", 0L, db.longOf("SELECT COUNT(*) FROM playlist_edit_log"))
    }
}

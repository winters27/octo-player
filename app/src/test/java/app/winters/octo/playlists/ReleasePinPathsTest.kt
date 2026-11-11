package app.winters.octo.playlists

import app.winters.octo.catalog.Edits
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.exec
import app.winters.octo.catalog.longOf
import app.winters.octo.catalog.playlistOf
import app.winters.octo.listening.timed
import app.winters.octo.offline.RulesRig
import app.winters.octo.playback.summaries
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Every walk that pins a playlist's set lets its pin go through
// PlaylistSets.releasePin, the one helper that also lets the set's edit log
// go at its last pin: a download's end, a summary's cover walk, and a sync
// step's snapshot each leave the log while another walk (a page's) still
// holds the set, and it goes with that last pin.
@RunWith(RobolectricTestRunner::class)
class ReleasePinPathsTest {
    @Test
    fun everyPathLetsGoThroughTheHelper() = timed(60) {
        SyncRig(PlaylistWorld(1)).use { rig ->
            val db = rig.db
            val sets = rig.sets
            sets.playlistOf("p", 30)
            db.exec("UPDATE playlist SET sourceId = ? WHERE id = 'p'", SOURCE)
            val edits = Edits(db, sets, "p")
            fun log() = db.longOf("SELECT COUNT(*) FROM playlist_edit_log")
            fun pins() = db.longOf("SELECT COUNT(*) FROM playlist_set_pin")
            sets.pin(WorkOwners.marked("vm:1"), "p")
            val rows = edits.rows()
            edits.move(rows[0].itemKey, rows.last().ord + 1)
            assertEquals(1L, log())

            // A download's end.
            val download = RulesRig(db, page = 10)
            download.rules.downloadPlaylist("p", refused = null)
            assertEquals(1L to 1L, pins() to log())

            // A summary's cover walk.
            db.summaries(sets = sets).all.first()
            assertEquals(1L to 1L, pins() to log())

            // A sync step's snapshot (a new playlist made on the server).
            rig.steps.run(rig.serverOf(false), PlaylistStep.Create("p"))
            assertEquals(1L to 1L, pins() to log())

            sets.releasePin(WorkOwners.marked("vm:1"), "p")
            assertEquals(0L to 0L, pins() to log())
        }
    }
}

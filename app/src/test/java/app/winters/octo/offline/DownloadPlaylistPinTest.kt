package app.winters.octo.offline

import app.winters.octo.catalog.longOf
import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A playlist's download keeps one item set: a new list of songs, written
// and made current at its seventh page, leaves the download on the set it
// pinned as it began, whole, so the downloads are the old set's songs; the
// pin holds the set through every page and goes, through the one way pins
// are let go, when the download ends. Today's download read the playlist
// whole.
@RunWith(RobolectricTestRunner::class)
class DownloadPlaylistPinTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aPlaylistsDownloadStaysOnTheSetItPinned() = timed(30) {
        val p = PinnedPlaylist(db).apply { put() }
        val rig = RulesRig(db, page = 10)
        val pins = ArrayList<Long>()
        rig.rules.hooks = object : RulesHooks() {
            override suspend fun downloading(page: Int) {
                pins += p.oldSetPins()
                if (page == 7) p.flip()
            }
        }
        rig.rules.downloadPlaylist("p", refused = null)
        assertEquals(p.old.associateWith { setOf(Reasons.MANUAL) }, db.heldNow())
        assertEquals(List(10) { 1L }, pins)
        assertEquals(listOf("p"), rig.released.map { it.second })
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM playlist_set_pin"))
    }
}

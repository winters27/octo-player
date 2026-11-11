package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.listening.PlayedIn
import app.winters.octo.testing.counted
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// An album as big as the library (20,000 songs) played from its menu, put
// next, and started as a radio reads at most a page of its songs per
// statement, counted at the database: no route reads the album whole. On
// a 30-song album the menu's play is today's: the album in its order.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class AlbumMenuResidencyTest {
    private val page = 500
    private val rig = QueueRig(page = page)

    @After
    fun close() = rig.close()

    @Test
    fun aHugeAlbumsMenuReadsAPageAtATime() {
        rig.db.songs(20_000, album = "huge")
        rig.builds.pool = { _, _, _, _ -> emptyList() }
        val log = rig.db.counted
        log.reset()
        log.recording = true
        // Play, as the menu plays it.
        rig.play(PlayRequest(ListSpec.Album("huge", withFinds = false), null))
        rig.joined()
        rig.until("the queue counts the album") { rig.window.logicalCount.value == 20_000 }
        // Play next of the whole album.
        rig.run { rig.window.addList(ListSpec.Album("huge", withFinds = false), next = true, source = null) }
        rig.joined()
        // The album's radio: its seed from the stored plays or its first song.
        val seed = rig.run { RadioSeeds(rig.db).of(PlayedIn.Album("huge")) }!!
        rig.play(PlayRequest(ListSpec.Radio(seed.id, "huge radio", RadioAround.Album("huge"))))
        rig.joined()
        log.recording = false
        val statements = log.own
        assertTrue("nothing was recorded", statements.size > 20)
        val biggest = statements.maxBy { it.rowsRead }
        assertTrue("one statement read ${biggest.rowsRead}: $biggest", biggest.rowsRead <= page + QUEUE_WINDOW + 1)
    }

    @Test
    fun aSmallAlbumPlaysInItsOrder() {
        val songs = rig.db.songs(30, album = "small").map { it.id }
        rig.play(PlayRequest(ListSpec.Album("small", withFinds = false), null))
        rig.settle()
        assertEquals(songs, rig.held())
    }
}

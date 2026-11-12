package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.SongToAdd
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// While one playlist's queue build walks it, edits of that playlist and of
// another all land at once: the build holds no playlist's lock, so nothing
// waits for it, and its queue is its playlist as at Play.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class PlaylistEditOtherDuringBuildTest {
    private val p = PlaylistBuildRig(700)

    @After
    fun close() = p.close()

    @Test
    fun editsOfBothPlaylistsDuringTheBuildLandAtOnce() {
        val atPlay = p.songs()
        val pRows = p.rows()
        val qRows = p.rows("q")
        val more = p.rig.db.songs(1_200, prefix = "m")
        val landed = ArrayList<String>()
        p.rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (page != 2) return
                // Each runs inside the build's page and ends there: none waits.
                p.edits.move("p", pRows[10].itemKey, pRows[600].itemKey, now = 2)
                landed += "move p"
                val gone = p.edits.remove("p", pRows[20].itemKey, now = 2)!!
                p.edits.restore("p", gone, now = 3)
                landed += "remove and undo p"
                p.edits.remove("q", qRows[5].itemKey, now = 2)
                landed += "remove q"
                p.edits.move("q", qRows[6].itemKey, qRows[400].itemKey, now = 2)
                landed += "move q"
                val added = p.edits.add("q", { emit -> more.chunked(500).forEach { chunk -> emit(chunk.map { SongToAdd(it.id, it.relinkKey) }) } }, distinct = false, newOnly = false, now = 4)!!
                p.edits.undoAdd("q", added, now = 5)
                landed += "add and undo q"
            }
        }
        p.rig.play(PlayRequest(ListSpec.Playlist("p"), null, shownOverWindow = true))
        p.rig.joined()
        assertEquals(listOf("move p", "remove and undo p", "remove q", "move q", "add and undo q"), landed)
        assertEquals(atPlay, p.rig.songs())
        assertTrue(p.songs("q").size == 699)
    }
}

package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Moves in a playlist while its queue build walks it land at once, never
// wait for the build, never start it over, and change nothing in the
// queue: the build reads the set as it stood when it pinned it, through the
// set's edit log, so the queue is the playlist as it was at Play.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class PlaylistMoveDuringBuildTest {
    private val p = PlaylistBuildRig(900)

    @After
    fun close() = p.close()

    @Test
    fun threeMovesDuringTheBuildLandAtOnceAndLeaveTheQueueAsAtPlay() {
        val atPlay = p.songs()
        val keys = p.rows().map { it.itemKey }
        val moved = ArrayList<Int>()
        p.rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (page in listOf(2, 5, 9)) {
                    // Across the walk's place both ways, and far ahead.
                    val (from, to) = when (page) {
                        2 -> keys[800] to keys[10]
                        5 -> keys[20] to keys[700]
                        else -> keys[400] to keys[899]
                    }
                    p.edits.move("p", from, to, now = 2)
                    moved += page
                }
            }
        }
        p.rig.play(PlayRequest(ListSpec.Playlist("p"), null, shownOverWindow = true))
        p.rig.joined()
        assertEquals(listOf(2, 5, 9), moved)
        assertNotEquals("the moves changed the playlist", atPlay, p.songs())
        assertEquals(atPlay, p.rig.songs())
        assertEquals(0, p.rig.lists().single().attempts)
        assertTrue(p.rig.window.building.value.isEmpty())
    }
}

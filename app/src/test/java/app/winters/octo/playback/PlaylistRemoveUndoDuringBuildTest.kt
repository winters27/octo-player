package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A playlist row removed ahead of a queue build's walk, then put back by
// Undo once the walk has passed its place, is queued once, at its place as
// the playlist stood at Play.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class PlaylistRemoveUndoDuringBuildTest {
    private val p = PlaylistBuildRig(800)

    @After
    fun close() = p.close()

    @Test
    fun aRemoveAheadThenUndoBehindQueuesTheSongOnceInItsPlace() {
        val atPlay = p.songs()
        val target = p.rows()[300]
        var removed: app.winters.octo.catalog.PlaylistItemEntity? = null
        p.rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                when (page) {
                    1 -> removed = p.edits.remove("p", target.itemKey, now = 2)
                    10 -> p.edits.restore("p", removed!!, now = 3)
                }
            }
        }
        p.rig.play(PlayRequest(ListSpec.Playlist("p"), null, shownOverWindow = true))
        p.rig.joined()
        assertEquals(atPlay, p.rig.songs())
        assertEquals(1, p.rig.songs().count { it == target.trackId })
        assertEquals(atPlay, p.songs())
    }
}

package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.SongToAdd
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Every kind of edit of a playlist while its queue build walks it: a move
// across the walk's place each way, a move of the start's row, a remove, a
// restore and an add. Each lands at once, and the queue holds each row of
// the set as it stood at Play, once.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class BuildPlaylistEditTest {
    private val p = PlaylistBuildRig(700)

    @After
    fun close() = p.close()

    @Test
    fun editsOfEveryKindDuringTheBuildLeaveTheQueueAsAtPlay() {
        val rows = p.rows()
        val atPlay = rows.map { it.trackId }
        val start = rows[250]
        val extra = p.rig.db.songs(5, prefix = "x")
        var removed: app.winters.octo.catalog.PlaylistItemEntity? = null
        p.rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                when (page) {
                    1 -> p.edits.move("p", rows[600].itemKey, rows[100].itemKey, now = 2)
                    2 -> p.edits.move("p", rows[50].itemKey, rows[650].itemKey, now = 2)
                    3 -> p.edits.move("p", start.itemKey, rows[0].itemKey, now = 2)
                    4 -> removed = p.edits.remove("p", rows[500].itemKey, now = 2)
                    5 -> p.edits.restore("p", removed!!, now = 3)
                    6 -> p.edits.add("p", { emit -> emit(extra.map { SongToAdd(it.id, it.relinkKey) }) }, distinct = false, newOnly = false, now = 4)
                }
            }
        }
        val key = app.winters.octo.ui.paging.playlistKey(start.ord, start.itemKey)
        p.rig.play(PlayRequest(ListSpec.Playlist("p"), ListStart.Row(start.trackId, key), shownOverWindow = true))
        p.rig.joined()
        assertEquals(atPlay, p.rig.songs())
        assertEquals(start.trackId, p.rig.current())
    }
}

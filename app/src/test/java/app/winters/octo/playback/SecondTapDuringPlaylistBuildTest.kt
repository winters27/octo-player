package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.queueQuery
import app.winters.octo.testing.addSongs
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A second tap while a big playlist is still being written: the first
// build stops, its queue and its pin go, and the second list plays and
// joins whole.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class SecondTapDuringPlaylistBuildTest {
    private val rig = QueueRig()

    @After
    fun close() = rig.close()

    private fun count(sql: String, vararg args: Any?): Long = rig.run { rig.db.queueDao().number(queueQuery(sql, *args)) ?: 0 }

    @Test
    fun theFirstBuildStopsAndTheSecondPlays() {
        val songs = rig.db.songs(700)
        rig.run {
            rig.db.userDao().insertPlaylist(PlaylistEntity("p", "P", 1, 1))
            rig.db.addSongs("p", songs, now = 1)
        }
        val gate = CompletableDeferred<Unit>()
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (page >= 1) gate.await()
            }
        }
        rig.play(PlayRequest(ListSpec.Playlist("p"), null, shownOverWindow = true))
        val first = rig.window.queueId
        rig.until("the first build wrote a page") { count("SELECT COUNT(*) FROM queue_entry WHERE queueId = ?1", first) > 1 }
        rig.builds.buildHooks = BuildHooks()
        rig.play(PlayRequest(ListSpec.Album("al", withFinds = false), ListStart.Row("s3", null), shownOverWindow = true))
        gate.complete(Unit)
        rig.joined()
        assertEquals("s3", rig.current())
        assertEquals(700, rig.songs().size)
        rig.until("the first queue went") { count("SELECT COUNT(*) FROM queue_entry WHERE queueId = ?1", first) == 0L }
        rig.until("its lists went") { count("SELECT COUNT(*) FROM queue_list WHERE queueId = ?1", first) == 0L }
    }
}

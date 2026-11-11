package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.offline.DownloadStatus
import app.winters.octo.offline.downloadRow
import app.winters.octo.sort.DownloadSort
import app.winters.octo.sort.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Downloads finishing, and a finished one finishing again with another
// size, while a queue build walks Downloads by size move nothing in the
// queue: the build walks its own copy, made at Play in the downloads' own
// key order, so every song is queued once, in the copy's order.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class DownloadFinishDuringBuildTest {
    private val rig = QueueRig(serverInUse = "server:h")

    @After
    fun close() = rig.close()

    @Test
    fun finishesDuringTheBuildMoveNothing() {
        rig.db.songs(1_000)
        rig.run {
            val done = (0 until 700).map { i ->
                downloadRow("s$i", serverId = "n$i", state = DownloadStatus.Done, path = "/m/$i.flac").copy(sizeBytes = ((i * 7919L) % 1000) * 1_000)
            }
            val queued = (700 until 1_000).map { i -> downloadRow("s$i", serverId = "n$i") }
            rig.db.downloadDao().upsert(done + queued)
        }
        val order = SortOrder(DownloadSort.Size, true)
        val atPlay = rig.run {
            @Suppress("UNCHECKED_CAST")
            val loader = rig.loaders.downloads(order, "server:h") as app.winters.octo.ui.paging.ListLoader<Any>
            loader.after(null, 10_000).map { songOf(loader, it.row)!! }
        }.filter { it.removePrefix("s").toInt() < 700 } // A play holds the finished ones, as today.
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (page > 8) return
                val dao = rig.db.downloadDao()
                // A queued one finishes, and a finished one finishes again bigger.
                dao.finish("server:h", "n${700 + page}", "/m/${700 + page}.flac", 5_000_000L, null, null)
                dao.finish("server:h", "n${page * 31}", "/m/again.flac", 9_000_000L + page, null, null)
            }
        }
        rig.play(PlayRequest(ListSpec.Downloads(order), ListStart.Row(atPlay[350], null), shownOverWindow = true))
        rig.joined()
        assertEquals(atPlay, rig.songs())
        assertEquals(700, rig.songs().toSet().size)
    }
}

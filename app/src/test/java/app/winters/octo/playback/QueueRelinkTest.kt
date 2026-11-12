package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.QueueEntryEntity
import app.winters.octo.catalog.SAVE_GROUP_FLOOR
import app.winters.octo.catalog.boundMerge
import app.winters.octo.catalog.library
import app.winters.octo.catalog.put
import app.winters.octo.device.DEVICE
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Songs the queue holds follow the library when a save renames them: in
// the save's group, which moves each queued row the find saw, and through
// the rename triggers for a row written with an old id after the rename
// is in force; and a row a list holds beside the song it becomes, which
// the group leaves, follows when the window hears that group's renames.
// A queued find and a song gone from the library are left as they are.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class QueueRelinkTest {
    private val server = "server:queue.test"
    private val db = openTestCatalog()
    private val merge = db.boundMerge(server, SAVE_GROUP_FLOOR)
    private val rig = QueueRig(db, libraryMerge = merge, serverInUse = server)

    @After
    fun close() = rig.close()

    private fun playAll() {
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false)), null))
        rig.settle()
        rig.joined()
    }

    @Test
    fun aSavesRenameReachesTheQueueInItsGroupAndThroughTheTriggers() {
        rig.run {
            rig.db.put(library(server, 20))
            merge.rebuild()
        }
        playAll()
        assertTrue("$server:s5" in rig.songs())
        // The phone gets song 5: its id becomes the phone's.
        rig.run {
            rig.db.put(library(DEVICE, 6).let { it.copy(tracks = it.tracks.filter { t -> t.nativeId == "s5" }, albums = it.albums.take(1)) })
            merge.rebuild()
        }
        rig.settle()
        val songs = rig.songs()
        assertTrue("$DEVICE:s5" in songs)
        assertTrue("$server:s5" !in songs)
        // A row written later with the old id lands on the new one.
        val queueId = rig.window.queueId
        rig.run { rig.db.queueDao().putEntries(listOf(QueueEntryEntity(queueId, entryOf(9_000, 0), "$server:s5", 9_000, 1, 1, 0))) }
        assertEquals("$DEVICE:s5", rig.rows().single { it.entry == entryOf(9_000, 0) }.trackId)
    }

    @Test
    fun aRowLeftBesideItsNewSongFollowsWhenTheWindowHearsTheGroup() {
        rig.run {
            rig.db.put(library(server, 10))
            merge.rebuild()
        }
        playAll()
        val queueId = rig.window.queueId
        // A list holding a song under an old id and under the id it becomes,
        // and a queued find: the group's guard leaves the old row.
        rig.run {
            rig.db.queueDao().putEntries(
                listOf(
                    QueueEntryEntity(queueId, entryOf(9_000, 0), "old:1", 9_000, 1, 1, 0),
                    QueueEntryEntity(queueId, entryOf(9_000, 1), "new:1", 9_001, 2, 1, 0),
                    QueueEntryEntity(queueId, entryOf(9_000, 2), "find:abc", 9_002, 3, 1, 0),
                ),
            )
        }
        rig.window.swapped(mapOf("old:1" to "new:1", "gone:2" to "$server:s1"))
        rig.settle()
        val rows = rig.rows().filter { it.entry >= entryOf(9_000, 0) }
        assertEquals(listOf("new:1", "new:1", "find:abc"), rows.map { it.trackId })
        assertTrue("$server:s1" in rig.songs())
    }
}

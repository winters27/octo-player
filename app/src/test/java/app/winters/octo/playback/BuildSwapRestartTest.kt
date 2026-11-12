package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A build over the library's own tables compares the library's swap count
// inside each page's transaction: a swap before a page makes it start over
// by number, writing nothing that page; after two such starts the third
// walks inside the merge lock, where no swap lands, so it ends. The start
// entry the player holds is never written again, and the queue holds every
// row once.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class BuildSwapRestartTest {
    private val rig = QueueRig()
    private val titles = SortOrder(SongSort.Title, false)

    @After
    fun close() = rig.close()

    @Test
    fun aSwapMidBuildStartsItOverByNumber() {
        val songs = rig.db.songs(400).map { it.id }
        var swapped = false
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (page == 3 && !swapped) {
                    swapped = true
                    rig.db.workDao().raiseSwapCount()
                }
            }
        }
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, titles), ListStart.Row("s200", null), shownOverWindow = true))
        val start = rig.rows().first { numberOfEntry(it.entry) == 0L }
        rig.joined()
        assertTrue(swapped)
        val list = rig.lists().single()
        assertEquals(1, list.attempts)
        assertEquals(songs, rig.songs())
        // The start entry is the row the opening wrote.
        assertEquals(start, rig.rows().first { numberOfEntry(it.entry) == 0L })
        assertEquals("s200", rig.current())
    }

    @Test
    fun aSwapBeforeEveryPageEndsInTheThirdAttemptUnderTheLock() {
        val songs = rig.db.songs(300).map { it.id }
        var inLock = 0
        rig.builds.buildHooks = object : BuildHooks() {
            var inside = false

            override suspend fun beforePage(listId: Long, page: Int) {
                if (!inside) rig.db.workDao().raiseSwapCount()
            }

            override suspend fun inLastAttempt(listId: Long) {
                inside = true
                inLock++
                // Nothing swaps while the walk holds the lock.
                assertTrue(rig.merge.holdsMergeLock())
            }
        }
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, titles), ListStart.Row("s10", null), shownOverWindow = true))
        rig.joined()
        assertEquals(1, inLock)
        assertEquals(2, rig.lists().single().attempts)
        assertEquals(songs, rig.songs())
    }
}

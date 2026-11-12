package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// What a big play's start counted is the rows its build expects, from the
// tap on, so the build's space check has them from its first page and Up
// next's count is the list's own while it is written: the rows written and
// those to come, each once. Once the list joined and the window made again
// around it, the server's copy of the queue hears of it.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class StartCountTest {
    private val rig = QueueRig()
    private val titles = ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false))

    @After
    fun close() = rig.close()

    @Test
    fun aShuffledStartsCountIsTheListsExpectedRows() {
        rig.db.songs(2_000)
        val read = rig.run { rig.starts.read(titles, null, shuffle = true, shownOverWindow = true, pageOwner = null) } as StartRead.Big
        assertEquals(2_000, read.count)
        val big = rig.run { rig.window.readBig(PlayRequest(titles, null, shuffle = true, shownOverWindow = true), read, 7, 9) }!!
        assertEquals(2_000L, big.list.expected)
    }

    @Test
    fun upNextCountsTheListOnceWhileItIsWritten() {
        rig.db.songs(2_000)
        val gate = CompletableDeferred<Unit>()
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (page == 4) gate.await()
            }
        }
        rig.play(PlayRequest(titles, null, shuffle = true, shownOverWindow = true))
        rig.until("the build is part way") { rig.lists().single().let { it.filled0 + it.filled1 } >= 150 }
        rig.window.refreshCount()
        rig.settle()
        assertEquals(2_000, rig.window.logicalCount.value)
        gate.complete(Unit)
        rig.joined()
        rig.settle()
        assertEquals(2_000, rig.window.logicalCount.value)
    }

    @Test
    fun aJoinReachesTheServersCopyOfTheQueue() {
        rig.db.songs(2_000)
        val heard = ArrayList<Long>()
        rig.scope.launch { rig.window.rejoined.collect { heard += it } }
        rig.play(PlayRequest(titles, ListStart.Row("s10", null), shownOverWindow = true))
        val listId = rig.lists().single().listId
        rig.joined()
        rig.until("the join was heard") { heard.isNotEmpty() }
        assertEquals(listOf(listId), heard)
        assertTrue(rig.window.building.value.isEmpty())
    }
}

package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A server switch lets the queue go while a big list is still written:
// its build stops, so it never walks the next server's library, and when
// the account comes back the queue is restored and the build goes on from
// where it stopped, to the whole list, once the stopped build let its rows
// go.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class LeaveStopsBuildsTest {
    private val rig = QueueRig()

    @After
    fun close() = rig.close()

    @Test
    fun leavingStopsTheBuildAndTheReturnFinishesIt() {
        rig.db.songs(2_000)
        val gate = CompletableDeferred<Unit>()
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (page == 2) gate.await()
            }
        }
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false)), ListStart.Row("s10", null), shownOverWindow = true))
        rig.until("the build is part way") { rig.lists().single().filled0 >= 100 }
        val listId = rig.lists().single().listId
        val generation = rig.window.queueId
        rig.window.leave()
        assertTrue("the build stopped", rig.store.builderOf(listId)?.isActive != true)
        // The switch empties the player, as the service does.
        rig.player.clearMediaItems()
        rig.settle()
        val written = rig.run { rig.store.inTurn { rig.store.dao.list(listId) } }!!.filled0
        gate.complete(Unit)
        rig.settle()
        assertEquals("nothing more is written while away", written, rig.run { rig.store.inTurn { rig.store.dao.list(listId) } }!!.filled0)
        // The account comes back.
        val saved = rig.run { rig.window.readSaved("home") }!!
        assertEquals(generation, saved.queueId)
        assertTrue(rig.window.restore(saved, "home"))
        rig.joined()
        assertEquals("s10", rig.current())
        assertEquals((0 until 2_000).map { "s$it" }, rig.songs())
    }

    // The return comes while the stopped build still lets its rows go: the
    // list builds again only once that build has ended.
    @Test
    fun theReturnBuildsOnlyAfterTheStoppedBuildLetGo() {
        rig.db.songs(2_000)
        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var left = false
        var stopped: kotlinx.coroutines.Job? = null
        var early = 0
        var pagesAfter = 0
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (!left) {
                    if (page == 2) gate.await()
                    return
                }
                pagesAfter++
                if (stopped?.isCompleted != true) early++
            }

            override suspend fun releasing(listId: Long) {
                if (!left || reached.isCompleted) return
                reached.complete(Unit)
                release.await()
            }
        }
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false)), ListStart.Row("s10", null), shownOverWindow = true))
        rig.until("the build is part way") { rig.lists().single().filled0 >= 100 }
        val listId = rig.lists().single().listId
        stopped = rig.store.builderOf(listId)
        left = true
        rig.window.leave()
        rig.until("the stopped build lets its rows go") { reached.isCompleted }
        rig.player.clearMediaItems()
        rig.settle()
        val saved = rig.run { rig.window.readSaved("home") }!!
        assertTrue(rig.window.restore(saved, "home"))
        rig.settle()
        assertEquals("nothing builds while the stopped build lets go", 0, pagesAfter)
        release.complete(Unit)
        rig.joined()
        assertTrue("the list built again", pagesAfter > 0)
        assertEquals("no page before the stopped build ended", 0, early)
        assertEquals((0 until 2_000).map { "s$it" }, rig.songs())
    }
}

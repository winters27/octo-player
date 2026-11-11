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

// Clear while a big list is still being written, the list joins while
// Clear holds its queue, then Undo: the queue comes back as Room has it
// now, with no list holding the window, so Next past the window's edge
// moves on and Save as playlist finishes with every song.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class ClearUndoAfterJoinTest {
    private val rig = QueueRig()

    @After
    fun close() = rig.close()

    @Test
    fun undoAfterTheListJoinedGoesOnPastTheWindow() {
        rig.db.songs(2_000)
        val gate = CompletableDeferred<Unit>()
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (page == 2) gate.await()
            }
        }
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false)), ListStart.Row("s10", null), shownOverWindow = true))
        rig.until("the build is part way") { rig.lists().single().filled0 >= 100 }
        val old = rig.window.queueId
        val undo = rig.run { rig.window.clear() }!!
        rig.settle()
        assertTrue(rig.window.building.value.isEmpty())
        gate.complete(Unit)
        rig.until("the cleared queue's list joined") {
            rig.run { rig.store.inTurn { rig.store.dao.listsOf(old) } }.all { it.fillState == QueueBuilds.JOINED }
        }
        // The window heard the join while Clear's queue was in use.
        rig.settle()
        undo()
        rig.until("Undo's queue holds no list's place") { rig.window.queueId == old && rig.window.building.value.isEmpty() && !rig.window.stepping }
        assertEquals("s10", rig.current())
        repeat(rig.sizes.ahead + 5) { rig.next() }
        assertEquals("s${10 + rig.sizes.ahead + 5}", rig.current())
        val saved = ArrayList<String>()
        rig.run { rig.store.savePages { page -> saved += page.map { it.trackId } } }
        assertEquals((0 until 2_000).map { "s$it" }, saved)
    }
}

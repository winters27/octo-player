package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The service dies while a big list is being written, after a Play next
// that waits for its join: the restart brings back the window as the
// listener left it (the start, then the song played next), the list goes on
// from where it stopped with no song written twice, and its join puts the
// song played next in its place in Room.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class DeathMidBuildPendingTest {
    private val rig = QueueRig()

    @After
    fun close() = rig.close()

    private fun die() {
        rig.window.release()
        rig.scope.cancel()
        rig.store.scope.cancel()
        rig.idle()
    }

    @Test
    fun aPlayNextBeforeADeathComesBackAndLandsAtTheJoin() {
        rig.db.songs(600)
        rig.db.songs(1, album = "x", prefix = "x")
        val gate = CompletableDeferred<Unit>()
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (page >= 2) gate.await()
            }
        }
        rig.play(PlayRequest(ListSpec.Album("al", withFinds = false), ListStart.Row("s150", null), shownOverWindow = true))
        rig.settle()
        rig.run { rig.window.addList(ListSpec.Ids(listOf("x0")), next = true, source = null) }
        rig.settle()
        assertEquals(listOf("s150", "x0"), rig.held())
        rig.window.saveState()
        rig.settle()
        die()

        val later = QueueRig(rig.db)
        try {
            val saved = later.run { later.window.readSaved("home") }!!
            assertTrue(saved.pending.isNotEmpty())
            assertTrue(later.window.restore(saved, "home"))
            later.settle()
            assertEquals("s150", later.current())
            assertEquals(listOf("s150", "x0"), later.held().take(2))
            later.joined()
            val songs = later.songs()
            assertEquals(601, songs.size)
            assertEquals("each song once", songs.size, songs.toSet().size)
            assertEquals("x0", songs[151])
            assertEquals("s151", songs[152])
        } finally {
            later.window.release()
        }
    }
}

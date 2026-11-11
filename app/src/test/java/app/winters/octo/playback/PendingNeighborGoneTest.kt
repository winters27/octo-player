package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A Play next made while a list is being written waits for its join; when
// the song it went after is taken out meanwhile, the join still puts it
// where it was put: after the song before that one.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class PendingNeighborGoneTest {
    private val rig = QueueRig()

    @After
    fun close() = rig.close()

    @Test
    fun aPendingInsertWhoseNeighborWentLandsInItsPlace() {
        rig.db.songs(600)
        rig.db.songs(1, album = "x", prefix = "x")
        val gate = CompletableDeferred<Unit>()
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (page >= 1) gate.await()
            }
        }
        rig.play(PlayRequest(ListSpec.Album("al", withFinds = false), ListStart.Row("s150", null), shownOverWindow = true))
        rig.settle()
        // Play next after s150, then the next song plays and s150 goes.
        rig.run { rig.window.addList(ListSpec.Ids(listOf("x0")), next = true, source = null) }
        rig.settle()
        rig.next()
        assertEquals("x0", rig.current())
        val gone = rig.player.getMediaItemAt(0).entryId!!
        rig.run { rig.window.takeOut(listOf(entryOfKey(gone)!!)) }
        rig.settle()
        gate.complete(Unit)
        rig.joined()
        val songs = rig.songs()
        assertEquals(600, songs.size)
        val i = songs.indexOf("x0")
        assertEquals("s149", songs[i - 1])
        assertEquals("s151", songs[i + 1])
    }
}

package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

// Shuffle on and off on a queue bigger than the window: the song that is on
// stays, the window is the queue around it in the new order, Next follows
// that order, and Room holds every song once whichever way.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class ShuffleToggleTest {
    // A fixed seed, so the shuffled order always has five songs after s50
    // for Next to step through. The app's own source stays SecureRandom.
    private val rig = QueueRig(random = Random(50))

    @After
    fun close() = rig.close()

    @Test
    fun theCurrentSongStaysAndNextFollowsTheNewOrder() {
        rig.db.songs(200)
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false)), ListStart.Row("s50", null), shownOverWindow = true))
        rig.joined()
        rig.player.shuffleModeEnabled = true
        rig.settle()
        assertEquals("s50", rig.current())
        val shuffled = rig.songs(QueueOrder.Shuffle)
        val at = shuffled.indexOf("s50")
        repeat(5) { i ->
            rig.next()
            assertEquals(shuffled[at + 1 + i], rig.current())
        }
        val now = rig.current()
        rig.player.shuffleModeEnabled = false
        rig.settle()
        assertEquals(now, rig.current())
        val n = now!!.removePrefix("s").toInt()
        rig.next()
        assertEquals("s${n + 1}", rig.current())
        assertEquals(200, rig.songs().toSet().size)
        assertEquals(200, rig.songs(QueueOrder.Shuffle).toSet().size)
    }
}

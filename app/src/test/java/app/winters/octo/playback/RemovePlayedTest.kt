package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.queueQuery
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

// Remove played sets the floors at the current song: the songs before it
// go from the window and from every read, Undo brings them back, and once
// the Undo is no longer offered their rows go for good and the floors
// clear. Shuffled, the songs before the current one in the shuffled order
// go, as today.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class RemovePlayedTest {
    private val rig = QueueRig()
    private val titles = SortOrder(SongSort.Title, false)

    @After
    fun close() = rig.close()

    private fun rowsInRoom(): Long = rig.run { rig.db.queueDao().number(queueQuery("SELECT COUNT(*) FROM queue_entry WHERE queueId = ?1", rig.window.queueId)) ?: 0 }

    @Test
    fun playedSongsGoUndoBringsThemBackAndExpiryDeletesThem() {
        rig.db.songs(120)
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, titles), null, shownOverWindow = true))
        rig.joined()
        repeat(30) { rig.next() }
        assertEquals("s30", rig.current())
        val undo = rig.run { rig.window.removePlayed() }!!
        rig.settle()
        assertTrue(rig.held().none { it.removePrefix("s").toInt() < 30 })
        rig.until("the count follows the floors") { rig.window.logicalCount.value == 90 }
        // Undo: the window and the count come back.
        undo()
        rig.until("the songs came back") { rig.window.logicalCount.value == 120 }
        rig.until("the window holds them") { "s29" in rig.held() }
        // Again, and this time the Undo goes.
        rig.run { rig.window.removePlayed() }!!
        rig.settle()
        assertEquals(120L, rowsInRoom())
        rig.window.expireFloors()
        rig.until("the played rows went") { rowsInRoom() == 90L }
        val account = rig.run { rig.store.account("home") }!!
        assertTrue(Floors.of(account) == Floors.NONE)
        assertEquals((30 until 120).map { "s$it" }, rig.songs())
    }

    @Test
    fun shuffledTheSongsBeforeTheCurrentInTheShuffledOrderGo() {
        rig.db.songs(80)
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, titles), null, shuffle = true, shownOverWindow = true))
        rig.joined()
        repeat(10) { rig.next() }
        val order = rig.songs(QueueOrder.Shuffle)
        val current = rig.current()!!
        val before = order.takeWhile { it != current }.toSet()
        assertEquals(10, before.size)
        rig.run { rig.window.removePlayed() }!!
        rig.until("the count follows the floors") { rig.window.logicalCount.value == 70 }
        assertTrue(rig.held().none { it in before })
    }
}

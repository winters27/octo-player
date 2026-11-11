package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.ui.common.Feedback
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Up next reads the queue past the songs the player holds a page at a
// time: the songs after the window's last in the order that plays (and,
// shuffled, in the shuffled order), the songs before its first, and how
// many come after it and how long they play.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class UpNextPagingTest {
    private val rig = QueueRig()
    private val connection = PlaybackConnection(rig.context, RealLengths(), rig.store, StartingPlays(), { Feedback() })

    @After
    fun close() = rig.close()

    private fun keyAt(index: Int): String = rig.player.getMediaItemAt(index).entryId!!

    @Test
    fun theTailAndTheHeadComeAPageAtATime() {
        rig.db.songs(300)
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false)), ListStart.Row("s150", null), shownOverWindow = true))
        rig.joined()
        rig.until("the window is topped up") { rig.held().size > 1 }
        val held = rig.held()
        val last = held.last().removePrefix("s").toInt()
        val first = held.first().removePrefix("s").toInt()
        val tail = rig.run { connection.queueRows(keyAt(held.size - 1), forward = true, limit = 50) }
        assertEquals((last + 1..last + 50).map { "s$it" }, tail.map { it.trackId })
        val (count, ms) = rig.run { connection.queueTail(keyAt(held.size - 1)) }
        assertEquals(299 - last, count)
        assertEquals((299 - last) * 1_000L, ms)
        val head = rig.run { connection.queueRows(keyAt(0), forward = false, limit = 50) }
        assertEquals((first - 1 downTo first - 50).map { "s$it" }, head.map { it.trackId })
        // The next page follows on from the last row read.
        val more = rig.run { connection.queueRows(tail.last().key, forward = true, limit = 50) }
        assertEquals((last + 51..last + 100).map { "s$it" }, more.map { it.trackId })
    }
}

package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.SqlValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A list of at most QUEUE_WINDOW songs plays whole at once, as today: the
// player holds every song in the list's order from the start the tap
// named, Up next is complete, and Room holds the same queue after one
// write. A shuffled one starts from a song picked at random and plays
// every other once.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class SmallListTodayPathTest {
    // The app's window sizes, so a small list stays whole in the player.
    private val rig = QueueRig(sizes = WindowSizes())
    private val titles = SortOrder(SongSort.Title, false)

    @After
    fun close() = rig.close()

    @Test
    fun aRowTapPlaysTheWholeListFromThatRow() {
        val songs = rig.db.songs(30).map { it.id }
        val start = rig.play(PlayRequest(ListSpec.Songs(SongScope.All, titles), ListStart.Row("s10", null)))
        assertTrue(start.started)
        rig.settle()
        assertEquals(songs, rig.held())
        assertEquals("s10", rig.current())
        assertEquals(songs, rig.songs())
        assertEquals(1, rig.lists().size)
        assertTrue(rig.window.building.value.isEmpty())
        assertTrue(rig.window.atLogicalEnd)
    }

    @Test
    fun everyStartKindStartsWhereItSays() {
        val songs = rig.db.songs(40).map { it.id }
        val spec = ListSpec.Songs(SongScope.All, titles)
        rig.play(PlayRequest(spec, ListStart.Track("s7")))
        assertEquals("s7", rig.current())
        rig.play(PlayRequest(spec, ListStart.At(12, 0)))
        assertEquals("s12", rig.current())
        rig.play(PlayRequest(spec, ListStart.First("s3", null)))
        assertEquals("s3", rig.current())
        rig.play(PlayRequest(spec, null))
        assertEquals("s0", rig.current())
        rig.play(PlayRequest(ListSpec.Ids(songs.take(5)), ListStart.Row("s2", RowKey(0, listOf(SqlValue.Int64(2)), "s2"))))
        assertEquals("s2", rig.current())
        rig.settle()
        assertEquals(songs.take(5), rig.held())
    }

    @Test
    fun aShuffledPlayPicksItsFirstSongAndPlaysEveryOtherOnce() {
        val songs = rig.db.songs(25).map { it.id }
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, titles), null, shuffle = true))
        rig.settle()
        val first = rig.current()!!
        assertEquals(songs.toSet(), rig.held().toSet())
        val shuffled = rig.songs(QueueOrder.Shuffle)
        assertEquals(first, shuffled.first())
        assertEquals(songs.toSet(), shuffled.toSet())
        assertEquals(SK_FIRST, rig.rows(QueueOrder.Shuffle).first().sk)
        // The player's own shuffled order is Room's.
        assertTrue(rig.player.shuffleModeEnabled)
        assertEquals(shuffled, upcomingFrom(rig))
    }

    // The songs in the player's order of play from its first, shuffled.
    private fun upcomingFrom(rig: QueueRig): List<String> {
        val timeline = rig.player.currentTimeline
        val out = ArrayList<String>()
        var i = timeline.getFirstWindowIndex(true)
        while (i != androidx.media3.common.C.INDEX_UNSET) {
            out += rig.player.getMediaItemAt(i).mediaId
            i = timeline.getNextWindowIndex(i, androidx.media3.common.Player.REPEAT_MODE_OFF, true)
        }
        return out
    }
}

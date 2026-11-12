package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A list too big to play whole plays its tapped song at once and alone:
// the player holds that one item, Up next shows the rest coming, and Next
// waits, until the list's build joins; then the queue is the whole list
// in its order, every row once, and the window fills around the song.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class BigPlayStartFirstTest {
    private val rig = QueueRig()
    private val titles = SortOrder(SongSort.Title, false)

    @After
    fun close() = rig.close()

    @Test
    fun theTappedSongPlaysAloneUntilTheListJoins() {
        val songs = rig.db.songs(300)
        val gate = CompletableDeferred<Unit>()
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                gate.await()
            }
        }
        val answer = rig.play(PlayRequest(ListSpec.Songs(SongScope.All, titles), ListStart.Row("s150", null), shownOverWindow = true))
        assertTrue(answer.started)
        rig.settle()
        // One item, the tapped song, while the rest is written.
        assertEquals(listOf("s150"), rig.held())
        assertEquals(1, rig.window.building.value.size)
        assertTrue(rig.window.building.value.single().play)
        assertFalse(rig.window.atLogicalEnd)
        // Next waits for the join.
        rig.player.seekToNext()
        rig.settle()
        assertEquals("s150", rig.current())
        gate.complete(Unit)
        rig.joined()
        rig.until("Next moved on") { rig.current() == "s151" }
        // The queue is the list in its order, every row once.
        assertEquals(songs.map { it.id }, rig.songs())
        assertTrue(rig.held().size <= rig.sizes.window)
        assertTrue(rig.held().contains("s152"))
    }

    @Test
    fun aShuffledPlayStartsWithAPickAndPlaysEverySongOnce() {
        rig.db.songs(300)
        val answer = rig.play(PlayRequest(ListSpec.Songs(SongScope.All, titles), null, shuffle = true, shownOverWindow = true))
        assertTrue(answer.started)
        val first = rig.current()!!
        rig.joined()
        val shuffled = rig.songs(QueueOrder.Shuffle)
        assertEquals(first, shuffled.first())
        assertEquals(300, shuffled.toSet().size)
        assertEquals(300, shuffled.size)
    }
}

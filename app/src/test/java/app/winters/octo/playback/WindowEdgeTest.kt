package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
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

// The player holds a window of 20 of a long queue. Next walks the whole
// queue: the window tops up ahead and trims behind, and a Next at its last
// song waits for the next page rather than wrapping or stopping; under
// repeat-all the queue's first song follows its last. Previous walks back
// the same way. The session says the listener's repeat and offers Next
// while the queue goes on.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class WindowEdgeTest {
    private val rig = QueueRig()
    private val titles = SortOrder(SongSort.Title, false)

    @After
    fun close() = rig.close()

    private fun playFromTop(n: Int) {
        rig.db.songs(n)
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, titles), null, shownOverWindow = true))
        rig.joined()
    }

    @Test
    fun nextWalksTheWholeQueueThroughTheWindow() {
        playFromTop(120)
        for (i in 1 until 120) {
            rig.next()
            assertEquals("s$i", rig.current())
            assertTrue(rig.held().size <= rig.sizes.window)
        }
        // At the end with repeat off, Next does what it does today.
        assertTrue(rig.window.atLogicalEnd)
        rig.next()
        assertEquals("s119", rig.current())
    }

    @Test
    fun previousWalksBack() {
        playFromTop(80)
        repeat(60) { rig.next() }
        assertEquals("s60", rig.current())
        for (i in 59 downTo 30) {
            rig.player.seekToPreviousMediaItem()
            rig.settle()
            assertEquals("s$i", rig.current())
        }
    }

    @Test
    fun repeatAllWrapsTheQueueNotTheWindow() {
        playFromTop(60)
        rig.player.repeatMode = Player.REPEAT_MODE_ALL
        rig.settle()
        assertEquals(Player.REPEAT_MODE_ALL, rig.player.repeatMode)
        assertEquals(Player.REPEAT_MODE_OFF, rig.remote.repeatMode)
        repeat(59) { rig.next() }
        assertEquals("s59", rig.current())
        rig.next()
        assertEquals("s0", rig.current())
        assertTrue(rig.player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
    }

    @Test
    fun repeatAllPreviousAtTheFirstGoesToTheLast() {
        playFromTop(60)
        rig.player.repeatMode = Player.REPEAT_MODE_ALL
        rig.settle()
        assertTrue(rig.player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
        rig.player.seekToPreviousMediaItem()
        rig.until("Previous wrapped") { rig.current() == "s59" }
        assertTrue(rig.held().size <= rig.sizes.window)
        rig.player.seekToPreviousMediaItem()
        rig.until("Previous walked back") { rig.current() == "s58" }
    }

    // A song that ends at the window's last item, while a list after it is
    // still being written, moves on once it joins: listeners hear the song
    // end on its own (as the sleep timer counts songs), not a skip.
    @Test
    fun aSongEndingAtTheEdgeIsHeardAsASongEnding() {
        rig.db.songs(300)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (page >= 1) gate.await()
            }
        }
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, titles), null, shownOverWindow = true))
        rig.settle()
        assertEquals(listOf("s0"), rig.held())
        val reasons = ArrayList<Pair<String?, Int>>()
        rig.player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                reasons += mediaItem?.mediaId to reason
            }
        })
        rig.window.endedAtEdge()
        rig.settle()
        gate.complete(Unit)
        rig.joined()
        rig.until("the next song plays") { rig.current() == "s1" }
        assertEquals(listOf("s1" to Player.MEDIA_ITEM_TRANSITION_REASON_AUTO), reasons.filter { it.first == "s1" })
    }
}

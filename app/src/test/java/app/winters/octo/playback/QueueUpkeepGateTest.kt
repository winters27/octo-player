package app.winters.octo.playback

import androidx.annotation.OptIn
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

// The window's own top-up is the app's upkeep, not the person's edit:
// while a blend is under way its writes wait and land once it is over,
// read again only when the window moved meanwhile, so every song still
// blends and the window still moves on when every song does. The person's
// own Next never waits.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class QueueUpkeepGateTest {
    private val rig = QueueRig(gated = true)
    private val titles = SortOrder(SongSort.Title, false)

    @After
    fun close() = rig.close()

    private fun playFromTop(n: Int) {
        rig.db.songs(n)
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, titles), null, shownOverWindow = true))
        rig.joined()
        rig.until("the window filled") { ahead() >= rig.sizes.ahead }
    }

    // How many songs the window holds past the one on.
    private fun ahead(): Int {
        val held = rig.held()
        return held.size - held.indexOf(rig.current()) - 1
    }

    @Test
    fun aTopUpDuringABlendWaitsAndLandsOnceItIsOver() {
        playFromTop(120)
        val last = rig.held().last()
        rig.gate.busy = true
        repeat(8) { rig.next() }
        assertEquals("s8", rig.current())
        // The top-up read and holds its write.
        rig.until("a write waits") { rig.gate.waiting.isNotEmpty() }
        assertEquals("nothing added yet", last, rig.held().last())
        rig.gate.free()
        rig.until("topped up past $last") { rig.held().last() != last && ahead() >= rig.sizes.topUpAt }
        val held = rig.held()
        assertTrue(held.size <= rig.sizes.window)
        assertEquals("nothing twice", held.size, held.toSet().size)
        assertEquals("s8", rig.current())
    }

    @Test
    fun fourHundredBlendedSongsOfAShuffledTwoThousandNeverReachTheWindowsEdge() {
        rig.db.songs(2_000)
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, titles), null, shuffle = true, shownOverWindow = true))
        rig.joined()
        // The songs the player holds after the one on, in the order they play.
        fun upNext() = upcoming(rig.player).size
        rig.until("the window filled", { upNext() }) { upNext() >= rig.sizes.topUpAt }
        val heard = HashSet<String>()
        heard += rig.current()!!
        for (i in 1..400) {
            // Each song change is a blend: the upkeep waits through it, and
            // the song that ends on its own always has a next one held.
            rig.gate.busy = true
            assertTrue("song $i: a next song held", upNext() >= 1)
            rig.next()
            heard += rig.current()!!
            rig.gate.free()
            rig.until("ahead after song $i", { upNext() }) { upNext() >= rig.sizes.topUpAt }
            assertTrue(rig.held().size <= rig.sizes.window)
        }
        assertEquals("every song a new one", 401, heard.size)
    }

    @Test
    fun everySongBlendedTheWindowStillMovesOnAndNextNeverWaits() {
        playFromTop(120)
        for (i in 1 until 100) {
            // Each song change is a blend: the upkeep waits through it.
            rig.gate.busy = true
            rig.next()
            assertEquals("s$i", rig.current())
            rig.gate.free()
            // The window gets ahead of the song on again, so a song ending on
            // its own always has a next one held.
            rig.until("ahead of s$i") { ahead() >= rig.sizes.topUpAt || rig.window.atLogicalEnd }
            assertTrue(rig.held().size <= rig.sizes.window)
            rig.gate.free()
        }
    }
}

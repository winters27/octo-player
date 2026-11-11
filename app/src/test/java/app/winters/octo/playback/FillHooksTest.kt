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
import java.util.concurrent.CopyOnWriteArrayList

// The fill's hooks, for timing one way of filling a big list against
// another: the start song's first play, Up next reaching 10 songs and a
// full window, each Next that waited at the edge, and the fill's end with
// its rows and bytes.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class FillHooksTest {
    private val rig = QueueRig()

    @After
    fun close() = rig.close()

    @Test
    fun eachHookFiresOnceWhereItSays() {
        rig.db.songs(400)
        val heard = CopyOnWriteArrayList<String>()
        rig.builds.hooks = object : FillHooks() {
            override fun startPlaying(listId: Long) {
                heard += "start"
            }

            override fun upNextHolds(listId: Long, count: Int) {
                heard += "upNext $count"
            }

            override fun nextWaited(millis: Long) {
                heard += "waited"
            }

            override fun filled(listId: Long, rows: Long, bytes: Long) {
                heard += "filled $rows ${bytes > 0}"
            }
        }
        val gate = CompletableDeferred<Unit>()
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                gate.await()
            }
        }
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false)), null, shownOverWindow = true))
        rig.settle()
        assertEquals(listOf("start"), heard.toList())
        // A Next that waits for the join.
        rig.player.seekToNext()
        rig.settle()
        gate.complete(Unit)
        rig.joined()
        rig.until("the window filled") { heard.count { it.startsWith("upNext") } == 2 }
        rig.until("the waited Next was told") { "waited" in heard }
        assertTrue(heard.toString(), "filled 400 true" in heard)
        assertTrue(heard.toString(), heard.contains("upNext ${rig.sizes.ahead}") || heard.any { it.startsWith("upNext") && it.substringAfter(' ').toInt() >= 10 })
    }
}

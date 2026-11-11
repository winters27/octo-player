package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A tap on a library list never waits for the queue's writes: with the
// worker held by a write, a small list plays whole and a big one plays its
// start, at once; their own writes run once the worker is free.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class TapNeverBlocksMainTest {
    private val rig = QueueRig()
    private val titles = SortOrder(SongSort.Title, false)

    @After
    fun close() = rig.close()

    // Plays with the worker held: only the main thread turns, so a tap that
    // waited on the worker never answers.
    private fun playHeld(request: PlayRequest): PlayAnswer {
        var answer: PlayAnswer? = null
        rig.scope.launch { answer = rig.window.play(request) }
        val end = System.nanoTime() + 5_000_000_000L
        while (answer == null) {
            rig.idle()
            if (System.nanoTime() > end) throw AssertionError("the tap waited for the queue's writes")
            Thread.sleep(5)
        }
        rig.idle()
        return answer!!
    }

    private fun holdTheWorker(): CompletableDeferred<Unit> {
        // The ids are read before the worker is held, as at every launch.
        rig.run { rig.store.seed() }
        val gate = CompletableDeferred<Unit>()
        rig.store.post { gate.await() }
        return gate
    }

    @Test
    fun aSmallListPlaysWhileTheWorkerIsHeld() {
        rig.db.songs(15)
        val gate = holdTheWorker()
        val answer = playHeld(PlayRequest(ListSpec.Songs(SongScope.All, titles), ListStart.Row("s4", null)))
        assertTrue(answer.started)
        assertEquals("s4", rig.current())
        assertEquals(15, rig.held().size)
        gate.complete(Unit)
        rig.until("the queue is written") { rig.songs().size == 15 }
    }

    @Test
    fun aBigListPlaysItsStartWhileTheWorkerIsHeld() {
        rig.db.songs(400)
        val gate = holdTheWorker()
        val answer = playHeld(PlayRequest(ListSpec.Songs(SongScope.All, titles), ListStart.Row("s200", null), shownOverWindow = true))
        assertTrue(answer.started)
        assertEquals("s200", rig.current())
        gate.complete(Unit)
        rig.joined()
        assertEquals(400, rig.songs().size)
    }
}

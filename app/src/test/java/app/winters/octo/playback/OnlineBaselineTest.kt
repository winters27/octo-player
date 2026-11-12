package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.room.withTransaction
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.discovery.libraryTrack
import app.winters.octo.discovery.putLibrary
import app.winters.octo.subsonic.Song
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// A list the server answers for dates from its copy's read, which its build
// makes from the start's drained answer once the start plays: a swap
// between the start and the build is one the copy and the list date after,
// and the start's row, written after it too, follows it as it is written.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class OnlineBaselineTest {
    @Test
    fun theBuildDatesFromItsCopysRead() {
        val songs = (0 until 1_200).associate { "srv$it" to Song("srv$it", title = "Title $it", artist = "Artist ${it % 5}") }
        NetworkQueueRig(songs).use { net ->
            net.db.putLibrary((0 until 1_200 step 2).map { libraryTrack("t$it", "Title $it", "Artist ${it % 5}") }) { "srv${it.id.removePrefix("t")}" }
            net.server.lists["L"] = (0 until 1_200).map { "srv$it" }
            var baseline: Long? = null
            var copy: Long? = null
            net.builds.buildHooks = object : BuildHooks() {
                override suspend fun beforePage(listId: Long, page: Int) {
                    if (page == 0) {
                        baseline = net.store.dao.list(listId)?.swapCount
                        copy = net.reads.head(WorkOwners.marked("queue:$listId"))?.swapCount
                    }
                }
            }
            // The worker is held, so the list is written and built only after
            // a swap that comes once the start was read; only the main looper
            // turns meanwhile.
            val held = CompletableDeferred<Unit>()
            net.store.post { held.await() }
            var answer: PlayAnswer? = null
            net.scope.launch { answer = net.window.play(PlayRequest(ListSpec.Online(OnlineKind.List, "L"), null, shownOverWindow = true)) }
            val end = System.nanoTime() + 15_000_000_000L
            while (answer == null) {
                shadowOf(Looper.getMainLooper()).idle()
                if (System.nanoTime() > end) throw AssertionError("never: the play answers")
                Thread.sleep(5)
            }
            assertTrue(answer!!.started)
            val read = runBlocking { net.db.workDao().swapCount() }
            runBlocking { net.db.withTransaction { net.db.workDao().raiseSwapCount() } }
            held.complete(Unit)
            net.joined()
            assertEquals("the build's copy is read after the swap", read + 1, copy)
            assertEquals("the list dates from its copy's read", copy, baseline)
            assertTrue(net.songs().size > QUEUE_WINDOW)
        }
    }
}

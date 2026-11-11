package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A build cut off by the service's death goes on from its cursor when the
// service comes back: every song once, in order, the start where it was;
// the restored window holds the start and nothing else of the list until
// it joins; and a second restore while it goes on starts no second builder.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class BuildResumeTest {
    private val rig = QueueRig()

    @After
    fun close() = rig.close()

    @Test
    fun aBuildGoesOnFromWhereItStopped() {
        val songs = rig.db.songs(900).map { it.id }
        val gate = CompletableDeferred<Unit>()
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (page >= 4) gate.await()
            }
        }
        rig.play(PlayRequest(ListSpec.Album("al", withFinds = false), ListStart.Row("s300", null), shownOverWindow = true))
        rig.until("four pages are written") { rig.lists().single().filled0 >= 4L * rig.builds.pageSize }
        rig.window.saveState()
        rig.settle()
        // The service dies.
        rig.window.release()
        rig.scope.cancel()
        rig.store.scope.cancel()
        rig.idle()

        val later = QueueRig(rig.db)
        val pages = java.util.concurrent.atomic.AtomicInteger()
        val seen = java.util.Collections.synchronizedList(ArrayList<Int>())
        later.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                pages.incrementAndGet()
                seen += page
            }
        }
        val left = rig.run { rig.db.queueDao().listsOf(rig.window.queueId).map { "${it.listId}:${it.fillState}:${it.phase}:${it.cursor}:${it.filled0}:${it.attempts}:${it.swapCount}" } }
        try {
            val saved = later.run { later.window.readSaved("home") }!!
            assertTrue(later.window.restore(saved, "home"))
            later.settle()
            assertEquals(listOf("s300"), later.held())
            // The service is made again before the list joins: one builder.
            assertTrue(!later.window.restore(saved, "home"))
            later.joined()
            assertEquals(songs, later.songs())
            assertEquals("s300", later.current())
            assertTrue("pages read again: ${pages.get()} $seen, lists at the death $left", pages.get() < 900 / later.builds.pageSize)
        } finally {
            later.window.release()
        }
    }
}

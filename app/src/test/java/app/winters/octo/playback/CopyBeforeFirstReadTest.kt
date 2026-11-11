package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.discovery.SERVER_SOURCE
import app.winters.octo.discovery.ServerHooks
import app.winters.octo.discovery.ServerListKind
import app.winters.octo.discovery.libraryTrack
import app.winters.octo.discovery.putLibrary
import app.winters.octo.discovery.today.todayResolve
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

// Labeled guard: a fill of a list the server answers for makes its own copy
// before its first read, and walks it as it stands. A list a page read
// whole is copied before the build reads a page, while the tapped song
// already plays; a swap mid-build starts nothing over; each song is queued
// once. A server folder's play answers once its walk is gathered, carrying
// the walk's cut, with no copy written before: the build copies after.
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CopyBeforeFirstReadTest {
    private val songs = (0 until 3_000).associate { "srv$it" to Song("srv$it", title = "Title $it", artist = "Artist ${it % 5}") }

    private fun NetworkQueueRig.library() =
        db.putLibrary((0 until 3_000 step 2).map { libraryTrack("t$it", "Title $it", "Artist ${it % 5}") }) { "srv${it.id.removePrefix("t")}" }

    @Test
    fun anOnlineListIsCopiedBeforeTheBuildReadsWhileTheTappedSongPlays() {
        NetworkQueueRig(songs).use { net ->
            net.library()
            val entries = (0 until 3_000).map { "srv$it" } + (0 until 300).map { "srv${it * 5}" }
            net.server.lists["L"] = entries
            val today = todayResolve(net.db, SERVER_SOURCE, entries.map { songs.getValue(it) }).map { it.id }
            runBlocking { net.reads.read(ServerListKind.List, "L", WorkOwners.marked("vm:1")) }
            val events = Collections.synchronizedList(ArrayList<String>())
            val copying = CompletableDeferred<Unit>()
            net.reads.hooks = object : ServerHooks() {
                override suspend fun copied(owner: String, pages: Int) {
                    if (pages == 1) copying.await()
                    events += "copied $owner"
                }
            }
            net.builds.buildHooks = object : BuildHooks() {
                override suspend fun beforePage(listId: Long, page: Int) {
                    events += "page $page"
                    // A swap mid-build: a walk of a copy starts nothing over.
                    if (page == 3) net.db.workDao().raiseSwapCount()
                }
            }
            val tapped = today[1_200]
            val key = runBlocking { net.reads.loader(WorkOwners.marked("vm:1")).keyOf(tapped) }
            assertTrue(net.play(PlayRequest(ListSpec.Online(OnlineKind.List, "L"), ListStart.Row(tapped, key), shownOverWindow = true)).started)
            // The tapped song plays while the copy is held.
            net.until("the tapped song plays") { net.current() == tapped }
            assertEquals(emptyList<String>(), events.filter { it.startsWith("page") })
            copying.complete(Unit)
            net.joined()
            val firstPage = events.indexOfFirst { it.startsWith("page") }
            assertTrue("events $events", firstPage > 0 && events.subList(firstPage, events.size).none { it.startsWith("copied") })
            assertEquals(today, net.songs())
            assertEquals(today.size, net.songs().toSet().size)
            val lists = runBlocking { net.store.inTurn { net.store.dao.listsOf(net.window.queueId) } }.filter { it.spec != null }
            assertTrue(lists.isNotEmpty())
            assertTrue("no attempt started over: $lists", lists.all { it.attempts == 0 })
        }
    }

    // A server folder's play answers once its walk is gathered, carrying the
    // walk's cut, and before any copy is written: the build makes the copy
    // from the gathered answer after it.
    @Test
    fun aServerFoldersPlayAnswersOnceItsWalkIsGathered() {
        NetworkQueueRig(songs).use { net ->
            net.library()
            net.server.folders["f"] = listOf("g") to (0 until 1_200).map { "srv$it" }
            net.server.folders["g"] = emptyList<String>() to (1_200 until 2_400).map { "srv$it" }
            val events = Collections.synchronizedList(ArrayList<String>())
            net.reads.hooks = object : ServerHooks() {
                override suspend fun drained(owner: String) {
                    events += "drained $owner"
                }

                override suspend fun filled(owner: String, pages: Int) {
                    events += "filled $owner"
                }

                override suspend fun resolved(owner: String, pages: Int) {
                    events += "resolved $owner"
                }
            }
            // The start hands its song over once its walk is gathered.
            net.window.handing = { events += "handed" }
            val answer = net.play(PlayRequest(ListSpec.ServerFolder("f")))
            assertTrue(answer.started)
            assertTrue("cut at the limit", answer.cut)
            val queue = WorkOwners.marked("queue:")
            val before = synchronized(events) { events.subList(0, events.indexOf("handed")).toList() }
            assertTrue("events $before", before.any { it.startsWith("drained $queue") })
            assertTrue("no copy before the answer: $before", before.none { it.startsWith("filled $queue") || it.startsWith("resolved $queue") })
            net.joined()
            assertEquals(2_000, net.songs().size)
        }
    }
}

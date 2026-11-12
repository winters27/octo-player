package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.data.Drains
import app.winters.octo.discovery.ServerHooks
import app.winters.octo.discovery.libraryTrack
import app.winters.octo.discovery.putLibrary
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The answer a big start drained belongs to its list's build: a newer tap
// that stops the build stops its copy, and the build's release leaves no
// row of its owner and no drained file; a file a killed process left goes
// at the next launch's sweep. A build that must read the list again (its
// file gone) finds the start's song by song, not by place. Once a start
// has played, a server that stops answering ends nothing: the copy comes
// from the file, and only the server's own answer to a start refuses it.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class ServerCopyOwnerTest {
    private val songs = (0 until 1_000).associate { "s$it" to Song("s$it", title = "Song ${wordOf(it)}", artist = "Artist") }
    private val rig = ServerStartRig(songs)

    @After
    fun close() = rig.close()

    private fun library() = rig.db.putLibrary((0 until 1_000).map { libraryTrack("t$it", "Song ${wordOf(it)}", "Artist") }) { "s${it.id.removePrefix("t")}" }

    private fun token() = runBlocking { rig.store.claimList() }

    private fun owned(owner: String) =
        rig.count("SELECT COUNT(*) FROM server_entry WHERE owner = ?", owner) + rig.count("SELECT COUNT(*) FROM server_head WHERE owner = ?", owner)

    // Holds the queue's worker, so a start's posted jobs and its build wait.
    private fun holdWorker(): CompletableDeferred<Unit> {
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        rig.store.post {
            held.complete(Unit)
            release.await()
        }
        runBlocking { withTimeout(10_000) { held.await() } }
        return release
    }

    @Test
    fun aNewerTapStopsTheCopyAndItsReleaseLeavesNothing() {
        library()
        rig.server.lists["L"] = (0 until 900).map { "s$it" }
        rig.server.lists["M"] = (0 until 800).map { "s${999 - it}" }
        val first = token()
        val copying = CompletableDeferred<Unit>()
        val stop = CompletableDeferred<Unit>()
        rig.reads.hooks = object : ServerHooks() {
            override suspend fun filled(owner: String, pages: Int) {
                if (owner == WorkOwners.marked("queue:$first") && pages == 2) {
                    copying.complete(Unit)
                    stop.await()
                }
            }
        }
        assertTrue(rig.play(PlayRequest(ListSpec.Online(OnlineKind.List, "L"), token = first)).started)
        runBlocking { withTimeout(10_000) { copying.await() } }
        assertTrue("the copy is under way", owned(WorkOwners.marked("queue:$first")) > 0)
        assertTrue(rig.play(PlayRequest(ListSpec.Online(OnlineKind.List, "M"), token = token())).started)
        stop.complete(Unit)
        rig.joined()
        rig.until("the stopped build let its copy go") { owned(WorkOwners.marked("queue:$first")) == 0 }
        assertTrue("no drained file of the stopped play: ${rig.files()}", rig.files().isEmpty())
        // The newer play's own build ends with its copy let go too.
        assertTrue(rig.songs().size > 1)
    }

    // A build stopped before it copied lets its answer go all the same.
    @Test
    fun aBuildStoppedBeforeItsCopyLeavesNoFile() {
        library()
        rig.server.lists["L"] = (0 until 900).map { "s$it" }
        rig.server.lists["M"] = (0 until 800).map { "s${999 - it}" }
        val release = holdWorker()
        val first = token()
        assertTrue(rig.playWhileParked(PlayRequest(ListSpec.Online(OnlineKind.List, "L"), token = first)).started)
        assertEquals(1, rig.files().size)
        // A newer tap stops the first build while it waits for its opening.
        assertTrue(rig.playWhileParked(PlayRequest(ListSpec.Online(OnlineKind.List, "M"), token = token())).started)
        release.complete(Unit)
        rig.joined()
        assertEquals(0, owned(WorkOwners.marked("queue:$first")))
        assertTrue("the stopped build's answer went: ${rig.files()}", rig.files().isEmpty())
    }

    @Test
    fun aKilledProcesssFileGoesAtTheLaunchSweep() {
        library()
        rig.server.lists["L"] = (0 until 900).map { "s$it" }
        val release = holdWorker()
        assertTrue(rig.playWhileParked(PlayRequest(ListSpec.Online(OnlineKind.List, "L"), token = token())).started)
        // The process dies here: its build never copied the answer.
        assertEquals(1, rig.files().size)
        Drains(rig.drains).sweep()
        assertEquals(emptyList<String>(), rig.files())
        release.complete(Unit)
    }

    @Test
    fun aBuildThatReadsTheListAgainFindsTheStartBySong() {
        library()
        rig.server.lists["L"] = (0 until 900).map { "s$it" }
        val id = token()
        val release = holdWorker()
        assertTrue(rig.playWhileParked(PlayRequest(ListSpec.Online(OnlineKind.List, "L"), token = id)).started)
        assertEquals("t0", rig.current())
        // The answer is gone before the build copies it, and the server
        // has made the list anew, its first song now in the middle.
        rig.reads.dropStart(WorkOwners.marked("queue:$id"))
        rig.server.lists["L"] = (450 until 900).map { "s$it" } + (0 until 450).map { "s$it" }
        release.complete(Unit)
        rig.joined()
        // The build placed the start's row at its song's place in the new
        // answer: the songs before it come before the start in play order.
        assertEquals((450 until 900).map { "t$it" } + (0 until 450).map { "t$it" }, rig.songs())
    }

    @Test
    fun aServerThatStopsAnsweringAfterTheStartEndsNothing() {
        library()
        rig.server.lists["L"] = (0 until 900).map { "s$it" }
        val release = holdWorker()
        assertTrue(rig.playWhileParked(PlayRequest(ListSpec.Online(OnlineKind.List, "L"), token = token())).started)
        val calls = rig.server.calls.size
        rig.server.gone += "L"
        release.complete(Unit)
        rig.joined()
        assertEquals("the copy came from the file", calls, rig.server.calls.size)
        assertEquals((0 until 900).map { "t$it" }, rig.songs())
        // Only the server's own answer to a start refuses a play.
        val refused = rig.play(PlayRequest(ListSpec.Online(OnlineKind.List, "L"), token = token()))
        assertEquals(LIST_MADE_ANEW, refused.refusal)
        assertTrue(rig.files().isEmpty())
    }
}

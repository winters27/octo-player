package app.winters.octo.playback

import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.listening.timed
import app.winters.octo.testing.addSongs
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A storm of song writes, as the after-sync's rating pages make, while a
// summary read runs: the read runs to its end and the list comes, and the
// reads that follow read no playlist again, since no playlist's key moved.
// Only the playlist that changed is read again at all.
@RunWith(RobolectricTestRunner::class)
class PlaylistSummaryStormTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    // Lets a cover walk of one playlist stop between its pages until let go.
    private class PausedWalk(private val playlistId: String) : SummaryHooksCounting() {
        val paused = CompletableDeferred<Unit>()
        val go = CompletableDeferred<Unit>()

        @Volatile var armed = false

        override suspend fun betweenPages(playlistId: String) {
            if (armed && playlistId == this.playlistId && paused.complete(Unit)) go.await()
        }
    }

    @Test
    fun aReadRunsThroughTheStormAndOnlyTheChangedPlaylistIsReadAgain() = timed(30) {
        // Covers only late in "a", so its walk reads several pages.
        db.dao().insertTracks((0 until 40).map { songRow("device:s$it", "al$it", if (it >= 30) "art:$it" else null) })
        db.playlist("a", (0 until 40).map { "device:s$it" }, updatedAt = 3)
        db.playlist("b", (0 until 5).map { "device:s${it + 30}" }, updatedAt = 2)
        db.playlist("c", listOf("device:s31"), updatedAt = 1)
        val summaries = db.summaries(page = 5)
        val hooks = PausedWalk("a")
        summaries.hooks = hooks
        val seen = Channel<List<PlaylistSummary>>(Channel.UNLIMITED)
        coroutineScope {
            val collecting = launch { summaries.all.collect { seen.send(it) } }
            assertEquals(40, seen.receive().first { it.id == "a" }.songCount)
            assertEquals(3, hooks.total)

            hooks.armed = true
            db.addSongs("a", listOf(songRow("device:s39", "al39", "art:39")), now = 10)
            hooks.paused.await()
            // The storm: 200 song writes while the read of "a" waits mid-walk.
            repeat(200) { n -> db.userDao().showRating("device:s${n % 40}", n % 5) }
            hooks.go.complete(Unit)
            var latest = seen.receive()
            while (latest.first { it.id == "a" }.songCount != 41) latest = seen.receive()
            // Whatever passes the storm left: wait for them to settle.
            db.userDao().renamePlaylist("c", "Settled", now = 20)
            while (latest.first { it.id == "c" }.name != "Settled") latest = seen.receive()

            assertEquals("the read in the storm ran once, to its end", 2, hooks.of("a"))
            assertEquals(1, hooks.of("b"))
            assertEquals("a rename moves the key too", 2, hooks.of("c"))
            collecting.cancel()
        }
    }
}

// Counts reads, and lets a subclass step in between pages.
internal open class SummaryHooksCounting : SummaryHooks() {
    private val counts = CountedReads()

    val total get() = counts.total

    fun of(id: String) = counts.of(id)

    override suspend fun reading(playlistId: String) = counts.reading(playlistId)
}

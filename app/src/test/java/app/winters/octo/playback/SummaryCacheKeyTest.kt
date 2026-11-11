package app.winters.octo.playback

import app.winters.octo.catalog.NewItem
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.catalog.exec
import app.winters.octo.catalog.longOf
import app.winters.octo.catalog.library
import app.winters.octo.catalog.put
import app.winters.octo.device.DEVICE
import app.winters.octo.listening.timed
import app.winters.octo.testing.addSongs
import app.winters.octo.testing.edits
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A playlist's kept totals and covers are read again after every writer of
// its rows: an add, a removal, a move, a putting back, a relink in a swap
// and in a skipped merge, and a change of a find's cover. Each moves one
// part of what they were read under, so the summary follows.
@RunWith(RobolectricTestRunner::class)
class SummaryCacheKeyTest {
    private val server = "server:h"
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    // Waits for a summary of `id` that `wanted` accepts.
    private suspend fun Channel<List<PlaylistSummary>>.until(id: String, wanted: (PlaylistSummary?) -> Boolean): PlaylistSummary? =
        withTimeout(10_000) {
            var found: PlaylistSummary?
            do {
                found = receive().firstOrNull { it.id == id }
            } while (!wanted(found))
            found
        }

    @Test
    fun editsMoveTheSummary() = timed(30) {
        db.dao().insertTracks((0 until 6).map { songRow("device:s$it", "al$it", "art:$it", ms = 1_000L * (it + 1)) })
        db.playlist("p", (0 until 5).map { "device:s$it" })
        val summaries = db.summaries()
        val seen = Channel<List<PlaylistSummary>>(Channel.UNLIMITED)
        coroutineScope {
            val collecting = launch { summaries.all.collect { seen.send(it) } }
            seen.until("p") { it?.songCount == 5 }
            val user = db.userDao()

            val edits = db.edits()
            val added = db.addSongs("p", listOf(songRow("device:s5", "al5", "art:5", ms = 6_000)), now = 10)!!
            seen.until("p") { it != null && it.songCount == 6 && it.durationMs == 21_000L }

            val removed = edits.remove("p", added.last, now = 11)!!
            seen.until("p") { it?.songCount == 5 }

            // The first song moved to the end: the covers follow the new order.
            val first = user.playlistItems("p").first()
            val last = user.playlistItems("p").last()
            edits.move("p", first.itemKey, last.itemKey, now = 12)
            seen.until("p") { it?.covers == listOf("art:1", "art:2", "art:3", "art:4") }

            edits.restore("p", removed, now = 13)
            seen.until("p") { it?.songCount == 6 }
            collecting.cancel()
        }
    }

    @Test
    fun relinksInASwapAndInASkippedMergeMoveTheSummary() = timed(30) {
        // Rows written with a server copy's id, which no library song has
        // until a merge follows them to the song it became.
        db.playlist("p", emptyList())
        db.exec("INSERT INTO playlist_item (playlistId, itemSet, trackId, relinkKey, ord, itemKey) VALUES ('p', 0, '$server:s0', '', 0, 1)")
        val summaries = db.summaries()
        val seen = Channel<List<PlaylistSummary>>(Channel.UNLIMITED)
        coroutineScope {
            val collecting = launch { summaries.all.collect { seen.send(it) } }
            seen.until("p") { it?.songCount == 0 }
            db.put(library(server, 3))
            db.put(library(DEVICE, 3))
            val merge = db.testMerge(server)
            merge.rebuild()
            seen.until("p") { it?.songCount == 1 }

            db.exec("INSERT INTO playlist_item (playlistId, itemSet, trackId, relinkKey, ord, itemKey) VALUES ('p', 0, '$server:s1', '', ${1L shl 20}, 2)")
            merge.rebuild()
            seen.until("p") { it?.songCount == 2 }
            collecting.cancel()
        }
    }

    // A whole new list of songs, written as a new set and shown in one
    // step that leaves updatedAt as it was (a playlist made from a list,
    // a sync's rows): the moved item set alone reads the summary again.
    @Test
    fun aBulkFlipMovesTheSummary() = timed(30) {
        db.dao().insertTracks((0 until 4).map { songRow("device:s$it", "al$it", "art:$it", ms = 1_000L * (it + 1)) })
        db.playlist("p", listOf("device:s0"))
        val sets = PlaylistSets(db)
        val summaries = db.summaries(sets = sets)
        val seen = Channel<List<PlaylistSummary>>(Channel.UNLIMITED)
        coroutineScope {
            val collecting = launch { summaries.all.collect { seen.send(it) } }
            seen.until("p") { it?.songCount == 1 }
            sets.withSet("p") {
                val set = sets.writeSet("p") { emit -> emit((1 until 4).map { NewItem("device:s$it", "k$it") }) }!!
                assertTrue(sets.flipNew("p", set))
                sets.deleteSet("p", 0)
            }
            val after = seen.until("p") { it?.songCount == 3 }
            assertEquals(listOf("art:1", "art:2", "art:3"), after?.covers)
            assertEquals(1L, db.longOf("SELECT COUNT(DISTINCT updatedAt) FROM playlist"))
            collecting.cancel()
        }
    }

    @Test
    fun aFindsNewCoverMovesTheSummary() = timed(30) {
        db.onlineDao().insert(listOf(findRow("find:1", album = "fa", cover = null)))
        db.dao().insertTracks(listOf(songRow("device:s0", "al0", null)))
        db.playlist("finds", listOf("device:s0", "find:1"), updatedAt = 2)
        db.playlist("plain", listOf("device:s0"), updatedAt = 1)
        val summaries = db.summaries()
        val hooks = CountedReads()
        summaries.hooks = hooks
        val seen = Channel<List<PlaylistSummary>>(Channel.UNLIMITED)
        coroutineScope {
            val collecting = launch { summaries.all.collect { seen.send(it) } }
            seen.until("finds") { it?.covers == emptyList<String>() }
            db.onlineDao().insert(listOf(findRow("find:1", album = "fa", cover = "c1")))
            seen.until("finds") { it?.covers == listOf("online:server:h|c1") }
            assertEquals("a playlist holding no find is not read again", 1, hooks.of("plain"))
            assertEquals(2, hooks.of("finds"))
            collecting.cancel()
        }
    }
}

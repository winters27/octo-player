package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.SourceCatalog
import app.winters.octo.catalog.library
import app.winters.octo.catalog.put
import app.winters.octo.device.DEVICE
import app.winters.octo.listening.HOME
import app.winters.octo.listening.NOW
import app.winters.octo.listening.TotalsRig
import app.winters.octo.listening.fillPlays
import app.winters.octo.listening.longOf
import app.winters.octo.listening.playOn
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.RowKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A Play of a list by listening (Most played) is one state of the play
// totals. Its walk holds the totals still for one attempt:
// a play and the totals' write it asks for (a waiting phase 2 write) land
// nothing in play_total until the attempt ends, then apply; a new reader of
// the totals waits behind that write and finishes once it lands; and the
// queue's order is the order at Play. A swap renaming a top-ten song at
// page 7 lets the hold go, waits for the totals to be built through it, and
// the next attempt queues the song once, at its rank.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class ListeningBuildFrozenTest {
    private val mostPlayed = ListSpec.Songs(SongScope.All, SortOrder(SongSort.MostPlayed, true))

    // The list's songs as its page shows them now, a page at a time.
    @Suppress("UNCHECKED_CAST")
    private fun shown(rig: QueueRig): List<String> = rig.run {
        val loader = rig.loaders.forSpec(mostPlayed, rig.owners.walk()) as ListLoader<Any>
        val out = ArrayList<String>()
        var after: RowKey? = null
        while (true) {
            val page = loader.after(after, 500)
            page.forEach { out += songOf(loader, it.row)!! }
            if (page.size < 500) break
            after = page.last().key
        }
        out
    }

    @Test
    fun aPlayDuringTheWalkWaitsForIt() {
        val db = openTestCatalog()
        db.fillPlays(seed = 61, songs = 20_000, albums = 2_000)
        val listening = TotalsRig(db, db.testMerge(HOME))
        val rig = QueueRig(db, page = 500, listening = listening)
        try {
            rig.run { listening.started() }
            val atPlay = shown(rig)
            val song = atPlay[10_000]
            val before = db.longOf("SELECT plays FROM play_total WHERE trackId = ?", song)
            val reached = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            rig.builds.buildHooks = object : BuildHooks() {
                override suspend fun beforePage(listId: Long, page: Int) {
                    if (page == 3) {
                        reached.complete(Unit)
                        gate.await()
                    }
                }
            }
            rig.play(PlayRequest(mostPlayed, ListStart.Row(atPlay.first(), null), shownOverWindow = true))
            rig.run {
                reached.await()
                // A play lands and the totals ask to write it: the write waits
                // for the walk's hold, and so does a reader asked for after it.
                val waits = CompletableDeferred<Unit>()
                listening.totals.holds.writeWaits = { waits.complete(Unit) }
                db.playOn(song, NOW + 1)
                listening.totals.refresh(listOf(song))
                waits.await()
                val reader = kotlinx.coroutines.coroutineScope {
                    val next = async(Dispatchers.IO) { listening.totals.withReadHold { db.longOf("SELECT plays FROM play_total WHERE trackId = ?", song) } }
                    assertNull(withTimeoutOrNull(300) { next.await() })
                    assertEquals(before, db.longOf("SELECT plays FROM play_total WHERE trackId = ?", song))
                    gate.complete(Unit)
                    next.await()
                }
                assertEquals(before + 1, reader)
            }
            rig.joined()
            assertEquals(before + 1, db.longOf("SELECT plays FROM play_total WHERE trackId = ?", song))
            assertEquals(atPlay, rig.songs())
        } finally {
            rig.close()
        }
    }

    // A phone library and a server's copy of every song, merged; plays on
    // the first thirty songs, song i played 40 - i times, by relink key.
    @Test
    fun aRenameAtPage7KeepsItsRank() {
        val db = openTestCatalog()
        val songs = 2_000
        val listening = TotalsRig(db, db.testMerge(HOME))
        val rig = QueueRig(db, page = 50, listening = listening)
        try {
            rig.run {
                db.put(library(DEVICE, songs))
                db.put(library(HOME, songs))
                listening.merge.rebuild()
                for (i in 0 until 30) repeat(40 - i) { db.playOn("$DEVICE:s$i", NOW - i * 1_000L - it, relinkKey = "drake|song $i") }
                listening.started()
                listening.built()
            }
            val atPlay = shown(rig)
            val renamed = "$DEVICE:s3"
            val rank = atPlay.indexOf(renamed)
            assertTrue("a top-ten song: $rank", rank in 0 until 10)
            var swapped = false
            rig.builds.buildHooks = object : BuildHooks() {
                override suspend fun beforePage(listId: Long, page: Int) {
                    if (page == 7 && !swapped) {
                        swapped = true
                        // The phone's copy goes: the song is the server's now.
                        val phone = library(DEVICE, songs)
                        db.put(SourceCatalog(DEVICE, true, phone.tracks.filter { it.nativeId != "s3" }, phone.albums, phone.artists))
                        listening.merge.rebuild()
                    }
                }
            }
            rig.play(PlayRequest(mostPlayed, ListStart.Row(atPlay.first(), null), shownOverWindow = true))
            rig.joined()
            assertTrue(swapped)
            assertTrue(rig.lists().first { it.spec != null }.attempts >= 1)
            val queued = rig.songs()
            val now = "$HOME:s3"
            assertEquals(1, queued.count { it == now })
            assertTrue(renamed !in queued)
            assertEquals(rank, queued.indexOf(now))
            assertEquals(songs, queued.size)
            assertEquals(queued.size, queued.toSet().size)
        } finally {
            rig.close()
        }
    }
}

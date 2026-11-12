package app.winters.octo.catalog

import app.winters.octo.listening.timed
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files

// A playlist's rows live in numbered sets. A new set's number is one past
// every set its rows use and past the one it shows, chosen only under its
// set mutex; a bulk write is a new set, a page to a transaction, shown in
// one step; a deleted playlist shows set -1 and leaves every list at once,
// its rows going a page at a time once no walk holds them; a set a killed
// write left is invisible and goes at the next start.
@RunWith(RobolectricTestRunner::class)
class PlaylistSetsTest {
    private fun rows(n: Int, prefix: String = "song") = List(n) { NewItem("$prefix$it", "k$it") }

    private suspend fun pages(rows: List<NewItem>): suspend (suspend (List<NewItem>) -> Unit) -> Unit = { emit -> rows.chunked(SET_PAGE).forEach { emit(it) } }

    private fun CatalogDatabase.current(id: String): List<String> =
        query("SELECT i.trackId FROM playlist p CROSS JOIN playlist_item i ON i.playlistId = p.id AND i.itemSet = p.itemSet WHERE p.id = ? ORDER BY i.ord, i.itemKey", arrayOf<Any?>(id))
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    @Test
    fun aBulkWriteShowsItsRowsInOneStep() = timed(30) {
        val db = openTestCatalog()
        val sets = PlaylistSets(db)
        val seen = ArrayList<Int>()
        sets.hooks = object : SetHooks() {
            override suspend fun wrotePage(playlistId: String, itemSet: Long, page: Int) {
                seen += db.current(playlistId).size
            }
        }
        db.counted.reset()
        db.counted.recording = true
        assertTrue(sets.create(PlaylistEntity("p", "P", 1, 1), pages(rows(1_200))))
        assertEquals("nothing shows before the step", listOf(0, 0, 0), seen)
        assertEquals(rows(1_200).map { it.trackId }, db.current("p"))
        assertEquals(1L, db.longOf("SELECT itemSet FROM playlist WHERE id = 'p'"))
        assertTrue("no transaction writes more than a page", db.counted.transactions.all { it.rowsChanged <= SET_PAGE + 2 })
        assertTrue(db.counted.transactions.size >= 3)
        db.close()
    }

    @Test
    fun setNumbersStepOverEverySetInUse() = timed(30) {
        val db = openTestCatalog()
        val sets = PlaylistSets(db)
        sets.create(PlaylistEntity("p", "P", 1, 1), pages(rows(3)))
        // A write the app's end cut off left set 4 behind.
        sets.withSet("p") { sets.writeRows("p", 4, 0, rows(2, "left")) }
        val next = sets.withSet("p") { sets.newItemSet("p") }
        assertEquals(5L, next)
        // A playlist being deleted gets none.
        db.userDao().insertPlaylist(PlaylistEntity("q", "Q", 1, 1))
        assertTrue(sets.deletePlaylist("q"))
        assertNull(sets.withSet("q") { sets.newItemSet("q") })
        // Two writers of one playlist take turns, each its own number.
        coroutineScope {
            val a = async { sets.withSet("p") { sets.writeSet("p", pages(rows(600, "a"))) } }
            val b = async { sets.withSet("p") { sets.writeSet("p", pages(rows(600, "b"))) } }
            assertEquals(setOf(5L, 6L), setOf(a.await(), b.await()))
        }
        db.close()
    }

    @Test
    fun aSetIsNumberedOnlyUnderItsMutex() = timed(10) {
        val db = openTestCatalog()
        val sets = PlaylistSets(db)
        db.userDao().insertPlaylist(PlaylistEntity("p", "P", 1, 1))
        try {
            sets.newItemSet("p")
            fail("numbered outside the set mutex")
        } catch (_: IllegalStateException) {
        }
        try {
            sets.newBaseSet("p")
            fail("numbered outside the set mutex")
        } catch (_: IllegalStateException) {
        }
        assertEquals(1L, sets.withSet("p") { sets.newItemSet("p") })
        db.close()
    }

    // A delete while two walks hold the set: the playlist leaves every list
    // at once, each walk still reads all of its set, and the rows and the
    // row go only once the last walk lets go, a page at a time.
    @Test
    fun aDeleteDuringWalksWaitsForTheirPins() = timed(60) {
        val db = openTestCatalog()
        val sets = PlaylistSets(db)
        val user = db.userDao()
        sets.create(PlaylistEntity("p", "P", 1, 1, serverId = "srv", sourceId = "server:h"), pages(rows(3_000)))
        db.favouritesDao().insertPins(listOf(PinnedItemEntity("playlist", "p", "", 0)))
        assertEquals(1, db.favouritesDao().shownPinCount())
        val walked = listOf(ArrayList<String>(), ArrayList<String>())
        // A duplicate's walk and a download's, each its own owner.
        val duplicating = WorkOwners.marked("walk:1")
        val downloading = WorkOwners.marked("walk:2")
        val a = sets.pin(duplicating, "p")!!
        val b = sets.pin(downloading, "p")!!
        coroutineScope {
            val gate = CompletableDeferred<Unit>()
            val walks = listOf(a to walked[0], b to walked[1]).map { (set, out) ->
                launch {
                    var page = 0
                    sets.walk(set, 7) { rows ->
                        out += rows.map { it.trackId }
                        if (++page == 7) gate.await()
                    }
                }
            }
            db.counted.reset()
            db.counted.recording = true
            assertTrue(sets.deletePlaylist("p", updatedAt = 1))
            assertEquals(emptyList<PlaylistRow>(), user.playlistRows())
            assertNull(user.playlistRow("p"))
            assertEquals(emptyList<PlaylistEntity>(), user.serverPlaylists("server:h"))
            assertEquals(emptyList<PlaylistEntity>(), user.playlists().first())
            assertNull(user.playlist("p").first())
            assertEquals("its pin on the home screen goes at once", 0, db.favouritesDao().shownPinCount())
            assertEquals("the pinned rows stay", 3_000L, db.longOf("SELECT COUNT(*) FROM playlist_item WHERE playlistId = 'p'"))
            gate.complete(Unit)
            walks.forEach { it.join() }
        }
        assertEquals(rows(3_000).map { it.trackId }, walked[0])
        assertEquals(rows(3_000).map { it.trackId }, walked[1])
        sets.releasePin(duplicating, "p")
        sets.idle()
        assertEquals("one walk still holds the set", 3_000L, db.longOf("SELECT COUNT(*) FROM playlist_item WHERE playlistId = 'p'"))
        sets.releasePin(downloading, "p")
        sets.idle()
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM playlist_item WHERE playlistId = 'p'"))
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM playlist WHERE id = 'p'"))
        assertTrue("no statement deleted more than a page", db.counted.own.all { it.rowsChanged <= SET_PAGE })
        assertTrue(db.counted.own.count { it.sql.startsWith("DELETE FROM playlist_item") } >= 6)
        db.close()
    }

    // A delete refused by an edit since it was read leaves the playlist.
    @Test
    fun aDeleteOfAnEditedPlaylistIsRefused() = timed(10) {
        val db = openTestCatalog()
        val sets = PlaylistSets(db)
        sets.create(PlaylistEntity("p", "P", 1, 1), pages(rows(3)))
        db.userDao().touchPlaylist("p", 9)
        assertFalse(sets.deletePlaylist("p", updatedAt = 1))
        assertEquals(3, db.current("p").size)
        db.close()
    }

    // A write cut off by the app's end, and a delete cut off before its
    // last pin let go, leave rows no reader sees; the next start's sweep
    // lets the old launch's pins go and takes them.
    @Test
    fun theSweepTakesWhatAKillLeft() = timed(30) {
        val dir = Files.createTempDirectory("sweep").toFile()
        var db = openTestCatalog(dir)
        var sets = PlaylistSets(db)
        sets.create(PlaylistEntity("p", "P", 1, 1), pages(rows(1_200)))
        sets.create(PlaylistEntity("q", "Q", 1, 1), pages(rows(700)))
        // Three pages of a new set of p, never shown.
        sets.withSet("p") { repeat(3) { sets.writeRows("p", 2, it * 500L, rows(500, "new")) } }
        // q deleted while a walk held it.
        sets.pin(WorkOwners().walk(), "q")
        sets.deletePlaylist("q")
        db.close()

        LaunchMark.next()
        db = openTestCatalog(dir)
        sets = PlaylistSets(db)
        assertEquals(rows(1_200).map { it.trackId }, db.current("p"))
        // The open deletes nothing: the old launch's pin waits for the sweep.
        assertEquals(1L, db.longOf("SELECT COUNT(*) FROM playlist_set_pin"))
        sets.sweep()
        sets.idle()
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM playlist_set_pin"))
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM playlist_item i JOIN playlist p ON p.id = i.playlistId WHERE i.itemSet != p.itemSet"))
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM playlist_item WHERE playlistId = 'q'"))
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM playlist WHERE id = 'q'"))
        assertEquals(rows(1_200).map { it.trackId }, db.current("p"))
        db.close()
    }

    // Past rank 2,047 an ord is past an Int: rows keep their place there.
    @Test
    fun ordsPastAnIntKeepTheirOrder() = timed(30) {
        val db = openTestCatalog()
        val sets = PlaylistSets(db)
        sets.create(PlaylistEntity("p", "P", 1, 1), pages(rows(3_000)))
        for (rank in listOf(2_047L, 2_048L, 2_999L)) {
            assertEquals(rank * ORD_STEP, db.longOf("SELECT ord FROM playlist_item WHERE trackId = ?", "song$rank"))
        }
        assertEquals(rows(3_000).map { it.trackId }, db.current("p"))
        db.close()
    }
}

package app.winters.octo.catalog

import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaylistRowKeysTest {
    // New playlist rows take keys from the one counter, a block per write;
    // a row taken out and put back, or moved, keeps its key; places run in
    // play order, new rows ORD_STEP apart. (Rows a sync keeps keep their
    // keys too: PlaylistKeptKeysTest.)
    @Test
    fun rowsTakeKeysAndKeepThem() = runBlocking {
        val db = openTestCatalog()
        val dao = db.userDao()
        val edits = PlaylistEdits(db, PlaylistSets(db))
        dao.insertPlaylist(PlaylistEntity("p", "P", 1, 1))
        val songs = (1..5).map { SongToAdd("device:$it", "kdevice:$it") }
        edits.add("p", { emit -> emit(songs.take(3)) }, distinct = false, newOnly = false, now = 2)
        edits.add("p", { emit -> emit(songs.drop(3)) }, distinct = false, newOnly = false, now = 3)
        var items = dao.playlistItems("p")
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), items.map { it.itemKey })
        assertEquals((0..4).map(::ordAt), items.map { it.ord })
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L), items.map { it.itemSet })

        val removed = edits.remove("p", items[1].itemKey, 4)!!
        assertEquals(listOf(1L, 3L, 4L, 5L), dao.playlistItems("p").map { it.itemKey })
        edits.restore("p", removed, 5)
        items = dao.playlistItems("p")
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), items.map { it.itemKey })
        assertEquals((0..4).map(::ordAt), items.map { it.ord })

        edits.move("p", items[0].itemKey, items[4].itemKey, 6)
        items = dao.playlistItems("p")
        assertEquals(listOf(2L, 3L, 4L, 5L, 1L), items.map { it.itemKey })
        assertTrue("places rise in play order", items.zipWithNext().all { (a, b) -> a.ord < b.ord })
        assertEquals(6L, dao.nextItemKey())
        db.close()
    }
}

package app.winters.octo.ui.paging

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A search's songs end at the screen's last row, a key read once and kept:
// a walk from row 70 of 100 capped results stops at row 99, a walk from the
// top equals today's search, and a song that joins after the survey past
// that end is never read.
@RunWith(RobolectricTestRunner::class)
class SearchEndKeyTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private fun song(id: String, title: String, artist: String = "Band") = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = title, searchKey = title.lowercase(), sortKey = title.lowercase(),
        artist = artist, artistId = "ar", album = "", albumId = "al", trackNo = null, discNo = null, year = null,
        durationMs = 0, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
    )

    private suspend fun walk(loader: ListLoader<TrackEntity>, from: RowKey?): List<String> {
        val out = ArrayList<String>()
        var after = from
        while (true) {
            val page = loader.after(after, 30)
            out += page.map { it.row.id }
            if (page.size < 30) return out
            after = page.last().key
        }
    }

    @Test
    fun aWalkStopsAtTheScreensLastRow() = runBlocking {
        // Titles that hold the word, then songs by an artist that does.
        val titled = (0 until 120).map { song("device:t$it", "Song %03d".format(it)) }
        val byArtist = (0 until 30).map { song("device:a$it", "Other %02d".format(it), artist = "Songbird") }
        db.dao().insertTracks(titled + byArtist + song("device:x", "Nothing"))
        val loader = SearchLoader(db.listsDao(), "song", 100)
        val today = db.dao().searchTracks("song", 100).map { it.id }

        assertEquals(100, loader.survey(null).count)
        assertEquals(today, walk(loader, null))
        val at70 = loader.keyAt(70)!!
        assertEquals(today.subList(71, 100), walk(loader, at70))

        // Joins after the survey: one that sorts before the end is read,
        // one past it never is.
        db.dao().insertTracks(listOf(song("device:early", "Song 000a"), song("device:late", "Song 999")))
        val again = walk(loader, null)
        assertTrue("device:early" in again)
        assertFalse("device:late" in again)
        assertEquals(again.size, loader.count())
        assertEquals(today.last(), again.last())
    }
}

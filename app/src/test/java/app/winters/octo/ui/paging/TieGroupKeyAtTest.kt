package app.winters.octo.ui.paging

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Liked songs all liked at one moment (a server's starred songs copied in
// at once) make one tie group bigger than TIE_GROUP_MAX. A jump to a place
// in it (the rail, a row's place, a list's middle) reads that one row, never
// the group up to the place, and lands on the row a walk of pages reaches
// there, both ways.
@RunWith(RobolectricTestRunner::class)
class TieGroupKeyAtTest {
    private val db = openTestCatalog()
    private val n = TIE_GROUP_MAX + 1_000

    @After
    fun close() = db.close()

    private suspend fun seed() {
        db.dao().insertTracks(
            (0 until n).map { i ->
                TrackEntity(
                    id = "device:$i", sourceId = "device", nativeId = "$i", title = "Song ${i % 997}", searchKey = "song ${i % 997}",
                    sortKey = "song ${i % 997}", artist = "Artist ${i % 31}", artistId = "ar${i % 31}", album = "Album ${i % 101}",
                    albumId = "al${i % 101}", trackNo = null, discNo = null, year = null, durationMs = 200_000, addedAt = 0,
                    mimeType = null, sizeBytes = null, artwork = null, uri = null, relinkKey = "k$i",
                )
            },
        )
        db.openHelper.writableDatabase.execSQL("INSERT INTO liked_track (trackId, relinkKey, likedAt) SELECT id, relinkKey, 7 FROM track")
    }

    @Test
    fun aPlaceInABigTieGroupReadsOneRow() = runBlocking {
        seed()
        for (down in listOf(true, false)) {
            val loader = SongLists(db.listsDao()).loader(SongScope.Liked, SortOrder(SongSort.DateLiked, down))
            val walked = ArrayList<RowKey>()
            var after: RowKey? = null
            while (true) {
                val page = loader.after(after, 500)
                walked += page.map { it.key }
                if (page.size < 500) break
                after = page.last().key
            }
            assertEquals(n, walked.size)
            val log = db.counted
            for (place in listOf(0, 1, TIE_GROUP_MAX + 3, n / 2, n - 1)) {
                log.reset()
                assertEquals("place $place, down $down", walked[place], loader.keyAt(place))
                val most = log.maxRead
                assertTrue("place $place, down $down: $most", most != null && most.rowsRead <= 1)
            }
            assertEquals(null, loader.keyAt(n))
        }
    }
}

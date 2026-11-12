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

// The Songs list holds at most a page plus a few rows per statement in
// every order and both directions: its count, its survey, a scroll from the
// top, a jump to the middle and a scroll back, counted at the database.
@RunWith(RobolectricTestRunner::class)
class SongsResidencyTest {
    private val db = openTestCatalog()
    private val n = 5_000
    private val page = 100

    @After
    fun close() = db.close()

    private suspend fun seed() {
        db.dao().insertTracks(
            (0 until n).map { i ->
                TrackEntity(
                    id = "device:$i", sourceId = "device", nativeId = "$i", title = "Song ${i % 997}", searchKey = "song ${i % 997}",
                    sortKey = "song ${i % 997}", artist = "Artist ${i % 31}", artistId = "ar${i % 31}", album = "Album ${i % 101}",
                    albumId = "al${i % 101}", trackNo = i % 12, discNo = 1, year = if (i % 9 == 0) null else 1960 + i % 60,
                    durationMs = 1_000L * (i % 400), addedAt = (i % 50).toLong(), mimeType = null, sizeBytes = null, artwork = null,
                    uri = null, albumOrder = i % 12, relinkKey = "k$i", genre = if (i % 2 == 0) "Rock" else "Jazz", rating = i % 6,
                    artistSortKey = "artist ${i % 31}", albumSortKey = "album ${i % 101}",
                )
            },
        )
        (0 until n step 4).forEach { db.openHelper.writableDatabase.execSQL("INSERT INTO liked_track (trackId, relinkKey, likedAt) VALUES ('device:$it', 'k$it', ${it % 13})") }
    }

    @Test
    fun everyOrderHoldsAPageAtATime() = runBlocking {
        seed()
        val lists = SongLists(db.listsDao())
        val log = db.counted
        val sorts = listOf(
            SongSort.Title, SongSort.Artist, SongSort.Album, SongSort.RecentlyAdded, SongSort.Year,
            SongSort.Length, SongSort.Rating, SongSort.Liked,
        )
        for (sort in sorts) {
            for (down in listOf(false, true)) {
                val name = "$sort ${if (down) "desc" else "asc"}"
                val loader = lists.loader(SongScope.All, SortOrder(sort, down))
                log.reset()
                assertEquals(name, n, loader.count())
                loader.survey(null)
                var after: RowKey? = null
                repeat(3) { after = loader.after(after, page).last().key }
                val middle = loader.keyAt(n / 2)!!
                loader.survey(middle)
                val back = loader.before(middle, page)
                assertEquals(name, page, back.size)
                loader.after(middle, page)
                val most = log.maxRead
                assertTrue("$name: $most", most == null || most.rowsRead <= page + 50)
            }
        }
    }
}

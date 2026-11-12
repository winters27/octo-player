package app.winters.octo.ui.paging

import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.rawQuery
import app.winters.octo.catalog.today.artistSongOrder
import app.winters.octo.listening.timed
import app.winters.octo.playback.ArtistOrder
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.artist.today.TODAY_ARTIST_TRACKS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

// A labeled pin: an artist page's songs, walked a page at a time both ways,
// are today's (catalog/today's artistSongOrder over ui/artist/today's base
// order, as c2208eaa had them): played songs first, most played first, of
// equal plays the one played last, then the rest in album order newest
// album first; with a group of 2,500 songs played as often at the same
// moment (walked on its own index), smaller tie groups, songs whose plays
// are only a recent play, albums alike in year and name, an album with no
// year and songs whose album the library lacks.
@RunWith(RobolectricTestRunner::class)
class ArtistOrderParityTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun theArtistPageIsTodaysOrder() = timed(90) {
        val random = Random(23)
        val albums = listOf("al-a", "al-b", "al-c", "al-d", "al-missing")
        val tracks = (0 until 3_200).map { i ->
            TrackEntity(
                id = "t%05d".format(random.nextInt(100_000)) + "-$i", sourceId = "device", nativeId = "$i", title = "Song $i", searchKey = "song", sortKey = "song %02d".format(i % 40),
                artist = "Band", artistId = "ar1", album = "", albumId = albums[random.nextInt(albums.size)], trackNo = null, discNo = null, year = null,
                durationMs = 0, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null, albumOrder = random.nextInt(30),
            )
        }
        db.dao().insertTracks(tracks)
        val sql = db.openHelper.writableDatabase
        for ((id, year, key) in listOf(Triple("al-a", 2001, "b"), Triple("al-b", null, "a"), Triple("al-c", 1999, "c"), Triple("al-d", 2001, "a"))) {
            sql.execSQL(
                "INSERT INTO album (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, year, songCount, durationMs, addedAt) VALUES (?, 'device', ?, ?, ?, ?, 'Band', 'ar1', ?, 0, 0, 0)",
                arrayOf<Any?>(id, id, key, key, key, year),
            )
        }
        val played = ArrayList<PlayedTrack>()
        sql.beginTransaction()
        try {
            tracks.forEachIndexed { i, t ->
                val (plays, last) = when {
                    i < 2_500 -> 3 to 100L
                    i < 2_800 -> 1 + random.nextInt(4) to 50L + random.nextInt(5)
                    i < 2_900 -> 0 to 0L
                    else -> return@forEachIndexed
                }
                sql.execSQL(
                    "INSERT INTO play_total (trackId, albumId, artistId, plays, lastPlayedAt, recentAt, phonePlays, phoneLastAt, sortKey, artistSortKey) VALUES (?, ?, 'ar1', ?, ?, ?, 0, NULL, ?, '')",
                    arrayOf<Any?>(t.id, t.albumId, plays, last, if (plays == 0) 77L else null, t.sortKey),
                )
                if (plays > 0) played += PlayedTrack(t, plays, last)
            }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
        val byId = tracks.associateBy { it.id }
        val base = db.listsDao().ids(rawQuery(TODAY_ARTIST_TRACKS, listOf(SqlValue.Text("ar1")))).map(byId::getValue)
        val today = artistSongOrder(base, played).map { it.id }

        val loader = ArtistLoaderSource(db.listsDao()).loader("ar1", ArtistOrder.Page)
        assertEquals(today, loader.walked(page = 97))
        assertEquals(today.size, loader.count())
        // Back from the end, a page at a time.
        val back = ArrayList<String>()
        var from = loader.keyAt(today.size - 1)!!
        back += today.last()
        while (true) {
            val page = loader.before(from, 97)
            back += page.map { it.row.id }
            if (page.size < 97) break
            from = page.last().key
        }
        assertEquals(today.asReversed(), back)
        assertTrue("a tie group past the held size", played.count { it.plays == 3 && it.lastPlayedAt == 100L } > TIE_GROUP_MAX)
    }
}

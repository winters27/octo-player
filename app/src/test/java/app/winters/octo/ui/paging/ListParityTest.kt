package app.winters.octo.ui.paging

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.rawQuery
import app.winters.octo.catalog.sortKey
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.today.songQuery
import app.winters.octo.listening.TodayPlays
import app.winters.octo.listening.TotalsRig
import app.winters.octo.listening.fillPlays
import app.winters.octo.listening.timed
import app.winters.octo.sort.today.byListening
import app.winters.octo.sort.today.songListening
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

// A labeled pin: every song list's rows, walked a few at a time by its
// keyset loader, equal today's whole query (sort/today, as c2208eaa had it)
// in every scope, order and direction, on generated data full of ties:
// names alike but for case and accents, names past the BMP, empty names,
// missing years, equal lengths, every rating, and likes at equal times.
@RunWith(RobolectricTestRunner::class)
class ListParityTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private val titles = listOf("a", "A", "Ångström", "angstrom", "𝄞 clef", "", "Zed", "zed", "100 Days", "10 Days", "_under", "Éclair")
    private val artists = listOf("Abba", "ABBA", "Björk", "bjork", "", "🎵 Notes")
    private val albums = listOf("One", "one", "Two", "", "Ünder")
    private val genres = listOf("Rock", "ROCK", "rock", "Jazz", "")

    private suspend fun seed(n: Int) {
        val random = Random(11)
        val tracks = (0 until n).map { i ->
            val title = titles[random.nextInt(titles.size)]
            val artist = artists[random.nextInt(artists.size)]
            val album = albums[random.nextInt(albums.size)]
            TrackEntity(
                id = "device:${random.nextInt(1_000_000)}-$i", sourceId = "device", nativeId = "$i", title = title,
                searchKey = title.lowercase(), sortKey = sortKey(title), artist = artist, artistId = "ar:$artist",
                album = album, albumId = "al:$album", trackNo = random.nextInt(3), discNo = 1,
                year = if (random.nextInt(4) == 0) null else 1990 + random.nextInt(4), durationMs = 1_000L * random.nextInt(5),
                addedAt = random.nextLong(5), mimeType = null, sizeBytes = null, artwork = null, uri = null,
                albumOrder = random.nextInt(4), relinkKey = "k$i", genre = genres[random.nextInt(genres.size)], rating = random.nextInt(6),
                artistSortKey = artist.lowercase(), albumSortKey = album.lowercase(),
            )
        }
        db.dao().insertTracks(tracks)
        tracks.filterIndexed { i, _ -> i % 3 == 0 }.forEachIndexed { i, t ->
            db.openHelper.writableDatabase.execSQL("INSERT INTO liked_track (trackId, relinkKey, likedAt) VALUES ('${t.id}', '${t.relinkKey}', ${i % 7})")
        }
    }

    // Today's rows: its query with only the ids selected.
    private suspend fun today(sort: SongSort, down: Boolean, scope: SongScope): List<String> {
        val q = songQuery(sort, down, scope)
        val sql = q.sql.replaceFirst(Regex("""^SELECT t\.\*, .+? AS heading FROM"""), "SELECT t.id FROM")
        return db.listsDao().ids(rawQuery(sql, q.args.map { SqlValue.Text(it) }))
    }

    private suspend fun walked(loader: ListLoader<TrackEntity>): List<String> {
        val out = ArrayList<String>()
        var after: RowKey? = null
        while (true) {
            val page = loader.after(after, 7)
            out += page.map { it.row.id }
            if (page.size < 7) return out
            after = page.last().key
        }
    }

    @Test
    fun everySongListEqualsToday() = runBlocking {
        seed(300)
        val lists = SongLists(db.listsDao())
        val sorts = listOf(
            SongSort.Title, SongSort.Artist, SongSort.Album, SongSort.RecentlyAdded, SongSort.Year,
            SongSort.Length, SongSort.Rating, SongSort.Liked, SongSort.DateLiked,
        )
        for (scope in listOf(SongScope.All, SongScope.Genre("rock"), SongScope.Liked)) {
            for (sort in sorts) {
                if (sort == SongSort.DateLiked && scope != SongScope.Liked) continue
                for (down in listOf(false, true)) {
                    val name = "$scope $sort ${if (down) "desc" else "asc"}"
                    val loader = lists.loader(scope, SortOrder(sort, down))
                    val want = today(sort, down, scope)
                    assertEquals(name, want, walked(loader))
                    assertEquals("$name count", want.size, loader.count())
                }
            }
        }
    }

    // The orders by plays, read from the stored play totals, equal today's
    // byListening over today's title order and today's played songs (plays
    // from the phone and two servers, sent plays, equal plays at equal
    // times), with title ties, in every scope, both ways.
    @Test
    fun listeningSortsEqualToday() = timed(90) {
        db.fillPlays(seed = 9, songs = 900, albums = 30)
        val sql = db.openHelper.writableDatabase
        sql.execSQL(
            "UPDATE track SET sortKey = 'song ' || (rowid % 40), genre = CASE WHEN rowid % 3 = 0 THEN 'Rock' ELSE 'Jazz' END, " +
                "artistSortKey = LOWER(artist), albumSortKey = LOWER(album)",
        )
        sql.execSQL("INSERT INTO liked_track (trackId, relinkKey, likedAt) SELECT id, '', rowid FROM track WHERE rowid % 4 = 0")
        val rig = TotalsRig(db, db.testMerge(null)).started()
        val played = songListening(TodayPlays(db, "home").tracks())
        assertTrue("songs were played", played.size > 100)
        val lists = SongLists(db.listsDao())
        for (scope in listOf(SongScope.All, SongScope.Genre("rock"), SongScope.Liked)) {
            for (sort in listOf(SongSort.MostPlayed, SongSort.RecentlyPlayed)) {
                for (down in listOf(false, true)) {
                    val name = "$scope $sort ${if (down) "desc" else "asc"}"
                    val want = byListening(today(sort, down, scope), { it }, played, sort == SongSort.MostPlayed, down)
                    val loader = lists.loader(scope, SortOrder(sort, down))
                    assertEquals(name, want, walked(loader))
                    assertEquals("$name count", want.size, loader.count())
                }
            }
        }
        rig.totals.awaitBuilt()
    }
}

package app.winters.octo.sort

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.winters.octo.catalog.rawQuery
import app.winters.octo.listening.TodayPlays
import app.winters.octo.listening.TotalsRig
import app.winters.octo.listening.fillPlays
import app.winters.octo.listening.timed
import app.winters.octo.sort.today.albumListening
import app.winters.octo.sort.today.albumQuery
import app.winters.octo.sort.today.artistListening
import app.winters.octo.sort.today.artistQuery
import app.winters.octo.sort.today.byListening
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import app.winters.octo.ui.paging.SqlValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import app.winters.octo.listening.today.withServerAlbumPlays as todayWithServerAlbumPlays

// A labeled pin: Albums and Artists by plays, read from the stored play
// totals' sums by album and by artist, are today's orders (sort/today:
// albumListening, artistListening and byListening over today's base query,
// as c2208eaa had them) on generated plays from the phone and two servers,
// sent plays, albums the library lacks, and plays at equal times, both ways.
@RunWith(RobolectricTestRunner::class)
class ListeningAggregateParityTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    // No saved orders: the orders asked for are given.
    private object NoSettings : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = emptyPreferences()
    }

    private suspend fun ids(sql: String, args: List<String>) = db.listsDao().ids(rawQuery(sql, args.map { SqlValue.Text(it) }))

    @Test
    fun albumsAndArtistsByPlaysAreTodays() = timed(90) {
        db.fillPlays(seed = 5, songs = 900, albums = 60)
        for (a in 0 until 13) {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO artist (id, sourceId, name, searchKey, sortKey, albumCount, songCount, artwork) VALUES (?, 'device', ?, ?, ?, 1, 1, NULL)",
                arrayOf<Any?>("ar$a", "Artist $a", "artist $a", "artist %02d".format(a % 5)),
            )
        }
        val rig = TotalsRig(db, db.testMerge(null)).started()
        val library = SortedLibrary(db.dao(), SortSettings(NoSettings), db, rig.totals)
        val user = db.userDao()
        val played = TodayPlays(db, "home").tracks()
        assertTrue("songs were played", played.size > 100)
        val albums = albumListening(played, todayWithServerAlbumPlays(user.playedAlbums().first(), user.serverPlayedAlbums().first()))
        val artists = artistListening(played)
        for (most in listOf(true, false)) {
            for (down in listOf(true, false)) {
                val name = "${if (most) "most" else "recently"} played ${if (down) "desc" else "asc"}"
                val albumSort = if (most) AlbumSort.MostPlayed else AlbumSort.RecentlyPlayed
                val albumBase = albumQuery(albumSort, down).let { q -> ids(q.sql.replaceFirst(Regex("""^SELECT a\.\*, .+? AS heading FROM"""), "SELECT a.id FROM"), q.args) }
                assertEquals("albums $name", byListening(albumBase, { it }, albums, most, down), library.albumIds(SortOrder(albumSort, down)))
                val artistSort = if (most) ArtistSort.MostPlayed else ArtistSort.RecentlyPlayed
                val artistBase = artistQuery(artistSort, down).let { q -> ids(q.sql.replaceFirst(Regex("""^SELECT r\.\*, .+? AS heading FROM"""), "SELECT r.id FROM"), q.args) }
                assertEquals("artists $name", byListening(artistBase, { it }, artists, most, down), library.artistIds(SortOrder(artistSort, down)))
            }
        }
    }
}

package app.winters.octo.ui.paging

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.Pause
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.TestHooks
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.library
import app.winters.octo.catalog.playlistOf
import app.winters.octo.catalog.put
import app.winters.octo.device.DEVICE
import app.winters.octo.listening.timed
import app.winters.octo.playback.ArtistOrder
import app.winters.octo.playback.QUEUE_WINDOW
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QueryField
import app.winters.octo.query.QueryOp
import app.winters.octo.query.QueryRule
import app.winters.octo.sort.DownloadSort
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The small check a tap makes reads only: every list's check is one
// statement (a list filtered in Kotlin, at most two of its chunks; a
// playlist two, its set counted on its index before a row is read, so a
// set of rows whose songs are gone is never walked), writes nothing and
// takes no lock. On a list of 30 rows it is the whole list in the list's
// order with the keys its pages give; on one of 600 it says the list is
// big. It ends while a swap is parked inside its transaction.
@RunWith(RobolectricTestRunner::class)
class SmallCheckReadsTest {
    private val dbs = ArrayList<CatalogDatabase>()

    @After
    fun close() = dbs.forEach { it.close() }

    // A library of `n` songs, one artist's, on one album, each in a
    // playlist, a phone folder, an album listing's copy and the done
    // downloads, with ratings, likes, years, genres and plays.
    private suspend fun seeded(n: Int): CatalogDatabase {
        val db = openTestCatalog().also { dbs += it }
        val tracks = (0 until n).map { i ->
            TrackEntity(
                id = "song$i", sourceId = "device", nativeId = "$i", title = "Song ${i % 17}", searchKey = "song ${i % 17}", sortKey = "song ${i % 17}",
                artist = "Band", artistId = "ar1", album = "One", albumId = if (i % 5 == 0) "al2" else "al1", trackNo = i % 9, discNo = 1,
                year = if (i % 4 == 0) null else 1990 + i % 7, durationMs = 1_000L * (i % 6), addedAt = (i % 8).toLong(), mimeType = null,
                sizeBytes = null, artwork = null, uri = null, albumOrder = i, relinkKey = "k$i", genre = if (i % 2 == 0) "Rock" else "Jazz",
                rating = i % 6, artistSortKey = "band", albumSortKey = "one",
            )
        }
        db.dao().insertTracks(tracks)
        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO album (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, year, songCount, durationMs, addedAt) VALUES ('al1', 'device', 'al1', 'One', 'one', 'one', 'Band', 'ar1', 1999, 0, 0, 0)")
        tracks.forEachIndexed { i, t ->
            if (i % 3 == 0) sql.execSQL("INSERT INTO liked_track (trackId, relinkKey, likedAt) VALUES (?, '', ?)", arrayOf<Any?>(t.id, i % 4))
            if (i % 2 == 0) {
                sql.execSQL(
                    "INSERT INTO play_total (trackId, albumId, artistId, plays, lastPlayedAt, recentAt, phonePlays, phoneLastAt, sortKey, artistSortKey) VALUES (?, ?, 'ar1', ?, ?, NULL, 0, NULL, ?, 'band')",
                    arrayOf<Any?>(t.id, t.albumId, 1 + i % 3, 1_000L + i % 5, t.sortKey),
                )
            }
            sql.execSQL(
                "INSERT INTO source_track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, albumOrder, relinkKey, genre, mergedId, storageVolume, folder) " +
                    "VALUES (?, 'device', ?, 'S', 's', 's', 'Band', '', 'One', 'al1', 0, 0, 0, '', '', ?, 'primary', 'Music/One')",
                arrayOf<Any?>(t.id, "$i", t.id),
            )
            sql.execSQL(
                "INSERT INTO download (trackId, sourceId, serverId, path, sizeBytes, format, state, progress, addedAt, reason, title, artist, fileSize, titleKey) " +
                    "VALUES (?, 'server:s', ?, '', ?, NULL, 'Done', 1.0, ?, '', ?, 'Band', NULL, ?)",
                arrayOf<Any?>(t.id, "s$i", 1_000L * (i % 9), (i % 6).toLong(), t.title, t.title.toByteArray()),
            )
            sql.execSQL(
                "INSERT INTO lists_album_entry (owner, position, songId, shownId, isFind, albumOrder, trackNo, discNo) VALUES (?, ?, ?, ?, 0, ?, NULL, NULL)",
                arrayOf<Any?>(WorkOwners.marked("vm:copy"), i.toLong(), t.id, t.id, if (i % 2 == 0) null else i.toLong()),
            )
        }
        PlaylistSets(db).playlistOf("p", n)
        val folders = PhoneFolders(db, db.testMerge(null), { Long.MAX_VALUE })
        folders.copy(WorkOwners.marked("vm:folder"), listOf(PhoneFolder("One", emptyList(), n, listOf(FolderPlace("primary", "Music/One")), null)), SortOrder(SongSort.Title, true), ranked = false)
        return db
    }

    // Every list a tap can play, by name.
    private fun cases(db: CatalogDatabase): List<Pair<String, ListLoader<*>>> {
        val dao = db.listsDao()
        val songs = SongLists(dao)
        val out = ArrayList<Pair<String, ListLoader<*>>>()
        for (scope in listOf(SongScope.All, SongScope.Genre("rock"), SongScope.Liked)) {
            for (sort in SongSort.entries) {
                if (sort == SongSort.DateLiked && scope != SongScope.Liked) continue
                for (down in listOf(false, true)) out += "$scope $sort ${if (down) "desc" else "asc"}" to songs.loader(scope, SortOrder(sort, down))
            }
        }
        out += "album" to albumLibraryLoader(dao, "al1")
        out += "album copy" to albumCopyLoader(dao, WorkOwners.marked("vm:copy"))
        for (order in ArtistOrder.entries) out += "artist $order" to ArtistLoaderSource(dao).loader("ar1", order)
        out += "albums" to GroupsLoader(dao, artists = false, groupIds = { listOf("al2", "al1") }, tablesRead = arrayOf("track"))
        out += "artists" to GroupsLoader(dao, artists = true, groupIds = { listOf("ar1") }, tablesRead = arrayOf("track"))
        out += "playlist" to PlaylistLoader(db, PlaylistSets(db), "p", WorkOwners.marked("vm:pl"))
        for (sort in DownloadSort.entries) out += "downloads $sort" to DownloadsLists(dao).loader("server:s", SortOrder(sort, true))
        out += "folder" to PhoneFolders(db, db.testMerge(null), { Long.MAX_VALUE }).loader(WorkOwners.marked("vm:folder"))
        return out
    }

    private suspend fun <T : Any> checked(db: CatalogDatabase, name: String, loader: ListLoader<T>, n: Int, most: Int = 1) {
        db.counted.reset()
        db.counted.recording = true
        val small = try {
            loader.small()
        } finally {
            db.counted.recording = false
        }
        val reads = db.counted.own
        assertTrue("$name: read nothing", reads.isNotEmpty())
        assertTrue("$name: ${reads.size} statements: $reads", reads.size <= most)
        assertTrue("$name wrote: $reads", reads.none { it.rowsChanged > 0 || it.sql.trimStart().take(6).uppercase() in setOf("INSERT", "UPDATE", "DELETE") })
        // Each arm of a statement reads at most QUEUE_WINDOW + 1 rows.
        assertTrue("$name read past its bound: $reads", reads.all { it.rowsRead <= (QUEUE_WINDOW + 1) * (it.sql.split(" UNION ALL ").size) })
        // A scope of liked songs reads from its likes, never a walk of
        // every song for the few liked.
        if ("Liked" in name.substringBefore(' ')) {
            for (read in reads) {
                val plan = db.query("EXPLAIN QUERY PLAN ${read.sql}", arrayOfNulls<Any?>(read.boundArgs)).use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(3)) }
                }
                assertTrue("$name walks every song: $plan", plan.none { it == "SCAN t" || it.startsWith("SCAN t ") })
            }
        }
        val whole = loader.walked(page = 7) { loader.trackIdOf(it) }
        if (whole.size > QUEUE_WINDOW) {
            assertTrue("$name of $n is big", n > QUEUE_WINDOW)
            assertNull("$name is big", small)
        } else {
            assertEquals("$name rows in order", whole, small!!.map { loader.trackIdOf(it.row) })
            val keys = ArrayList<RowKey>()
            var after: RowKey? = null
            while (true) {
                val page = loader.after(after, 7)
                keys += page.map { it.key }
                if (page.size < 7) break
                after = page.last().key
            }
            assertEquals("$name keys", keys, small.map { it.key })
        }
    }

    private fun run(n: Int) = timed(120) {
        val db = seeded(n)
        for ((name, loader) in cases(db)) {
            @Suppress("UNCHECKED_CAST")
            checked(db, "$name of $n", loader as ListLoader<Any>, n, most = if (loader is PlaylistLoader) 2 else 1)
        }
        // A search's list ends at its cap's row, a key read once, then its
        // rows in one statement.
        checked(db, "search of $n", SearchLoader(db.listsDao(), "song", 1_000), n, most = 2)
        // A filter keeping every song walks at most two chunks of the list.
        val kept = LibraryQuery(rules = listOf(QueryRule(QueryField.Genre, QueryOp.IsNot, text = "Pop")))
        val inputs = FilterInputs(
            liked = { emptySet() },
            plays = { emptyMap() },
            songs = { ids -> db.dao().tracksByIdsUnordered(ids) },
        )
        checked(db, "filtered of $n", FilteredLoader(SongLists(db.listsDao()).loader(SongScope.All, SortOrder(SongSort.Title, false)), kept, inputs), n, most = 2)
    }

    @Test
    fun aSmallListIsOneReadOfTheWholeList() = run(30)

    @Test
    fun aBigListIsOneReadThatSaysSo() = run(600)

    @Test
    fun aParkedSwapHoldsUpNoSmallCheck() = timed(60) {
        val db = openTestCatalog().also { dbs += it }
        db.put(library(DEVICE, 30))
        db.testMerge(null).rebuild()
        val pause = Pause()
        val merge = db.testMerge(null, TestHooks(atSwap = pause))
        db.put(library(DEVICE, 31))
        coroutineScope {
            val swap = async { merge.rebuild() }
            pause.reached.await()
            val dao = db.listsDao()
            val album = dao.ids(app.winters.octo.catalog.rawQuery("SELECT albumId FROM track LIMIT 1", emptyList())).single()
            val artist = dao.ids(app.winters.octo.catalog.rawQuery("SELECT artistId FROM track LIMIT 1", emptyList())).single()
            val lists = listOf(
                SongLists(dao).loader(SongScope.All, SortOrder(SongSort.Title, false)),
                SongLists(dao).loader(SongScope.All, SortOrder(SongSort.Rating, true)),
                albumLibraryLoader(dao, album),
                ArtistLoaderSource(dao).loader(artist, ArtistOrder.Page),
                ArtistLoaderSource(dao).loader(artist, ArtistOrder.Albums),
            )
            withTimeout(5_000) {
                lists.forEach { assertTrue("the library as it stood", (it.small()?.size ?: 0) in 1..30) }
            }
            pause.release()
            swap.await()
        }
    }
}

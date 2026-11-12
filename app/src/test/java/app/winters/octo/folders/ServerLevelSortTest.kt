package app.winters.octo.folders

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.searchKey
import app.winters.octo.catalog.sortKey
import app.winters.octo.discovery.ListServer
import app.winters.octo.discovery.SERVER_SOURCE
import app.winters.octo.discovery.putLibrary
import app.winters.octo.discovery.serverFolders
import app.winters.octo.discovery.today.todayResolve
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.today.sortFolderSongs
import app.winters.octo.subsonic.Song
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.paging.Keyed
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.RowKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files
import kotlin.random.Random

// A server level's songs show in the order chosen for a folder's songs,
// exactly as today's page sorted the level's songs (sort/today's
// sortFolderSongs, as c2208eaa had it, over the songs as today's level
// resolved them), in every order both ways, with the level's folders first
// as the server lists them: read in an order, then keyed anew in their own
// rows for each order after, a page at a time, with no new read; and the
// songs alone, as a play walks them, in the same order. Names alike but for
// case and accents, empty, past the BMP; ties in every key; finds, a song
// listed twice and a song whose library row is gone among them. For a level
// smaller than a page and one bigger.
@RunWith(RobolectricTestRunner::class)
class ServerLevelSortTest {
    private val db = openTestCatalog()
    private val drains = Files.createTempDirectory("drain").toFile()
    private var server: ListServer? = null

    @After
    fun close() {
        server?.close()
        db.close()
        drains.deleteRecursively()
    }

    private val names = listOf("", "a", "A", "b", "Éclair", "eclair", "The Band", "zeta", "𝄞 clef", "Ünder")

    // Every order a folder's songs offer, both ways.
    private val orders = listOf(SongSort.FolderOrder, SongSort.Title, SongSort.Artist, SongSort.Album, SongSort.RecentlyAdded, SongSort.Year, SongSort.Length)
        .flatMap { sort -> listOf(SortOrder(sort, false), SortOrder(sort, true)) }

    @Test
    fun aLevelSmallerThanAPageShowsEveryOrderAsTodaysPage() = parity(library = 30, finds = 4, folders = 3, seed = 7)

    @Test
    fun aLevelBiggerThanAPageShowsEveryOrderAsTodaysPage() = parity(library = 1_100, finds = 60, folders = 12, seed = 11)

    private fun parity(library: Int, finds: Int, folders: Int, seed: Int) = runBlocking {
        val random = Random(seed)
        fun name() = names[random.nextInt(names.size)]
        val tracks = (0 until library).map { i ->
            val title = name() + if (random.nextInt(3) == 0) "" else " ${random.nextInt(40)}"
            TrackEntity(
                id = "t$i", sourceId = SERVER_SOURCE, nativeId = "t$i", title = title, searchKey = searchKey(title), sortKey = sortKey(title),
                artist = name(), artistId = "ar", album = name(), albumId = "al:${random.nextInt(5)}", trackNo = null, discNo = null,
                year = if (random.nextInt(4) == 0) null else 1990 + random.nextInt(4), durationMs = 1_000L * random.nextInt(5), addedAt = random.nextLong(5),
                mimeType = null, sizeBytes = null, artwork = null, uri = null, albumOrder = random.nextInt(4), relinkKey = "rk-t$i",
            )
        }
        db.putLibrary(tracks) { "srv${it.id.removePrefix("t")}" }
        // The library row of one song is gone; its copy on the server stays.
        db.openHelper.writableDatabase.execSQL("DELETE FROM track WHERE id = 't5'")
        val songs = tracks.associate { "srv${it.id.removePrefix("t")}" to Song("srv${it.id.removePrefix("t")}", title = it.title, artist = it.artist) } +
            (0 until finds).associate { i ->
                "f$i" to Song("f$i", title = "Faraway ${name()} $i", artist = "Elsewhere", album = name(), duration = random.nextInt(4), isExternal = true)
            }
        val listed = (songs.keys.filter { it.startsWith("srv") }.shuffled(random) + songs.keys.filter { it.startsWith("f") }).shuffled(random)
        // One song listed again further on.
        val own = listed + listed[1]
        val listServer = ListServer(songs).also { server = it }
        val subs = (0 until folders).map { "x-d$it" }
        listServer.folders["x"] = subs to own
        val shownFolders = subs.map { "folder $it F $it" }
        val today = todayResolve(db, SERVER_SOURCE, own.map { songs.getValue(it) })
        assertTrue(today.any { it.id.startsWith("find:") } && today.none { it.id == "t5" } && today.size == own.size - 2)
        val levels = db.serverFolders(listServer, WorkOwners(), page = 50, drains = drains)

        // Read in one order: keyed as it is written.
        val first = SortOrder(SongSort.Title, true)
        val level = levels.read("x", WorkOwners.marked("vm:1"), first)!!
        assertEquals(shownFolders + sortFolderSongs(today, first).map { it.id }, shown(walked(levels.loader(WorkOwners.marked("vm:1")))))

        // Each order after: keyed anew in the level's own rows.
        for (order in orders) {
            db.counted.reset()
            levels.rekey(level, order)
            val most = db.counted.maxRead
            assertNotNull("the keying read nothing", most)
            assertTrue("a page at a time: $most", most!!.rowsRead <= 51)
            val want = sortFolderSongs(today, order).map { it.id }
            val page = levels.loader(WorkOwners.marked("vm:1"))
            assertEquals("$order", shownFolders + want, shown(walked(page)))
            assertEquals(folders + want.size, page.count())
            // The songs alone, as a play walks them; a level of at most
            // QUEUE_WINDOW songs is small, whole and in order.
            val alone = levels.songs(WorkOwners.marked("vm:1"))
            assertEquals("$order alone", want, walked(alone).map { it.row.id })
            val small = alone.small()
            if (want.size <= 500) assertEquals(want, small!!.map { it.row.id }) else assertNull(small)
        }
        assertEquals("no new read", 1, listServer.calls.count { it == "getMusicDirectory" })
    }

    private suspend fun <T : Any> walked(loader: ListLoader<T>, page: Int = 37): List<Keyed<T>> {
        val out = ArrayList<Keyed<T>>()
        var after: RowKey? = null
        while (true) {
            val rows = loader.after(after, page)
            out += rows
            if (rows.size < page) return out
            after = rows.last().key
        }
    }

    private fun shown(rows: List<Keyed<LevelRow>>) = rows.map {
        when (val row = it.row) {
            is LevelRow.Folder -> "folder ${row.folder.id} ${row.folder.name}"
            is LevelRow.Song -> row.track.id
        }
    }
}

package app.winters.octo.folders

import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.data.Drains
import app.winters.octo.discovery.ListServer
import app.winters.octo.discovery.SERVER_SOURCE
import app.winters.octo.discovery.libraryTrack
import app.winters.octo.discovery.putLibrary
import app.winters.octo.discovery.renameSong
import app.winters.octo.discovery.toFind
import app.winters.octo.discovery.today.todayResolve
import app.winters.octo.listening.timed
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.ListStart
import app.winters.octo.playback.QUEUE_WINDOW
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.today.sortFolderSongs
import app.winters.octo.subsonic.Song
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.common.LoadState
import app.winters.octo.ui.folders.LevelInView
import app.winters.octo.ui.folders.ServerLevelPages
import app.winters.octo.ui.paging.Keyed
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.ListReads
import app.winters.octo.ui.paging.RowKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files

// A server folder's level is read into rows of its own under the page's
// owner, never held whole: its folders, then its songs, in the order the
// server lists them, each song as today's level resolved it (a song the
// library has from the server is that song, the rest are finds, a song
// listed twice shows once where it first comes, one whose library song is
// gone is left out), read a page at a time. Two levels on the way keep their
// own rows; a level read again turns to the new read and lets the old one
// go; a level off the way, and every level once the page is done, is let
// go, rows and all. A new order keys the songs anew in place. A tap on a
// song plays the level's songs from it in the order shown: named one by one
// in a level of at most QUEUE_WINDOW songs, else the level itself.
@RunWith(RobolectricTestRunner::class)
class ServerLevelsTest {
    private val db = openTestCatalog()
    private val songs = (0 until 1_400).associate { "srv$it" to Song("srv$it", title = "Title $it", artist = "Artist") } +
        (0 until 120).associate { "f$it" to Song("f$it", title = "Find $it", artist = "Elsewhere", isExternal = true) }
    private val server = ListServer(songs)
    private val drains = Files.createTempDirectory("drain").toFile()
    private val owners = WorkOwners()
    private val client = server.client()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val releasing = CoroutineScope(Job())

    @After
    fun close() {
        runBlocking {
            scope.coroutineContext.job.cancelAndJoin()
            releasing.coroutineContext.job.children.forEach { it.join() }
        }
        server.close()
        db.close()
        drains.deleteRecursively()
    }

    // The library: every server song but the finds, each with its copy on
    // the server, and one song whose library row is gone.
    private fun library() {
        db.putLibrary((0 until 1_400).map { libraryTrack("t$it", "Title $it") }) { "srv${it.id.removePrefix("t")}" }
        db.openHelper.writableDatabase.execSQL("DELETE FROM track WHERE id = 't7'")
    }

    // The app resolves a page of songs in place: each as today's resolve
    // makes it alone, finds kept as the app keeps them.
    private suspend fun inPlace(page: List<Song>): List<TrackEntity?> = page.map { song ->
        todayResolve(db, SERVER_SOURCE, listOf(song)).firstOrNull()?.also { track ->
            if (track.id.startsWith("find:")) db.onlineDao().keep(listOf(song.toFind(SERVER_SOURCE, 0)))
        }
    }

    private fun folders(page: Int = 50) = ServerFolders(
        flowOf(ServerName.None), db, Drains(drains), FreeSpace { Long.MAX_VALUE }, owners, { client }, ::inPlace, page,
    )

    // A level of `n` folders and the songs `own`, under folder `id`.
    private fun level(id: String, n: Int, own: List<String>) {
        server.folders[id] = (0 until n).map { "$id-d$it" } to own
    }

    // Every row a loader shows, walked in pages of `page`.
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

    // Today's level: its folders as listed, then its songs as today's
    // resolve of them all answers.
    private fun today(id: String): List<String> {
        val (subs, own) = server.folders.getValue(id)
        return subs.map { "folder $it F $it" } + todayResolve(db, SERVER_SOURCE, own.map { songs.getValue(it) }).map { it.id }
    }

    private fun rows(owner: String) = db.query("SELECT COUNT(*) FROM lists_server_dir WHERE owner = ?", arrayOf(owner)).use { it.moveToFirst(); it.getInt(0) }

    // Songs in the server's order: the library's, a find every 12th, one
    // listed again three pages on, and the one whose library row is gone.
    private fun own(n: Int): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until n) {
            out += "srv${(i * 13) % 1_400}"
            if (i % 12 == 5) out += "f${(i / 12) % 120}"
            if (i == 160) out += "srv${(3 * 13) % 1_400}"
        }
        return out + "srv7"
    }

    @Test
    fun aLevelBiggerThanAPageIsItsFoldersThenItsSongsAsTheServerListsThem() = timed(60) {
        library()
        level("x", 140, own(1_100))
        val expected = today("x")
        db.counted.reset()
        val level = runBlocking { folders().read("x", WorkOwners.marked("vm:1")) }!!
        assertEquals(140, level.folders)
        val loader = folders().loader(WorkOwners.marked("vm:1"))
        val walked = runBlocking { walked(loader) }
        assertEquals(walked.size, runBlocking { loader.count() })
        assertNull("over QUEUE_WINDOW rows is not small", runBlocking { loader.small() })
        // The read and the walk never held more than a page of rows.
        assertTrue("${db.counted.maxRead}", db.counted.maxRead!!.rowsRead <= 501)
        assertEquals(expected, shown(walked))
        assertEquals(1, server.calls.count { it == "getMusicDirectory" })
        assertTrue(drains.listFiles().isNullOrEmpty())
        // A song's row is found by its song, and a swap's rename follows it.
        val key = runBlocking { loader.keyOf("t13") }!!
        runBlocking { db.renameSong("t13", "t13-new") }
        assertEquals(null, runBlocking { loader.keyOf("t13") })
        assertEquals(key.values, runBlocking { loader.keyOf("t13-new") }!!.values)
        assertTrue("t13-new" in shown(runBlocking { walked(loader) }))
    }

    @Test
    fun twoLevelsOnTheWayKeepTheirOwnRowsAndALevelOffTheWayIsLetGo() = timed(60) {
        library()
        level("", 30, own(200))
        level("a", 600, own(700).drop(300))
        var onTheWay = setOf("", "a")
        val pages = ServerLevelPages(scope, folders(), owners::vm, ListReads.Plain, { emptyFlow<Unit>() }, { onTheWay }, releasing, MutableStateFlow(SERVER_LISTED))
        runBlocking {
            pages.load(null)!!.join()
            pages.load("a")!!.join()
        }
        val top = (pages.levels.value[""] as LoadState.Ready).data
        val inner = (pages.levels.value["a"] as LoadState.Ready).data
        val topOwner = (top.handle.loader as LevelInView).now.owner
        val innerOwner = (inner.handle.loader as LevelInView).now.owner
        assertNotEquals(topOwner, innerOwner)
        assertEquals(today(""), shown(runBlocking { walked(top.handle.loader) }))
        assertEquals(today("a"), shown(runBlocking { walked(inner.handle.loader) }))
        // A level shown is not read again on its own.
        assertNull(pages.load("a"))
        // Back up: the inner level and its rows go; the top keeps its own.
        onTheWay = setOf("")
        pages.keepOnTheWay()
        runBlocking { releasing.coroutineContext.job.children.forEach { it.join() } }
        assertEquals(setOf(""), pages.levels.value.keys)
        assertEquals(0, rows(innerOwner))
        assertEquals(today(""), shown(runBlocking { walked(top.handle.loader) }))
        // The page done: every level goes.
        pages.close()
        runBlocking { releasing.coroutineContext.job.children.forEach { it.join() } }
        assertEquals(0, rows(topOwner))
    }

    @Test
    fun aLevelReadAgainTurnsToTheNewReadAndLetsTheOldOneGo() = timed(60) {
        library()
        level("z", 20, own(100))
        val pages = ServerLevelPages(scope, folders(), owners::vm, ListReads.Plain, { emptyFlow<Unit>() }, { setOf("z") }, releasing, MutableStateFlow(SERVER_LISTED))
        runBlocking { pages.load("z")!!.join() }
        val first = (pages.levels.value["z"] as LoadState.Ready).data
        val before = (first.handle.loader as LevelInView).now.owner
        // The folder changes on the server; a pull reads it again.
        level("z", 25, own(120).reversed())
        val shownNow = today("z")
        runBlocking { pages.reloadAll().forEach { it.join() } }
        runBlocking { releasing.coroutineContext.job.children.forEach { it.join() } }
        val again = (pages.levels.value["z"] as LoadState.Ready).data
        assertTrue("the page keeps its list", again.handle === first.handle)
        val turned = (again.handle.loader as LevelInView).now.owner
        assertNotEquals(before, turned)
        assertEquals(25, again.level.folders)
        assertEquals(shownNow, shown(runBlocking { walked(again.handle.loader) }))
        assertEquals(0, rows(before))
        // A read that fails (the folder gone from the server) keeps the last.
        server.folders.remove("z")
        runBlocking { pages.reloadAll().forEach { it.join() } }
        val kept = (pages.levels.value["z"] as LoadState.Ready).data
        assertEquals(turned, (kept.handle.loader as LevelInView).now.owner)
        assertEquals(shownNow, shown(runBlocking { walked(kept.handle.loader) }))
        pages.close()
        runBlocking { releasing.coroutineContext.job.children.forEach { it.join() } }
        assertEquals(0, rows(turned))
    }

    // A tap on a song plays the level's songs from it in the order shown:
    // named one by one when the level shows at most QUEUE_WINDOW of them
    // (its folders not counted), else the level itself in that order, from
    // the row tapped.
    @Test
    fun aTappedSongPlaysTheLevelsSongsInTheOrderShown() = timed(60) {
        library()
        level("big", 40, own(1_100))
        level("small", 600, own(120))
        val title = SortOrder(SongSort.Title, descending = false)
        val pages = ServerLevelPages(scope, folders(), owners::vm, ListReads.Plain, { emptyFlow<Unit>() }, { setOf("big", "small") }, releasing, MutableStateFlow(title))
        runBlocking {
            pages.load("big")!!.join()
            pages.load("small")!!.join()
            val big = (pages.levels.value["big"] as LoadState.Ready).data
            val songs = walked(big.handle.loader).filter { it.row is LevelRow.Song }
            assertTrue(songs.size > QUEUE_WINDOW)
            val tapped = songs[700]
            val id = (tapped.row as LevelRow.Song).track.id
            assertEquals(ListSpec.ServerFolder("big", recursive = false, title) to ListStart.Row(id, tapped.key), pages.tapped("big", id, tapped.key))
            val small = (pages.levels.value["small"] as LoadState.Ready).data
            val few = walked(small.handle.loader).filter { it.row is LevelRow.Song }
            assertTrue("more rows than a window, fewer songs", 600 + few.size > QUEUE_WINDOW && few.size <= QUEUE_WINDOW)
            val ids = few.map { (it.row as LevelRow.Song).track.id }
            assertEquals(ListSpec.Ids(ids) to ListStart.Row(ids[60], null), pages.tapped("small", ids[60], few[60].key))
        }
        pages.close()
    }

    // A new order keys the level's songs anew in its own rows, with no new
    // read, and the page shows the level from its top in that order; its
    // folders stay first, as the server lists them.
    @Test
    fun aNewOrderKeysTheLevelInPlaceAndShowsItFromTheTop() = timed(60) {
        library()
        level("x", 30, own(700))
        val order = MutableStateFlow(SERVER_LISTED)
        val pages = ServerLevelPages(scope, folders(), owners::vm, ListReads.Plain, { emptyFlow<Unit>() }, { setOf("x") }, releasing, order)
        runBlocking { pages.load("x")!!.join() }
        val page = (pages.levels.value["x"] as LoadState.Ready).data
        val owner = (page.handle.loader as LevelInView).now.owner
        assertEquals(today("x"), shown(runBlocking { walked(page.handle.loader) }))
        val (subs, own) = server.folders.getValue("x")
        val listed = todayResolve(db, SERVER_SOURCE, own.map { songs.getValue(it) })
        for (next in listOf(SortOrder(SongSort.Artist, false), SortOrder(SongSort.Title, true), SERVER_LISTED.copy(descending = true))) {
            order.value = next
            runBlocking { page.handle.asked.first { it == next } }
            assertEquals("$next", subs.map { "folder $it F $it" } + sortFolderSongs(listed, next).map { it.id }, shown(runBlocking { walked(page.handle.loader) }))
            assertEquals("keyed in its own rows", owner, (page.handle.loader as LevelInView).now.owner)
        }
        assertEquals("no new read", 1, server.calls.count { it == "getMusicDirectory" })
        pages.close()
    }
}

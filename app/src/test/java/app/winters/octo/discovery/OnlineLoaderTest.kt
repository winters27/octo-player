package app.winters.octo.discovery

import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.findId
import app.winters.octo.discovery.today.todayResolve
import app.winters.octo.listening.timed
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.OnlineKind
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random
import app.winters.octo.discovery.today.albumHighlight as todayHighlight

// A labeled pin. A server list or album read into an owner's rows shows
// exactly what today's resolve made of the whole answer: songs the library
// has from the server (unless marked outside it), songs matched by title and
// artist through any entry's keys, finds, a find downloaded since as the
// song it became, a library song gone left out, every song once. Read with
// pages of 50 entries; no statement reads more than a page and a few rows.
@RunWith(RobolectricTestRunner::class)
class OnlineLoaderTest {
    private val db = openTestCatalog()
    private val server = ListServer(songs())

    @After
    fun close() {
        server.close()
        db.close()
    }

    // 3,000 entries over 400 library songs: by server id, by title alone
    // (marked outside, or with no copy), finds, repeats, an adopted find, a
    // song since gone.
    private fun songs(): Map<String, Song> {
        val r = Random(3)
        val out = LinkedHashMap<String, Song>()
        for (i in 0 until 400) {
            out["srv$i"] = Song("srv$i", title = "Title ${i % 37} ${i / 37}", artist = "Artist ${i % 11}", duration = 200 + i % 5)
        }
        for (i in 0 until 200) {
            val near = r.nextInt(400)
            out["ext$i"] = Song("ext$i", title = "Title ${near % 37} ${near / 37}", artist = "Artist ${near % 11}", duration = 200 + near % 5, isExternal = true)
        }
        for (i in 0 until 300) out["new$i"] = Song("new$i", title = "Elsewhere $i", artist = "Nobody", duration = 180)
        return out
    }

    private fun library() {
        db.putLibrary((0 until 400).map { libraryTrack("t$it", "Title ${it % 37} ${it / 37}", "Artist ${it % 11}", (200 + it % 5) * 1000L) }) { t ->
            // Every third song has no copy on the server: found by title.
            t.id.removePrefix("t").toInt().takeIf { it % 3 != 0 }?.let { "srv$it" }
        }
    }

    private fun entries(n: Int): List<String> {
        val r = Random(7)
        val ids = server.songs.keys.toList()
        return List(n) { ids[r.nextInt(ids.size)] }
    }

    @Test
    fun aListShowsWhatTodaysResolveMadeOfIt() = timed(120) {
        library()
        server.lists["L"] = entries(3_000)
        // A find downloaded since: it shows as the song it became.
        runBlocking {
            db.onlineDao().keep(listOf(server.songs.getValue("new1").toFind(SERVER_SOURCE, 0).copy(adoptedId = "t5")))
            // A library song gone since its copy was made: left out.
            db.openHelper.writableDatabase.execSQL("DELETE FROM track WHERE id = 't8'")
        }
        val reads = db.serverReads(server)
        db.counted.reset()
        val head = runBlocking { reads.read(ServerListKind.List, "L", WorkOwners.marked("vm:1")) }!!
        val most = db.counted.maxRead!!
        assertTrue("$most", most.rowsRead <= SERVER_PAGE)
        assertTrue(head.complete)
        assertEquals("List L", head.title)
        val today = todayResolve(db, SERVER_SOURCE, server.lists.getValue("L").map { server.songs.getValue(it) }) { found ->
            runBlocking { db.onlineDao().byIds(listOf(found.id)).firstOrNull() } ?: found
        }
        val shown = runBlocking { reads.shownIds(WorkOwners.marked("vm:1")) }
        assertEquals(today.map { it.id }, shown)
        assertTrue("t5" in shown && findId("new1") !in shown)
        assertFalse("t8" in shown)
        // The read's work rows are gone; its entries and heading stay.
        listOf("server_key", "server_match", "server_lookup").forEach { table ->
            assertEquals(table, 0, db.query("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) })
        }
        assertEquals(1, server.calls.count { it == "getPlaylist" })
    }

    @Test
    fun anAlbumReadsTheSameWithItsHeading() = timed(60) {
        library()
        server.albums["A"] = "The Album" to entries(800)
        val reads = db.serverReads(server)
        val head = runBlocking { reads.read(ServerListKind.Album, "A", WorkOwners.marked("vm:1")) }!!
        assertEquals("The Album", head.title)
        assertEquals("Artist", head.artist)
        assertEquals(2001, head.year)
        val today = todayResolve(db, SERVER_SOURCE, server.albums.getValue("A").second.map { server.songs.getValue(it) })
        assertEquals(today.map { it.id }, runBlocking { reads.shownIds(WorkOwners.marked("vm:1")) })
        val facts = runBlocking { reads.facts(WorkOwners.marked("vm:1")) }
        assertEquals(today.size, facts.count)
        assertEquals(today.count { it.id.startsWith("find:") }, facts.finds)
    }

    @Test
    fun aListMadeAnewFailsBeforeAnyPage() {
        library()
        server.gone += "L"
        val reads = db.serverReads(server)
        try {
            runBlocking { reads.read(ServerListKind.List, "L", WorkOwners.marked("vm:1")) }
            fail("a list made anew read")
        } catch (e: SubsonicException.NotFound) {
            // as today's decode
        }
        assertEquals(0, db.query("SELECT COUNT(*) FROM server_entry", null).use { it.moveToFirst(); it.getInt(0) })
        assertNull(runBlocking { reads.head(WorkOwners.marked("vm:1")) })
    }

    // A fill copies the page's whole read, with no second request; a clear
    // of the page asked for while it copies waits until the copy ends.
    @Test
    fun aFillCopiesTheNewestWholeRead() = timed(60) {
        library()
        server.lists["L"] = entries(1_200)
        val owners = WorkOwners()
        val reads = db.serverReads(server, owners = owners)
        // The owners as the app mints them, after this launch's mark: the
        // newest whole read is this launch's.
        val page = owners.vm()
        val fill = owners.queue(9)
        runBlocking { reads.read(ServerListKind.List, "L", page) }
        var clearedDuring: Int? = null
        reads.hooks = object : ServerHooks() {
            override suspend fun copied(owner: String, pages: Int) {
                if (pages == 2) {
                    reads.clear(page)
                    clearedDuring = db.query("SELECT COUNT(*) FROM server_entry WHERE owner = ?", arrayOf(page)).use { it.moveToFirst(); it.getInt(0) }
                }
            }
        }
        val before = runBlocking { reads.shownIds(page) }
        val head = runBlocking { reads.fill(ListSpec.Online(OnlineKind.List, "L"), fill) }
        assertTrue(head.complete)
        assertEquals(1, server.calls.count { it == "getPlaylist" })
        assertEquals(before, runBlocking { reads.shownIds(fill) })
        assertEquals(1_200, clearedDuring)
        // The clear asked for meanwhile ran once the copy let the read go.
        assertEquals(0, db.query("SELECT COUNT(*) FROM server_entry WHERE owner = ?", arrayOf(page)).use { it.moveToFirst(); it.getInt(0) })
    }

    // The album's main song over its rows, a page at a time, equals today's
    // over the whole answer: an id match, a title-only match, two top songs
    // on one row.
    @Test
    fun theHighlightIsTodays() = timed(60) {
        library()
        server.albums["A"] = "The Album" to entries(700)
        val reads = db.serverReads(server)
        runBlocking { reads.read(ServerListKind.Album, "A", WorkOwners.marked("vm:1")) }
        val shown = runBlocking { reads.shownIds(WorkOwners.marked("vm:1")) }
        val tracks = runBlocking { db.dao().tracksByIdsUnordered(shown.filterNot { it.startsWith("find:") }) }.associateBy { it.id }
        val rows = shown.mapNotNull { tracks[it] }
        val ranked = listOf(
            RankedSong("nope", "Not here", "Nobody"),
            RankedSong("other", rows[40].title + " (feat. Someone)", rows[40].artist),
            RankedSong(rows[90].id, rows[90].title, rows[90].artist),
            RankedSong(rows[40].id, rows[40].title, rows[40].artist),
        )
        val loader = reads.loader(WorkOwners.marked("vm:1"))
        val paged = runBlocking { highlightWalk(ranked) { after -> loader.after(after, 50) } }
        val whole = runBlocking { loader.after(null, 10_000) }.map { it.row }
        assertEquals(todayHighlight(whole, ranked), paged)
    }

    // The arrival check walks the finds a page at a time and stops at the
    // first one that has arrived.
    @Test
    fun theArrivalCheckStopsAtTheFirstArrived() = timed(60) {
        library()
        server.lists["L"] = entries(1_500)
        val reads = db.serverReads(server)
        runBlocking { reads.read(ServerListKind.List, "L", WorkOwners.marked("vm:1")) }
        val finds = runBlocking { reads.shownIds(WorkOwners.marked("vm:1")) }.filter { it.startsWith("find:") }
        val target = finds[finds.size / 2]
        val seen = ArrayList<String>()
        assertTrue(runBlocking { reads.anyFind(WorkOwners.marked("vm:1")) { id -> seen += id; id == target } })
        assertEquals(finds.take(finds.size / 2 + 1), seen)
        val pages = ArrayList<Int>()
        runBlocking { reads.findPages(WorkOwners.marked("vm:1")) { page -> pages += page.size } }
        assertEquals(finds.size, pages.sum())
        assertTrue(pages.all { it <= 50 })
    }
}

package app.winters.octo.folders

import app.winters.octo.catalog.FOLLOW_SERVER_DIRS_SQL
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.ListsServerDirEntity
import app.winters.octo.catalog.RELINK_SERVER_DIRS_SQL
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.library
import app.winters.octo.catalog.put
import app.winters.octo.data.Drains
import app.winters.octo.device.DEVICE
import app.winters.octo.discovery.ListServer
import app.winters.octo.discovery.SERVER_SOURCE
import app.winters.octo.subsonic.Song
import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.OldSqlite
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files

// A server folder level's page of songs written across saves relinks its
// own rows alone. Its songs resolve, then a save lands (the phone's copies
// join the server's, every song renamed by follow) and a second save
// begins with renames of its own, which clears the first save's; so the
// page's rows go in on ids no rename in force names. The page's two
// relinks then follow each row to the song it is now, and touch no other
// row of the table, though thousands of other levels' rows name songs gone
// too. Both statements seek by the level's owner and places on SQLite 3.21
// and a current one.
@RunWith(RobolectricTestRunner::class)
class ServerDirRelinkTest {
    private val db = openTestCatalog()
    private val songs = 60
    private val server = ListServer((0 until songs).associate { "s$it" to Song("s$it", title = "Song $it", artist = "Drake") })
    private val drains = Files.createTempDirectory("drain").toFile()

    @After
    fun close() {
        server.close()
        db.close()
        drains.deleteRecursively()
    }

    private fun strings(sql: String, vararg args: Any?): List<String> =
        db.query(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    // Other levels' song rows whose songs are gone: the server's copies'
    // ids (which the follow would move) and ids no source has with a song's
    // key (which the relink by key would move).
    private suspend fun otherLevels(): Int {
        val rows = (0 until 3_000).map { i ->
            val n = i % songs
            val (trackId, key) = if (i % 2 == 0) "$SERVER_SOURCE:s$n" to "drake|song $n" else "gone:$i" to "drake|song $n"
            ListsServerDirEntity(WorkOwners.marked("vm:${100 + i % 3}"), i.toLong(), LEVEL_SONG, "x$i", "X $i", null, null, trackId, ByteArray(0), ByteArray(0), key)
        }
        rows.chunked(500).forEach { db.listsDao().insertServerDirRows(it) }
        return rows.size
    }

    private fun others(owner: String): List<String> =
        strings("SELECT owner || ' ' || position || ' ' || COALESCE(trackId, '') FROM lists_server_dir WHERE owner != ? ORDER BY owner, position", owner)

    @Test
    fun aPageWrittenAcrossSavesRelinksItsOwnRowsAlone() = runBlocking {
        withTimeout(120_000) {
            db.put(library(SERVER_SOURCE, songs))
            val merge = db.testMerge(SERVER_SOURCE)
            merge.rebuild()
            server.folders["f"] = emptyList<String>() to (0 until songs).map { "s$it" }
            val owner = WorkOwners.marked("vm:1")
            var saved = false
            var before: List<String> = emptyList()
            var large = 0
            // Where the counting log stood once the saves were in.
            var mark = Long.MAX_VALUE
            val folders = ServerFolders(
                flowOf(ServerName.None), db, Drains(drains), FreeSpace { Long.MAX_VALUE }, WorkOwners(), { server.client() },
                resolve = { page ->
                    // Each song as the library has it now: the server's copy.
                    val found = db.dao().tracksByIdsUnordered(page.map { "$SERVER_SOURCE:${it.id}" }).associateBy { it.id }
                    val shown: List<TrackEntity?> = page.map { found["$SERVER_SOURCE:${it.id}"] }
                    if (!saved) {
                        saved = true
                        // The phone's copies join, so the save renames every
                        // song to the phone's id.
                        db.put(library(DEVICE, songs))
                        merge.rebuild()
                        assertTrue("the first save left renames", strings("SELECT `old` FROM save_rename").isNotEmpty())
                        // The phone's last song changes its id: the next save
                        // has a rename of its own and clears the first's.
                        db.put(library(DEVICE, songs).let { lib -> lib.copy(tracks = lib.tracks.map { if (it.nativeId == "s${songs - 1}") it.copy(id = "$DEVICE:moved", nativeId = "moved") else it }) })
                        merge.rebuild()
                        assertEquals("the renames in force", listOf("$DEVICE:s${songs - 1}"), strings("SELECT `old` FROM save_rename WHERE kind = 'track'"))
                        large = otherLevels()
                        before = others(owner)
                        mark = db.counted.mark()
                    }
                    shown
                },
                page = 100,
            )
            db.counted.reset()
            db.counted.recording = true
            val level = try {
                folders.read("f", owner)
            } finally {
                db.counted.recording = false
            }
            assertTrue("the level was read", level != null && level.songs == songs)
            assertEquals("other levels' rows", large, before.size)
            // The relinks touched the page's rows and nothing else.
            val relinks = db.counted.statements.filter { it.seq > mark && "UPDATE lists_server_dir SET trackId" in it.sql }
            assertEquals("the page's two relinks ran: ${relinks.map { it.sql.trim().take(60) }}", 2, relinks.size)
            assertEquals("rows the relinks touched", songs, relinks.sumOf { it.rowsChanged })
            assertEquals("other levels' rows moved", before, others(owner))
            // Each of the page's rows is on the song it is now.
            val expected = (0 until songs).map { if (it == songs - 1) "$DEVICE:moved" else "$DEVICE:s$it" }
            assertEquals(expected, strings("SELECT trackId FROM lists_server_dir WHERE owner = ? AND kind = $LEVEL_SONG ORDER BY position", owner))
            assertTrue("a row names no library song", expected.all { strings("SELECT id FROM track WHERE id = ?", it).size == 1 })
        }
    }

    // Each relink seeks the table by the level's owner and places, on SQLite
    // 3.21 and a current one: no scan of the table, no sort, no temporary
    // index.
    @Test
    fun bothRelinksSeekByOwnerAndPlace() {
        val problems = ArrayList<String>()
        for ((name, sql) in listOf("follow" to FOLLOW_SERVER_DIRS_SQL, "relink" to RELINK_SERVER_DIRS_SQL)) {
            val text = sql.replace(Regex(""":\w+"""), "?")
            val args = listOf<Any?>("o", 0L, 100L)
            val plans = listOf(OldSqlite.open().use { conn -> OldSqlite.plan(conn, text, args) }, CurrentSqlite.plan(text, args))
            for (lines in plans) {
                assertTrue("$name: no plan", lines.isNotEmpty())
                val table = lines.filter { "lists_server_dir" in it }
                if (table.none { it.trimStart().startsWith("SEARCH") && ("owner=?" in it || "owner = ?" in it) && "position" in it }) problems += "$name: not by owner and place: $lines"
                for (line in lines) {
                    val scan = line.trimStart().startsWith("SCAN") && ("lists_server_dir" in line || ("track" in line && "USING" !in line))
                    if (scan || "TEMP B-TREE" in line || "AUTOMATIC" in line || "LIST SUBQUERY" in line) problems += "$name: $line"
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}

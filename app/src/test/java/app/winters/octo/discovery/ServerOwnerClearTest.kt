package app.winters.octo.discovery

import app.winters.octo.catalog.SERVER_TABLES
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.deleteOwned
import app.winters.octo.listening.timed
import app.winters.octo.subsonic.Song
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.paging.LIST_TABLES
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Owners are tokens: one clear of a queue's owner through the lists' work
// tables (as a build lets its copy go) removes its server rows of every
// table and its heading, a read cut off halfway included, and leaves every
// other owner's.
@RunWith(RobolectricTestRunner::class)
class ServerOwnerClearTest {
    private val db = openTestCatalog()
    private val server = ListServer((0 until 400).associate { "s$it" to Song("s$it", title = "Song $it", artist = "A") })

    @After
    fun close() {
        server.close()
        db.close()
    }

    private fun count(table: String, owner: String) = db.query("SELECT COUNT(*) FROM $table WHERE owner = ?", arrayOf(owner)).use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun oneClearOfATokenReachesEveryServerTable() = timed(30) {
        db.putLibrary((0 until 400 step 5).map { libraryTrack("t$it", "Song $it", "A") }) { null }
        server.lists["L"] = (0 until 400).map { "s$it" }
        val reads = db.serverReads(server)
        runBlocking { reads.read(ServerListKind.List, "L", WorkOwners.marked("vm:1")) }
        // A read stopped while it resolves keeps its work rows until cleared.
        reads.hooks = object : ServerHooks() {
            override suspend fun resolved(owner: String, pages: Int) {
                if (pages == 2) throw IllegalStateException("stopped")
            }
        }
        runCatching { runBlocking { reads.read(ServerListKind.List, "L", WorkOwners.marked("queue:5")) } }
        // The failed read cleared itself; a copy left by a death halfway is
        // made here by hand.
        val queue = arrayOf<Any?>(WorkOwners.marked("queue:5"))
        db.openHelper.writableDatabase.execSQL("INSERT INTO server_key (owner, `key`) VALUES (?, 'song')", queue)
        db.openHelper.writableDatabase.execSQL("INSERT INTO server_match (owner, title, artist, lengthMs, relinkKey) VALUES (?, 'x', 'y', 0, '')", queue)
        db.openHelper.writableDatabase.execSQL("INSERT INTO server_lookup (owner, lookupKey, groupId) VALUES (?, 'x', 1)", queue)
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO server_head (owner, kind, listId, title, readSeq, swapCount) VALUES (?, 'List', 'L', '', 1, 0)",
            queue,
        )
        assertTrue(SERVER_TABLES.all { it in LIST_TABLES })
        runBlocking { for (table in LIST_TABLES) db.deleteOwned(table, WorkOwners.marked("queue:5")) }
        SERVER_TABLES.forEach { assertEquals(it, 0, count(it, WorkOwners.marked("queue:5"))) }
        assertEquals(400, count("server_entry", WorkOwners.marked("vm:1")))
        assertEquals(1, count("server_head", WorkOwners.marked("vm:1")))
    }
}

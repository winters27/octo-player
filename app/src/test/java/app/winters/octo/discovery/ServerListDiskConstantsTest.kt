package app.winters.octo.discovery

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.LaunchMark
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.folders.LEVEL_PAGE
import app.winters.octo.subsonic.Song
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

// An entry of a server list, and a song's row of a server folder's level,
// take no more than SERVER_BYTES_PER_ENTRY says: their rows with their
// indexes, measured on whole reads of songs that are all finds, under an
// owner as the app writes it (the launch's mark first, as long as a mark
// can be, so every run measures the most an entry takes), as the file's
// pages once the log is checkpointed in. The room asked for a drained answer
// (drainedEntries) covers the rows its read then writes, for songs with
// short names and with long ones.
@RunWith(RobolectricTestRunner::class)
class ServerListDiskConstantsTest {
    private val entries = 3_000
    private val long = " (Live at the Royal Albert Hall, London, 14 March 1971) [2011 Remastered Deluxe Edition]"

    private fun used(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun count(db: CatalogDatabase, table: String, owner: String): Int =
        db.query("SELECT COUNT(*) FROM $table WHERE owner = ?", arrayOf(owner)).use { it.moveToFirst(); it.getInt(0) }

    // The server's songs, as a list and as a folder's own songs; with
    // `suffix`, every title and album carries it.
    private fun server(suffix: String): ListServer {
        val ids = (0 until entries).map { "%032x".format(it.toLong() * 7_919 + 12_345) }
        return ListServer(
            ids.withIndex().associate { (i, id) ->
                id to Song(
                    id, title = "Song $i$suffix", album = "Greatest Hits$suffix", albumId = "al${i / 12}",
                    artist = "Artist $i & The Royal Philharmonic Orchestra and Chorus", artistId = "ar${i / 40}", track = i % 12 + 1,
                    year = 1971, duration = 200 + i % 100, coverArt = "al-${i / 12}", suffix = "flac", contentType = "audio/flac",
                    bitRate = 1_411, size = 40_000_000L + i,
                )
            },
        ).apply {
            lists["L"] = ids
            folders["F"] = emptyList<String>() to ids
        }
    }

    // The bytes of the answer as the app drains it, the disk the rows of its
    // whole read took, and the most a list's read left in the log, emptied
    // first (each page of the read).
    private class Measure(val answer: Long, val rows: Long, val log: Long)

    // A whole read of the list, or of the folder's level, into a database of
    // its own.
    private suspend fun measure(suffix: String, folder: Boolean): Measure {
        // A launch's mark is random, and every owner carries it, so a mark
        // of fewer digits measures less: 19 digits, the most it has.
        while (LaunchMark.current < 1_000_000_000_000_000_000L) LaunchMark.next()
        val server = server(suffix)
        val dir = Files.createTempDirectory("catalog").toFile()
        val drains = Files.createTempDirectory("drain").toFile()
        val db = openTestCatalog(dir)
        try {
            val file = File(drains, "answer.json")
            val client = server.client()
            if (folder) client.drainFolder("F", file) else client.playlistInto("L", file) { }
            val answer = file.length()
            file.delete()
            db.query("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            val wal = File(db.openHelper.writableDatabase.path + "-wal")
            var log = 0L
            val before = used(dir)
            if (folder) {
                val owner = WorkOwners.marked("vm:2")
                db.serverFolders(server, page = LEVEL_PAGE, drains = drains).read("F", owner)
                assertEquals(entries, count(db, "lists_server_dir", owner))
            } else {
                val owner = WorkOwners.marked("vm:1")
                val reads = db.serverReads(server, page = SERVER_PAGE, drains = drains)
                reads.hooks = object : ServerHooks() {
                    override suspend fun filled(owner: String, pages: Int) {
                        log = maxOf(log, wal.length())
                    }
                }
                reads.read(ServerListKind.List, "L", owner)
                assertEquals(entries, count(db, "server_entry", owner))
            }
            log = maxOf(log, wal.length())
            db.query("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            return Measure(answer, used(dir) - before, log)
        } finally {
            db.close()
            server.close()
            dir.deleteRecursively()
            drains.deleteRecursively()
        }
    }

    @Test
    fun anEntryTakesWhatItsConstantSays() = runBlocking {
        withTimeout(60_000) {
            val m = measure(long, folder = false)
            val perEntry = m.rows / entries
            println("MEASURED server list entries=$entries answerPerEntry=${m.answer / entries} perEntry=$perEntry constant=$SERVER_BYTES_PER_ENTRY")
            assertTrue("measured $perEntry, constant $SERVER_BYTES_PER_ENTRY", perEntry <= SERVER_BYTES_PER_ENTRY)
            assertTrue("measured $perEntry, constant $SERVER_BYTES_PER_ENTRY", SERVER_BYTES_PER_ENTRY <= perEntry * 11 / 10 + 10)
        }
    }

    // A list's read of songs with long names, all finds, asks for the room
    // it takes, a little over and never under: its rows, and the most the
    // log held while it wrote them.
    @Test
    fun aReadAsksForTheRoomItTakes() = runBlocking {
        withTimeout(60_000) {
            val m = measure(long, folder = false)
            val asked = serverRoom(entries.toLong(), SERVER_PAGE)
            val taken = m.rows + m.log
            println("MEASURED server list read of $entries entries: asked $asked, took $taken (the log's most ${m.log})")
            assertTrue("asked $asked, took $taken", asked >= taken)
            assertTrue("asked $asked, took $taken", asked <= taken * 135 / 100)
        }
    }

    // A folder level asks SERVER_BYTES_PER_ENTRY a row too.
    @Test
    fun aFolderLevelsRowTakesNoMoreThanAnEntrysAsk() = runBlocking {
        withTimeout(60_000) {
            val m = measure(long, folder = true)
            val perRow = m.rows / entries
            println("MEASURED server level rows=$entries answerPerRow=${m.answer / entries} perRow=$perRow constant=$SERVER_BYTES_PER_ENTRY")
            assertTrue("measured $perRow, constant $SERVER_BYTES_PER_ENTRY", perRow <= SERVER_BYTES_PER_ENTRY)
        }
    }

    // The room asked once an answer is drained, from its bytes alone, holds
    // the rows its read writes: short names and long, a list and a level.
    @Test
    fun theRoomAskedForADrainedAnswerHoldsItsRows() = runBlocking {
        withTimeout(120_000) {
            for (suffix in listOf("", long)) {
                for (folder in listOf(false, true)) {
                    val where = "${if (folder) "level" else "list"}, ${if (suffix.isEmpty()) "short" else "long"} names"
                    val m = measure(suffix, folder)
                    val asked = drainedEntries(m.answer) * SERVER_BYTES_PER_ENTRY
                    println("MEASURED server room $where: answer ${m.answer} (${m.answer / entries} an entry), asked $asked, rows took ${m.rows}")
                    assertTrue("$where: asked $asked for rows that took ${m.rows}", asked >= m.rows)
                }
            }
        }
    }
}

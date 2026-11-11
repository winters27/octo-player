package app.winters.octo.catalog

import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

// A first copy's save asks for no less free space than it takes from its
// merge's build's end, where it asks: the most the file's pages in use grow
// over the save, and the most the write-ahead log holds after it was
// emptied there, each measured once the build is done and after every
// group.
@RunWith(RobolectricTestRunner::class)
class SwapSpaceTest {
    @Test
    fun aFirstCopyAsksForWhatItsSaveTakes() = runBlocking {
        val dir = createTempDirectory("catalog").toFile()
        val db = openTestCatalog(dir)
        try {
            val songs = 3_000
            val (_, server) = MergeRowsGen.sources(41, songs, phone = true)
            db.stage(server, generation = 1)
            val sql = db.openHelper.writableDatabase
            val wal = File(sql.path + "-wal")
            var seen: SaveKind? = null
            var asked = 0L
            var mark = 0L
            var most = 0L
            var log = 0L
            fun sample() {
                most = maxOf(most, used(db))
                log = maxOf(log, wal.length())
            }
            val hooks = object : MergeHooks() {
                override suspend fun built() {
                    sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
                    mark = used(db)
                    most = mark
                    log = wal.length()
                }

                override fun spaceAsked(kind: SaveKind, bytes: Long) {
                    seen = kind
                    asked = bytes
                }

                override suspend fun betweenGroups(turn: Long) = sample()

                override fun applied(turns: Long, rows: Long, millis: Long) = sample()
            }
            val merge = db.testMerge(MergeRowsGen.SERVER, hooks)
            merge.rebuild()
            merge.awaitStage(MergeRowsGen.SERVER, 1)
            val taken = most - mark + log
            println("MEASURED first copy of $songs songs: asked $asked, took $taken (${most - mark} in use, the log's most $log)")
            assertEquals(SaveKind.FirstCopy, seen)
            assertTrue("the save's room was measured", taken > 0)
            assertTrue("asked $asked, took $taken", asked >= taken)
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }

    // The log's checkpoint span the asks take is the connection's own:
    // wal_autocheckpoint pages at its page size.
    @Test
    fun theLogsCheckpointSpanIsTheConnections() {
        val db = openTestCatalog()
        try {
            val sql = db.openHelper.writableDatabase
            fun pragma(name: String) = sql.query("PRAGMA $name").use { it.moveToFirst(); it.getLong(0) }
            assertEquals(LOG_CHECKPOINT_BYTES, pragma("wal_autocheckpoint") * pragma("page_size"))
        } finally {
            db.close()
        }
    }

    // The file's pages in use, as its connection sees them.
    private fun used(db: CatalogDatabase): Long {
        val w = db.openHelper.writableDatabase
        fun pragma(name: String) = w.query("PRAGMA $name").use { it.moveToFirst(); it.getLong(0) }
        return (pragma("page_count") - pragma("freelist_count")) * pragma("page_size")
    }
}

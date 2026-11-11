package app.winters.octo.data

import app.winters.octo.catalog.SourceDao
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.copiesInChunks
import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

// Deleting from disk, the server's ids for a fix and a selection's phone
// files read the copies of any number of songs 900 at a time: 2,000 songs'
// copies come back whole, and a recording SourceDao sees no call over 900
// ids (SQLite 3.21 itself accepts more, so the decorator is the check).
// And no copiesOf in LibraryFiles.kt or PhoneFileActions.kt goes around
// the chunked read. The base passed every id in one call.
@RunWith(RobolectricTestRunner::class)
class DeleteFromDiskChunkTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun copiesAreReadNineHundredSongsAtATime() = timed(60) {
        val sql = db.openHelper.writableDatabase
        sql.beginTransaction()
        try {
            repeat(2_000) { i ->
                sql.execSQL(
                    "INSERT INTO source_track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, albumOrder, " +
                        "relinkKey, genre, mergedId, seq) VALUES (?, 'server:h', ?, 'T', 't', 't', 'A', 'a', 'B', 'b', 1, 0, 0, '', '', ?, ?)",
                    arrayOf<Any>("server:h:$i", "$i", "t$i", i),
                )
            }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
        val calls = ArrayList<Int>()
        val real = db.sourceDao()
        val recording = object : SourceDao by real {
            override suspend fun copiesOf(trackIds: List<String>): List<SourceTrackEntity> {
                calls += trackIds.size
                return real.copiesOf(trackIds)
            }
        }
        val copies = recording.copiesInChunks((0 until 2_000).map { "t$it" } + "t5")
        assertEquals(2_000, copies.size)
        assertTrue("$calls", calls.isNotEmpty() && calls.all { it <= 900 })
        assertEquals(2_000, calls.sum())
    }

    @Test
    fun noCopiesOfCallGoesAroundTheChunks() {
        val root = File(System.getProperty("octo.mainSources")!!)
        for (path in listOf("app/winters/octo/data/LibraryFiles.kt", "app/winters/octo/ui/menu/PhoneFileActions.kt")) {
            val code = File(root, path).readLines().joinToString("\n") { it.substringBefore("//") }
            val calls = Regex("""\bcopiesOf\(""").findAll(code).count()
            assertEquals("$path reads copies outside the chunks", 0, calls)
            assertTrue("$path no longer reads copies", "copiesInChunks(" in code)
        }
    }
}

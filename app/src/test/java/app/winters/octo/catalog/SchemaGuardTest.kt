package app.winters.octo.catalog

import android.app.ActivityManager
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SchemaGuardTest {
    private fun columns(db: CatalogDatabase, table: String): List<String> =
        db.openHelper.writableDatabase.query("PRAGMA table_info(`$table`)").use { c ->
            generateSequence { if (c.moveToNext()) c.getString(1) + " " + c.getString(2) + " " + c.getInt(3) else null }.toList()
        }

    @Test
    fun writeAheadLoggingEvenOnLowMemoryPhones() = runBlocking {
        val am = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(ActivityManager::class.java)
        shadowOf(am).setIsLowRamDevice(true)
        val db = openTestCatalog()
        db.openHelper.writableDatabase.query("PRAGMA journal_mode").use { it.moveToFirst(); assertEquals("wal", it.getString(0)) }
        db.close()
    }

    @Test
    fun stageTablesHaveTheSourceColumns() = runBlocking {
        val db = openTestCatalog()
        assertEquals(columns(db, "source_track"), columns(db, "stage_track"))
        assertEquals(columns(db, "source_album"), columns(db, "stage_album"))
        assertEquals(columns(db, "source_artist"), columns(db, "stage_artist"))
        assertEquals(columns(db, "album"), columns(db, "album_next"))
        assertEquals(columns(db, "artist"), columns(db, "artist_next"))
        // Every library song column is a candidate column too.
        val candidate = columns(db, "merge_candidate").toSet()
        columns(db, "track").forEach { assertTrue("merge_candidate lacks $it", it in candidate) }
        db.close()
    }

    @Test
    fun openEmptiesWorkingRowsAndResetsStages() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("g").toFile()
        openTestCatalog(dir).apply {
            val w = openHelper.writableDatabase
            w.execSQL("INSERT INTO take_in_log VALUES (1, 'track', 'x')")
            w.execSQL("INSERT INTO rating_note VALUES ('x', 3)")
            w.execSQL("INSERT INTO sync_song (songId, albumKey, json) VALUES ('s', 'a', '{}')")
            w.execSQL("INSERT INTO source_meta VALUES ('server:h', 'acct', 'acct', 5, '$STAGE_READY', 7, 1, 1, 1, 0)")
            w.execSQL("INSERT INTO merge_state VALUES (0, 'key', 1)")
            close()
        }
        val db = openTestCatalog(dir)
        val w = db.openHelper.writableDatabase
        WORKING_TABLES.forEach { t -> w.query("SELECT COUNT(*) FROM `$t`").use { it.moveToFirst(); assertEquals(t, 0, it.getInt(0)) } }
        w.query("SELECT stage, generation, stamp FROM source_meta").use {
            it.moveToFirst()
            assertEquals(STAGE_NONE, it.getString(0))
            assertEquals(0L, it.getLong(1))
            // The stamp and the skip key are kept: they are not working rows.
            assertEquals(5L, it.getLong(2))
        }
        w.query("SELECT skipKey, claimActive FROM merge_state").use {
            it.moveToFirst()
            assertEquals("key", it.getString(0))
            assertEquals(0, it.getInt(1))
        }
        db.close()
    }
}

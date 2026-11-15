package app.winters.octo.catalog

import android.database.sqlite.SQLiteDatabase
import app.winters.octo.testing.OldSqlite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

// The upgrade from 19 only adds tables and columns, so the guard asks for a
// megabyte, never three times the library file as the upgrade from 18 does.
@RunWith(RobolectricTestRunner::class)
class UpgradeGuardTablesOnlyTest {
    private fun version19(): File {
        val file = File(Files.createTempDirectory("u20").toFile(), CATALOG_FILE)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            OldSqlite.schemaStatements(19).forEach(db::execSQL)
            db.version = 19
        }
        return file
    }

    @Test
    fun theUpgradeFrom19AsksForAMegabyte() {
        val file = version19()
        val short = UpgradeGuard(file) { 1_000L }
        assertFalse(short.check())
        assertEquals(UpgradeState.NeedsSpace(1L shl 20), short.state.value)
        assertTrue(UpgradeGuard(file) { 1L shl 20 }.check())
    }
}

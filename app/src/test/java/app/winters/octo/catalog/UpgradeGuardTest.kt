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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class UpgradeGuardTest {
    private fun version18(rows: Int): File {
        val file = File(Files.createTempDirectory("u").toFile(), CATALOG_FILE)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            OldSqlite.schemaStatements(18).forEach(db::execSQL)
            repeat(rows) { i ->
                db.execSQL("INSERT INTO track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt) VALUES ('t$i','d','$i','t','t','t','a','a','a','a',1,1)")
            }
            db.version = 18
        }
        return file
    }

    @Test
    fun tooLittleSpaceBlocksTheUpgradeAndSaysHowMuch() {
        val file = version18(rows = 100)
        val guard = UpgradeGuard(file) { 1_000L }
        assertFalse(guard.check())
        assertEquals(UpgradeState.NeedsSpace(100 * UPGRADE_BYTES_PER_ROW * 2), guard.state.value)
        // Nothing was opened for writing beyond the check: still version 18.
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(18, it.version) }
    }

    @Test
    fun anOpenWaitsUntilTryAgainFindsRoom() {
        val file = version18(rows = 100)
        var free = 1_000L
        val guard = UpgradeGuard(file) { free }
        assertFalse(guard.check())
        val opened = CountDownLatch(1)
        thread { guard.awaitClear(); opened.countDown() }
        assertFalse(opened.await(200, TimeUnit.MILLISECONDS))
        free = Long.MAX_VALUE
        assertTrue(guard.retry())
        assertTrue(opened.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun currentOrMissingFilesAreClearWithoutOpening() {
        val missing = File(Files.createTempDirectory("u").toFile(), CATALOG_FILE)
        assertTrue(UpgradeGuard(missing) { 0L }.check())
        val current = File(Files.createTempDirectory("u").toFile(), CATALOG_FILE)
        SQLiteDatabase.openOrCreateDatabase(current, null).use { it.version = CATALOG_VERSION }
        assertEquals(CATALOG_VERSION, headerVersion(current))
        assertTrue(UpgradeGuard(current) { 0L }.check())
    }
}

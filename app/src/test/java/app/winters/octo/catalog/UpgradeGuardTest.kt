package app.winters.octo.catalog

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteFullException
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.winters.octo.testing.OldSqlite
import app.winters.octo.testing.testContext
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
        assertEquals(UpgradeState.NeedsSpace(file.length() * 3), guard.state.value)
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

    // A file the check cannot read as a database asks for room for the
    // most the upgrade could take, and nothing throws.
    @Test
    fun anUnreadableFileAsksForTheMostInsteadOfFailing() {
        val file = File(Files.createTempDirectory("u").toFile(), CATALOG_FILE)
        file.writeBytes(ByteArray(64).also { it[63] = 18 } + ByteArray(4_000) { 7 })
        val size = file.length()
        val guard = UpgradeGuard(file) { 10L }
        assertFalse(guard.check())
        assertEquals(UpgradeState.NeedsSpace(size * 3), guard.state.value)
        // The check left the file as it was.
        assertEquals(size, file.length())
    }

    // A header that cannot be read is no proof of an upgrade: the file stays
    // due and is read again, instead of the guard standing down for good.
    @Test
    fun anUnreadableHeaderStaysDue() {
        val notAFile = File(Files.createTempDirectory("u").toFile(), CATALOG_FILE).apply { mkdirs() }
        val guard = UpgradeGuard(notAFile) { Long.MAX_VALUE }
        assertTrue(guard.upgradeDue())
        assertTrue(guard.upgradeDue())
    }

    // What a full disk showed the upgrade needs outlives a restart, so the
    // next launch asks for it before trying again, and is forgotten once the
    // upgrade lands.
    @Test
    fun theRaisedAskSurvivesARestart() {
        val file = version18(rows = 100)
        val size = file.length()
        var free = size * 3
        assertTrue(UpgradeGuard(file) { free }.ranOutOfSpace(full = true))
        val next = UpgradeGuard(file) { free }
        assertFalse(next.check())
        assertEquals(UpgradeState.NeedsSpace(size * 4), next.state.value)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = CATALOG_VERSION }
        free = 0L
        assertTrue(UpgradeGuard(file) { free }.check())
        assertFalse(File(file.path + "-upgrade-floor").exists())
    }

    // A write error with room for the usual ask counts as a full disk once.
    // Its raised ask lasts for that run only: after a restart the upgrade
    // tries again with the room it had, and a second error is passed on.
    @Test
    fun aWriteErrorWithRoomCountsAsFullOnce() {
        val file = version18(rows = 100)
        val size = file.length()
        var free = size * 3
        val guard = UpgradeGuard(file) { free }
        assertTrue(guard.ranOutOfSpace(full = false))
        assertEquals(UpgradeState.NeedsSpace(size * 4), guard.state.value)
        val next = UpgradeGuard(file) { free }
        assertTrue(next.check())
        assertFalse(next.ranOutOfSpace(full = false))
        // While the space is short it still counts.
        free = 10L
        assertTrue(UpgradeGuard(file) { free }.ranOutOfSpace(full = false))
    }

    // An upgrade that runs out of disk anyway leaves the open waiting, with
    // the most it could take asked for, instead of failing the open.
    @Test
    fun anUpgradeThatFillsTheDiskWaitsForRoom() {
        val file = version18(rows = 100)
        var free = Long.MAX_VALUE
        val guard = UpgradeGuard(file) { free }
        var opens = 0
        val inner = SupportSQLiteOpenHelper.Factory { _ ->
            object : SupportSQLiteOpenHelper {
                override val databaseName: String? = null
                override fun setWriteAheadLoggingEnabled(enabled: Boolean) = Unit
                override val writableDatabase: SupportSQLiteDatabase
                    get() {
                        opens++
                        if (opens == 1) {
                            free = 1_000L
                            throw SQLiteFullException("database or disk is full")
                        }
                        return proxyDatabase()
                    }
                override val readableDatabase: SupportSQLiteDatabase get() = writableDatabase
                override fun close() = Unit
            }
        }
        val helper = GuardedOpenHelperFactory(guard, inner).create(
            SupportSQLiteOpenHelper.Configuration.builder(testContext()).name(file.path)
                .callback(object : SupportSQLiteOpenHelper.Callback(19) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        val opened = CountDownLatch(1)
        thread { helper.writableDatabase; opened.countDown() }
        assertFalse(opened.await(200, TimeUnit.MILLISECONDS))
        assertEquals(UpgradeState.NeedsSpace(file.length() * 3), guard.state.value)
        free = Long.MAX_VALUE
        assertTrue(guard.retry())
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        assertEquals(2, opens)
    }

    // The app's own database, built as the app builds it, waits at its
    // first read while the upgrade has no room, and upgrades once it has.
    @Test
    fun theLibraryWaitsThroughRoomUntilThereIsRoom() {
        val file = version18(rows = 100)
        var free = 1_000L
        val guard = UpgradeGuard(file) { free }
        val db = Room.databaseBuilder(testContext(), CatalogDatabase::class.java, file.path)
            .openHelperFactory(GuardedOpenHelperFactory(guard))
            .catalogSetup()
            .allowMainThreadQueries()
            .build()
        val read = CountDownLatch(1)
        thread { db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM track").close(); read.countDown() }
        assertFalse(read.await(200, TimeUnit.MILLISECONDS))
        free = Long.MAX_VALUE
        assertTrue(guard.retry())
        assertTrue(read.await(10, TimeUnit.SECONDS))
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM track").use { it.moveToFirst(); assertEquals(100, it.getInt(0)) }
        db.close()
    }

    // The real open path (Room, the framework helper) when the upgrade fills
    // the disk partway: SQLite rolls it back, the open waits for room, and
    // once there is room the upgrade runs whole.
    @Test
    fun aFullDiskDuringTheRealUpgradeWaitsThenUpgrades() {
        val file = version18(rows = 100)
        var free = Long.MAX_VALUE
        val guard = UpgradeGuard(file) { free }
        var attempts = 0
        val fillsOnce = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // The framework helper tries an upgrade twice before it
                // gives up, so the disk is full for both.
                if (attempts++ < 2) {
                    free = 1_000L
                    throw SQLiteFullException("database or disk is full")
                }
                MIGRATION_18_19.migrate(db)
            }
        }
        val db = Room.databaseBuilder(testContext(), CatalogDatabase::class.java, file.path)
            .openHelperFactory(GuardedOpenHelperFactory(guard))
            .catalogSetup()
            .addMigrations(fillsOnce)
            .allowMainThreadQueries()
            .build()
        val read = CountDownLatch(1)
        val reader = thread { db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM track").close(); read.countDown() }
        // The open reaches the guard on its own thread: wait (at most 10 s) until the guard holds
        // it, parked in awaitClear with the disk full, not for a fixed time. Held, the read has
        // not happened.
        fun heldByTheGuard() = guard.state.value is UpgradeState.NeedsSpace &&
            reader.stackTrace.any { it.className == UpgradeGuard::class.java.name && it.methodName == "awaitClear" }
        val heldBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!heldByTheGuard() && System.nanoTime() < heldBy) Thread.sleep(10)
        assertTrue("the open waits in the guard while the disk is full", heldByTheGuard())
        assertEquals(1L, read.count)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(18, it.version) }
        free = Long.MAX_VALUE
        assertTrue(guard.retry())
        assertTrue(read.await(10, TimeUnit.SECONDS))
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM track").use { it.moveToFirst(); assertEquals(100, it.getInt(0)) }
        db.close()
    }
}

// A database for an open that only has to succeed: any call on it fails.
private fun proxyDatabase(): SupportSQLiteDatabase =
    java.lang.reflect.Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java),
    ) { _, method, _ -> throw UnsupportedOperationException(method.name) } as SupportSQLiteDatabase

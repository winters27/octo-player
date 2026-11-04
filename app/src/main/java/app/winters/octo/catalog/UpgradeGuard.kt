package app.winters.octo.catalog

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

// The most disk the 18 to 19 upgrade takes per song row (source_track or
// track), new tables and log together, measured on generated libraries by
// MigrationSpaceTest. UpgradeGuard asks for half as much again on top.
const val UPGRADE_BYTES_PER_ROW = 670L

// Whether the library can open now.
sealed interface UpgradeState {
    data object Checking : UpgradeState
    data object Clear : UpgradeState
    // An upgrade is due and needs this much free space, which the phone lacks.
    data class NeedsSpace(val bytes: Long) : UpgradeState
}

// Keeps the library from opening while the upgrade it needs would run out
// of disk partway: the upgrade copies the song tables, so it needs room for
// a second copy of them. Until there is room, every open of the database
// waits (GuardedOpenHelperFactory) and the app says how much to free.
@Singleton
class UpgradeGuard internal constructor(
    private val file: File,
    private val freeSpace: FreeSpace,
) {
    @Inject constructor(@ApplicationContext context: Context, freeSpace: FreeSpace) :
        this(context.getDatabasePath(CATALOG_FILE), freeSpace)

    private val lock = Object()
    private val _state = MutableStateFlow<UpgradeState>(UpgradeState.Checking)
    val state: StateFlow<UpgradeState> = _state

    // Checks once, on the thread that asks first, and answers whether the
    // library can open. Quick on every launch but an upgrading one: the
    // version is read from the file's header.
    fun check(): Boolean = synchronized(lock) {
        if (_state.value == UpgradeState.Checking) _state.value = measure()
        _state.value == UpgradeState.Clear
    }

    // Checks again, after the listener freed space. Answers whether the
    // library can open now; waiting opens go ahead when it can.
    fun retry(): Boolean = synchronized(lock) {
        _state.value = measure()
        lock.notifyAll()
        _state.value == UpgradeState.Clear
    }

    // Returns once the library can open.
    fun awaitClear() = synchronized(lock) {
        if (_state.value == UpgradeState.Checking) _state.value = measure()
        while (_state.value != UpgradeState.Clear) lock.wait()
    }

    // Whether the file still waits for its upgrade.
    fun upgradeDue(): Boolean = try {
        file.exists() && headerVersion(file) < CATALOG_VERSION
    } catch (e: IOException) {
        false
    }

    // The upgrade ran out of disk after all: nothing opens until there is
    // room for the most it could take, the whole file over twice.
    fun ranOutOfSpace() = synchronized(lock) {
        floor = wholeFileNeed()
        _state.value = UpgradeState.NeedsSpace(floor)
    }

    // At least what an upgrade that ran out of disk showed it needs.
    private var floor = 0L

    private fun measure(): UpgradeState {
        if (!upgradeDue()) return UpgradeState.Clear
        val needed = try {
            // A file SQLite finds damaged is left as it is (Android's default
            // handler would delete it); the open that follows decides.
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, DatabaseErrorHandler { }).use { db ->
                // The header can lag the file by what the log holds; the
                // version the database itself reports is the real one.
                if (db.version >= CATALOG_VERSION) return UpgradeState.Clear
                songRows(db) * UPGRADE_BYTES_PER_ROW * 3 / 2
            }
        } catch (e: SQLiteException) {
            // The file could not be read as it is: ask for room for the most
            // the upgrade could take.
            wholeFileNeed()
        }.coerceAtLeast(floor)
        return if (needed <= freeSpace.bytes()) UpgradeState.Clear else UpgradeState.NeedsSpace(needed)
    }

    private fun wholeFileNeed(): Long = file.length() * 2
}

// The rows the upgrade copies. A table an old version never made counts nothing.
private fun songRows(db: SQLiteDatabase): Long = listOf("source_track", "track").sumOf { table ->
    val exists = DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)) > 0
    if (exists) DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM `$table`", null) else 0L
}

// The schema version SQLite keeps at byte 60 of the file, or 0 when the
// file is too short to have one.
internal fun headerVersion(file: File): Int = RandomAccessFile(file, "r").use { f ->
    if (f.length() < 64) return 0
    f.seek(60)
    f.readInt()
}

// Opens the library through UpgradeGuard: every open waits until the guard
// is clear, so no open can start an upgrade the disk has no room for. An
// upgrade that runs out of disk anyway is rolled back by SQLite, and the
// open waits again, for room for the most it could take.
class GuardedOpenHelperFactory(
    private val guard: UpgradeGuard,
    private val inner: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val helper = inner.create(configuration)
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase: SupportSQLiteDatabase
                get() = opened { helper.writableDatabase }
            override val readableDatabase: SupportSQLiteDatabase
                get() = opened { helper.readableDatabase }
        }
    }

    private fun opened(open: () -> SupportSQLiteDatabase): SupportSQLiteDatabase {
        while (true) {
            guard.awaitClear()
            val due = guard.upgradeDue()
            try {
                return open()
            } catch (e: SQLiteFullException) {
                if (!due) throw e
            } catch (e: SQLiteDiskIOException) {
                if (!due) throw e
            }
            guard.ranOutOfSpace()
        }
    }
}

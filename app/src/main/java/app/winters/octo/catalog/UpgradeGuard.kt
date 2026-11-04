package app.winters.octo.catalog

import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

// Bytes a song row (source_track or track) takes once the 18 to 19
// upgrade rebuilt its table with the new indexes, measured on generated
// libraries by MigrationSpaceTest. The upgrade needs that much again for
// the copy, and the log about as much: UpgradeGuard asks for twice it.
const val UPGRADE_BYTES_PER_ROW = 490L

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

    private fun measure(): UpgradeState {
        if (!file.exists() || headerVersion(file) >= CATALOG_VERSION) return UpgradeState.Clear
        val needed = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use { db ->
            // The header can lag the file by what the log holds; the version
            // the database itself reports is the real one.
            if (db.version >= CATALOG_VERSION) return UpgradeState.Clear
            songRows(db) * UPGRADE_BYTES_PER_ROW * 2
        }
        return if (needed <= freeSpace.bytes()) UpgradeState.Clear else UpgradeState.NeedsSpace(needed)
    }
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
// is clear, so no open can start an upgrade the disk has no room for.
class GuardedOpenHelperFactory(
    private val guard: UpgradeGuard,
    private val inner: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val helper = inner.create(configuration)
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase: SupportSQLiteDatabase
                get() {
                    guard.awaitClear()
                    return helper.writableDatabase
                }
            override val readableDatabase: SupportSQLiteDatabase
                get() {
                    guard.awaitClear()
                    return helper.readableDatabase
                }
        }
    }
}

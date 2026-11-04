package app.winters.octo.catalog

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.util.Log
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

// Whether the library can open now.
sealed interface UpgradeState {
    data object Checking : UpgradeState
    data object Clear : UpgradeState
    // An upgrade is due and needs this much free space, which the phone lacks.
    data class NeedsSpace(val bytes: Long) : UpgradeState
}

// Keeps the library from opening while the upgrade it needs would run out
// of disk partway. The 18 to 19 upgrade copies the song tables before it
// drops the old ones, all in one transaction: at its most it takes about
// 1.6 times the database file on top of it (MigrationSpaceTest), so it asks
// for twice the file and its log. Until there is room, every open of the
// database waits (GuardedOpenHelperFactory) and the app says how much to free.
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

    // Set once the file is known to be upgraded (or absent): it never goes
    // back, so no open reads the file's header again.
    @Volatile private var upgraded = false

    // At least what an upgrade that filled the disk showed it needs, and
    // whether a write error was already taken for a full disk. Kept in a file
    // beside the database until the upgrade lands, so a restart does not run
    // an upgrade already known not to fit.
    private val kept = File(file.path + "-upgrade-floor")
    private var floor = 0L
    private var writeErrorCounted = false

    init {
        val saved = try {
            if (kept.exists()) kept.readText().split(' ') else emptyList()
        } catch (e: IOException) {
            emptyList()
        }
        floor = saved.firstOrNull()?.toLongOrNull() ?: 0L
        writeErrorCounted = saved.getOrNull(1) == WRITE_ERROR
    }

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

    // Whether the file still waits for its upgrade. The header can lag a
    // committed upgrade still in the log, so a header that says it is due
    // is checked against the version the database itself reports.
    // A file whose header cannot be read counts as due, and is read again
    // next time.
    fun upgradeDue(): Boolean {
        if (upgraded) return false
        val due = try {
            file.exists() && headerVersion(file) < CATALOG_VERSION && reportedVersion() < CATALOG_VERSION
        } catch (e: IOException) {
            return true
        }
        if (!due) {
            upgraded = true
            kept.delete()
        }
        return due
    }

    // The upgrade failed writing: nothing opens until there is room. A full
    // disk always counts, and from then on asks for more than was free when
    // it filled. A write error counts while the space is short; with room
    // for the usual ask it counts as a full disk once (a disk that fills can
    // be reported so), and after that (answering false) is the caller's to
    // pass on.
    fun ranOutOfSpace(full: Boolean): Boolean = synchronized(lock) {
        val raise = when {
            full -> true
            freeSpace.bytes() < wholeFileNeed() -> false
            writeErrorCounted -> return false
            else -> true.also { writeErrorCounted = true }
        }
        if (raise) {
            floor = maxOf(floor, wholeFileNeed(), freeSpace.bytes() + file.length())
            keep()
        }
        _state.value = UpgradeState.NeedsSpace(maxOf(floor, wholeFileNeed()))
        true
    }

    // A file that cannot be written leaves the floor for this run only.
    private fun keep() {
        try {
            kept.writeText(if (writeErrorCounted) "$floor $WRITE_ERROR" else "$floor")
        } catch (e: IOException) {
            Log.w("Octo", "upgrade floor not kept: ${e.javaClass.simpleName}")
        }
    }

    private fun measure(): UpgradeState {
        if (!upgradeDue()) return UpgradeState.Clear
        val needed = maxOf(floor, wholeFileNeed())
        return if (needed <= freeSpace.bytes()) UpgradeState.Clear else UpgradeState.NeedsSpace(needed)
    }

    // The version the database reports, read without changing the file: a
    // file SQLite finds damaged is left as it is (Android's default handler
    // would delete it) and counts as not yet upgraded, so the open that
    // follows decides.
    private fun reportedVersion(): Int = try {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, DatabaseErrorHandler { }).use { it.version }
    } catch (e: SQLiteException) {
        0
    }

    private fun wholeFileNeed(): Long = (file.length() + File(file.path + "-wal").length()) * 2
}

private const val WRITE_ERROR = "write-error"

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
// open waits again for room. While the upgrade is due, one open runs at a
// time, so two never fill the disk side by side.
class GuardedOpenHelperFactory(
    private val guard: UpgradeGuard,
    private val inner: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
) : SupportSQLiteOpenHelper.Factory {
    private val upgrading = Any()

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
        guard.awaitClear()
        if (!guard.upgradeDue()) return open()
        synchronized(upgrading) {
            while (true) {
                guard.awaitClear()
                if (!guard.upgradeDue()) return open()
                try {
                    return open()
                } catch (e: SQLiteFullException) {
                    guard.ranOutOfSpace(full = true)
                } catch (e: SQLiteDiskIOException) {
                    if (!guard.ranOutOfSpace(full = false)) throw e
                }
            }
        }
    }
}

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
// of disk partway. The 18 to 19 upgrade copies the song tables and the
// tables of likes, ratings, downloads, plays and finds into new ones with
// more indexes before it drops the old ones, all in one transaction: at its
// most it takes about 2.3 times the database file on top of it
// (MigrationSpaceTest; a file mostly of plays and finds takes about 1.4
// times, MigrationTest), so it asks for three times the file and its log.
// Three things can grow beyond that share and are asked for on top,
// counted from the file: a family playlist's base, one line of ids that
// becomes a row per song, and
// the saved queue's and the playlists' rows, copied into bigger ones. Until
// there is room, every open of the database waits (GuardedOpenHelperFactory)
// and the app says how much to free.
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

    // What a write error taken for a full disk asks for, for this run only:
    // after a restart the upgrade tries again, and a second error is passed on.
    private var writeErrorAsk = 0L

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
        val usual = upgradeNeed()
        val raised = maxOf(usual, freeSpace.bytes() + file.length())
        when {
            full -> floor = maxOf(floor, raised)
            freeSpace.bytes() < usual -> Unit
            writeErrorCounted -> return false
            else -> {
                writeErrorCounted = true
                writeErrorAsk = raised
            }
        }
        keep()
        _state.value = UpgradeState.NeedsSpace(need())
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
        val needed = need()
        return if (needed <= freeSpace.bytes()) UpgradeState.Clear else UpgradeState.NeedsSpace(needed)
    }

    private fun need(): Long = maxOf(floor, writeErrorAsk, upgradeNeed())

    // The most the upgrade takes. From version 19 on it only adds tables and
    // columns (Migration19To20.kt), so a little room does; from 18 it is a
    // share of the whole file and the rows it writes that the file holds in
    // a smaller form. A file whose version can't be read asks for the most.
    private fun upgradeNeed(): Long =
        if (reportedVersion() >= TABLES_ONLY_FROM) TABLES_ONLY_BYTES else wholeFileNeed() + baseRowsNeed() + queueRowsNeed() + playlistRowsNeed()

    // The version the database reports, read without changing the file: a
    // file SQLite finds damaged is left as it is (Android's default handler
    // would delete it) and counts as not yet upgraded, so the open that
    // follows decides.
    private fun reportedVersion(): Int = try {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, DatabaseErrorHandler { }).use { it.version }
    } catch (e: SQLiteException) {
        0
    }

    private fun wholeFileNeed(): Long = (file.length() + File(file.path + "-wal").length()) * UPGRADE_FILE_FACTOR

    // Each family playlist's base becomes a row per id, and the line it was
    // stays in the log until the upgrade lands: twice the rows, as if every
    // id were different, and the characters at two bytes each.
    private fun baseRowsNeed(): Long = readFile(
        "SELECT TOTAL(length(syncedSongs)), TOTAL(length(syncedSongs) - length(replace(syncedSongs, char(10), '')) + 1) " +
            "FROM playlist WHERE typeof(syncedSongs) = 'text'",
    ) { chars, ids -> 2 * (ids * SYNCED_ROW_BYTES + chars * 2) }

    // The saved queue's songs, each copied into a row with its keys and
    // three index entries.
    private fun queueRowsNeed(): Long = readFile("SELECT COUNT(*), 0 FROM queue_item") { songs, _ -> songs * QUEUE_ROW_BYTES }

    // Every playlist row, copied into one with four index entries while the
    // old row is still there.
    private fun playlistRowsNeed(): Long = readFile("SELECT COUNT(*), 0 FROM playlist_item") { rows, _ -> rows * PLAYLIST_ROW_BYTES }

    // One row of two numbers from the file, read without changing it, the
    // way reportedVersion opens it. Nothing when it cannot be read.
    private fun readFile(sql: String, need: (Long, Long) -> Long): Long = try {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, DatabaseErrorHandler { }).use { db ->
            db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) need(c.getLong(0), c.getLong(1)) else 0L }
        }
    } catch (e: SQLiteException) {
        0L
    }
}

// How many times the database file the upgrade asks for on top of the
// file, and what a base row, a queue row and a playlist row take with
// their index entries and the log (MigrationSpaceTest measured about 100,
// 160 and 270 bytes).
private const val UPGRADE_FILE_FACTOR = 3L
private const val SYNCED_ROW_BYTES = 100L
private const val QUEUE_ROW_BYTES = 200L
internal const val PLAYLIST_ROW_BYTES = 350L

private const val WRITE_ERROR = "write-error"

// The first version whose upgrade only adds tables and columns, and the
// room it asks for (a few pages each, and their log).
private const val TABLES_ONLY_FROM = 19
private const val TABLES_ONLY_BYTES = 1L shl 20

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

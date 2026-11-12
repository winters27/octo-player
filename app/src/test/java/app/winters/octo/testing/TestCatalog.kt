package app.winters.octo.testing

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.catalog.AddedRows
import app.winters.octo.catalog.CATALOG_FILE
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.PlaylistEdits
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.SongToAdd
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.catalogSetup
import java.io.File
import java.nio.file.Files

// A library database in a file of its own, opened the way the app opens
// one (write-ahead logging, the migration, the open cleanup). Robolectric
// runs tests on the main thread, where Room refuses reads that block, so
// tests may read directly. Every statement is counted at the database
// (`counted`, CountingOpenHelperFactory).
fun openTestCatalog(dir: File = Files.createTempDirectory("catalog").toFile()): CatalogDatabase =
    Room.databaseBuilder(testContext(), CatalogDatabase::class.java, File(dir, CATALOG_FILE).path)
        .catalogSetup()
        .openHelperFactory(CountingOpenHelperFactory())
        .allowMainThreadQueries()
        .build()

// A playlist's one-row edits, as the app makes them.
fun CatalogDatabase.edits(): PlaylistEdits = PlaylistEdits(this, PlaylistSets(this))

// Appends songs to a playlist as the app's add does.
suspend fun CatalogDatabase.addSongs(playlistId: String, tracks: List<TrackEntity>, now: Long): AddedRows? =
    edits().add(playlistId, { emit -> emit(tracks.map { SongToAdd(it.id, it.relinkKey) }) }, distinct = false, newOnly = false, now = now)

// The test app's context, taking an absolute path as a database's name as
// it is. Android reads only a leading "/" as one, so it would refuse a
// Windows path like C:\Users\...\catalog.db.
fun testContext(): Context = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
    override fun getDatabasePath(name: String): File = File(name).takeIf { it.isAbsolute } ?: super.getDatabasePath(name)

    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        openOrCreateDatabase(name, mode, factory, null)

    // The framework's open helper comes here instead of getDatabasePath.
    // An absolute name opens as it is, with the flags Android derives from
    // the mode; any other goes to the app's database folder as usual.
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase {
        if (!File(name).isAbsolute) return super.openOrCreateDatabase(name, mode, factory, errorHandler)
        var flags = SQLiteDatabase.CREATE_IF_NECESSARY
        if (mode and Context.MODE_ENABLE_WRITE_AHEAD_LOGGING != 0) flags = flags or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
        if (mode and Context.MODE_NO_LOCALIZED_COLLATORS != 0) flags = flags or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        return SQLiteDatabase.openDatabase(name, factory, flags, errorHandler)
    }
}

// A second connection to the same file, which gives up at once instead of
// waiting when the database is busy: a write through it fails while any
// other connection holds the write lock.
fun rawConnection(db: CatalogDatabase): SQLiteDatabase {
    val path = db.openHelper.writableDatabase.path!!
    return SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).apply {
        rawQuery("PRAGMA busy_timeout = 0", null).use { it.moveToFirst() }
    }
}

// The schema files Room wrote, by version.
fun schemaFile(version: Int): File = File(System.getProperty("octo.schemas")!!, "$version.json")

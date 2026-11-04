package app.winters.octo.catalog

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.winters.octo.testing.LibraryGen
import app.winters.octo.testing.GenShape
import app.winters.octo.testing.OldSqlite
import app.winters.octo.testing.testContext
import app.winters.octo.server.buildServerCatalog
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class MigrationSpaceTest {
    // Bytes a song row takes in the rebuilt tables, on a generated library.
    @Test
    fun constantMatchesWhatTheUpgradeWrites() {
        val file = File(Files.createTempDirectory("s").toFile(), CATALOG_FILE)
        val rows = buildServerCatalog("server:h", LibraryGen.server(7, GenShape(songs = 5_000, albums = 500, artists = 200)))
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            OldSqlite.schemaStatements(18).forEach(db::execSQL)
            db.beginTransaction()
            for (t in rows.tracks) {
                val v = android.content.ContentValues().apply {
                    put("id", t.id); put("sourceId", t.sourceId); put("nativeId", t.nativeId); put("title", t.title)
                    put("searchKey", t.searchKey); put("sortKey", t.sortKey); put("artist", t.artist); put("artistId", t.artistId)
                    put("album", t.album); put("albumId", t.albumId); put("trackNo", t.trackNo); put("durationMs", t.durationMs)
                    put("addedAt", t.addedAt); put("mimeType", t.mimeType); put("sizeBytes", t.sizeBytes); put("artwork", t.artwork)
                    put("albumOrder", t.albumOrder); put("relinkKey", t.relinkKey); put("genre", t.genre); put("genres", t.genres)
                    put("artists", t.artists); put("mergedId", t.id)
                }
                db.insertOrThrow("source_track", null, v)
                v.remove("mergedId"); v.remove("genres"); v.remove("artists")
                db.insertOrThrow("track", null, v)
            }
            db.setTransactionSuccessful(); db.endTransaction()
            db.version = 18
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(testContext())
                .name(file.path)
                .callback(object : SupportSQLiteOpenHelper.Callback(19) {
                    override fun onConfigure(db: SupportSQLiteDatabase) {
                        db.enableWriteAheadLogging()
                        db.query("PRAGMA wal_autocheckpoint = 0").close()
                    }
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = MIGRATION_18_19.migrate(db)
                })
                .build(),
        )
        val db = helper.writableDatabase
        // What the two rebuilt song tables take on disk, indexes and all:
        // the upgrade holds a second copy of them until it drops the old
        // ones, and the log holds about as much again (the guard doubles it).
        fun bytes(): Long {
            db.execSQL("VACUUM")
            val pages = db.query("PRAGMA page_count").use { it.moveToFirst(); it.getLong(0) }
            val size = db.query("PRAGMA page_size").use { it.moveToFirst(); it.getLong(0) }
            return pages * size
        }
        val withRows = bytes()
        db.execSQL("DELETE FROM source_track")
        db.execSQL("DELETE FROM track")
        val withoutRows = bytes()
        helper.close()
        val perRow = (withRows - withoutRows) / (rows.tracks.size * 2)
        println("MEASURED bytesPerRow=$perRow")
        assertTrue("UPGRADE_BYTES_PER_ROW $UPGRADE_BYTES_PER_ROW below measured $perRow", UPGRADE_BYTES_PER_ROW >= perRow)
        assertTrue("UPGRADE_BYTES_PER_ROW $UPGRADE_BYTES_PER_ROW over 10% above measured $perRow", UPGRADE_BYTES_PER_ROW <= perRow * 11 / 10)
    }
}

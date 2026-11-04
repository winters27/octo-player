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
    // The most disk the upgrade takes is within what UpgradeGuard asks for,
    // twice the file, for short and long ids alike (a server's ids carry
    // its whole address), and for long titles, which the new indexes copy.
    @Test
    fun theUpgradeTakesLessThanTwiceTheFile() {
        val shapes = listOf("server:h" to false, "server:music.home-server.example.net:4533" to false, "server:h" to true)
        for ((source, longTitles) in shapes) {
            val (before, peak) = upgradePeak(source, longTitles)
            println("MEASURED $source longTitles=$longTitles file=$before peak=$peak ratio=${peak * 100 / before}%")
            assertTrue("$source (long titles $longTitles): the upgrade took $peak bytes over a $before-byte file", peak <= 2 * before)
        }
    }

    // The file's size and the most the upgrade took on top of it, measured
    // as it ends, before its one transaction commits, with Android's
    // settings (auto-vacuum, secure delete) and a small page cache, so
    // everything it wrote is on disk in the file and its log.
    private fun upgradePeak(source: String, longTitles: Boolean): Pair<Long, Long> {
        val file = File(Files.createTempDirectory("s").toFile(), CATALOG_FILE)
        val rows = buildServerCatalog(source, LibraryGen.server(7, GenShape(songs = 5_000, albums = 500, artists = 200)))
        val more = " (Live at the Royal Albert Hall, London, 14 March 1971) [2011 Remastered Deluxe Edition]"
        val tracks = if (!longTitles) rows.tracks else rows.tracks.map {
            it.copy(
                title = it.title + more,
                searchKey = it.searchKey + more.lowercase(),
                sortKey = it.sortKey + more.lowercase(),
                artist = it.artist + " & The Royal Philharmonic Orchestra and Chorus",
                album = it.album + more,
            )
        }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("PRAGMA auto_vacuum = FULL")
            OldSqlite.schemaStatements(18).forEach(db::execSQL)
            db.beginTransaction()
            for (t in tracks) {
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
        fun onDisk(): Long = listOf("", "-wal", "-journal").sumOf { File(file.path + it).length() }
        val before = onDisk()
        var peak = 0L
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(testContext())
                .name(file.path)
                .callback(object : SupportSQLiteOpenHelper.Callback(19) {
                    override fun onConfigure(db: SupportSQLiteDatabase) {
                        db.enableWriteAheadLogging()
                        db.query("PRAGMA wal_autocheckpoint = 0").close()
                        db.query("PRAGMA secure_delete = ON").close()
                        db.query("PRAGMA cache_size = -256").close()
                    }
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        MIGRATION_18_19.migrate(db)
                        peak = onDisk() - before
                    }
                })
                .build(),
        )
        helper.writableDatabase
        helper.close()
        return before to peak
    }
}

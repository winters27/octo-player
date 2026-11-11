package app.winters.octo.catalog

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.winters.octo.server.buildServerCatalog
import app.winters.octo.testing.GenShape
import app.winters.octo.testing.LibraryGen
import app.winters.octo.testing.OldSqlite
import app.winters.octo.testing.testContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class MigrationSpaceTest {
    // The most disk the upgrade takes is within what UpgradeGuard asks for,
    // for short and long ids alike (a server's ids carry its whole address),
    // for long titles, which the new indexes copy, and with every table the
    // upgrade copies as full as a library makes it: every song liked, rated,
    // downloaded, in the saved queue, in a playlist and in a family
    // playlist's base.
    @Test
    fun theUpgradeTakesNoMoreThanTheGuardAsks() {
        val shapes = listOf("server:h" to false, "server:music.home-server.example.net:4533" to false, "server:h" to true)
        for ((source, longTitles) in shapes) {
            val file = library(source, songs = 5_000, longTitles = longTitles)
            val asked = asked(file)
            val (before, peak) = upgradePeak(file)
            println("MEASURED $source longTitles=$longTitles file=$before peak=$peak ratio=${peak * 100 / before}% asked=$asked")
            assertTrue("$source (long titles $longTitles): the upgrade took $peak bytes over a $before-byte file, asked $asked", peak <= asked)
        }
    }

    // A family playlist's base is one line of ids in the file and a row per
    // song after the upgrade, far more than its share of the file: a small
    // library with a long family playlist still has room asked for it.
    @Test
    fun aFamilyBaseIsAskedForOnTop() {
        val ids = 60_000
        val plain = library("server:h", songs = 200)
        val (_, plainPeak) = upgradePeak(plain)
        val file = library("server:h", songs = 200) { db ->
            val base = (0 until ids).joinToString("\n") { "server-song-%08d".format(it) }
            db.execSQL("INSERT INTO playlist (id, name, createdAt, updatedAt, family, syncedSongs) VALUES ('family', 'Family', 1, 1, 1, ?)", arrayOf(base))
        }
        val asked = asked(file)
        val (before, peak) = upgradePeak(file)
        println("MEASURED base ids=$ids file=$before peak=$peak asked=$asked perId=${(peak - plainPeak) / ids}")
        assertTrue("a $ids-song base took $peak bytes over a $before-byte file, asked $asked", peak <= asked)
    }

    // The saved queue's rows are copied into rows with their keys and three
    // index entries; a long queue over a small library still fits what the
    // guard asks.
    @Test
    fun aLongSavedQueueIsAskedForOnTop() {
        val entries = 60_000
        val plain = library("server:h", songs = 200)
        val (_, plainPeak) = upgradePeak(plain)
        val file = library("server:h", songs = 200, queue = entries)
        val asked = asked(file)
        val (before, peak) = upgradePeak(file)
        println("MEASURED queue entries=$entries file=$before peak=$peak asked=$asked perEntry=${(peak - plainPeak) / entries}")
        assertTrue("a $entries-song queue took $peak bytes over a $before-byte file, asked $asked", peak <= asked)
    }

    // Each playlist row is copied into a row with four index entries before
    // the old one goes; a long playlist over a small library still fits what
    // the guard asks.
    @Test
    fun aLongPlaylistIsAskedForOnTop() {
        val rows = 60_000
        val plain = library("server:h", songs = 200)
        val (_, plainPeak) = upgradePeak(plain)
        val file = library("server:h", songs = 200, playlist = rows)
        val asked = asked(file)
        val (before, peak) = upgradePeak(file)
        println("MEASURED playlist rows=$rows file=$before peak=$peak asked=$asked perRow=${(peak - plainPeak) / rows}")
        assertTrue("a $rows-row playlist took $peak bytes over a $before-byte file, asked $asked", peak <= asked)
    }

    // What the guard asks for before the upgrade of this file.
    private fun asked(file: File): Long = (UpgradeGuard(file) { 0L }.apply { check() }.state.value as UpgradeState.NeedsSpace).bytes

    // A version 18 library of one server, with Android's settings
    // (auto-vacuum), every song liked, rated, downloaded and in a family
    // playlist's base, and a saved queue of `queue` songs and that
    // playlist of `playlist` rows (every song once by default, more by
    // adding them again).
    private fun library(
        source: String,
        songs: Int,
        longTitles: Boolean = false,
        queue: Int = songs,
        playlist: Int = songs,
        more: (SQLiteDatabase) -> Unit = {},
    ): File {
        val file = File(Files.createTempDirectory("s").toFile(), CATALOG_FILE)
        val rows = buildServerCatalog(source, LibraryGen.server(7, GenShape(songs = songs, albums = maxOf(songs / 10, 1), artists = maxOf(songs / 25, 1))))
        val extra = " (Live at the Royal Albert Hall, London, 14 March 1971) [2011 Remastered Deluxe Edition]"
        val tracks = if (!longTitles) rows.tracks else rows.tracks.map {
            it.copy(
                title = it.title + extra,
                searchKey = it.searchKey + extra.lowercase(),
                sortKey = it.sortKey + extra.lowercase(),
                artist = it.artist + " & The Royal Philharmonic Orchestra and Chorus",
                album = it.album + extra,
            )
        }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("PRAGMA auto_vacuum = FULL")
            OldSqlite.schemaStatements(18).forEach(db::execSQL)
            db.beginTransaction()
            for (t in tracks) {
                val v = ContentValues().apply {
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
                db.execSQL("INSERT INTO liked_track (trackId, relinkKey, likedAt) VALUES (?, ?, ?)", arrayOf<Any?>(t.id, t.relinkKey, t.addedAt))
                db.execSQL("INSERT INTO track_rating (trackId, relinkKey, rating, ratedAt) VALUES (?, ?, 4, ?)", arrayOf<Any?>(t.id, t.relinkKey, t.addedAt))
                db.execSQL(
                    "INSERT INTO download (trackId, sourceId, serverId, path, sizeBytes, format, state, progress, addedAt, reason, title, artist, fileSize) " +
                        "VALUES (?, ?, ?, ?, ?, 'flac', 'Done', 1.0, ?, 'liked', ?, ?, ?)",
                    arrayOf<Any?>(t.id, t.sourceId, t.nativeId, "/data/user/0/app.winters.octo/files/offline/${t.nativeId}.flac", 30_000_000L, t.addedAt, t.title, t.artist, 30_000_000L),
                )
            }
            for (a in rows.albums) {
                db.execSQL(
                    "INSERT OR IGNORE INTO album (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, year, songCount, durationMs, addedAt, artwork) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(a.id, a.sourceId, a.nativeId, a.title, a.searchKey, a.sortKey, a.artist, a.artistId, a.year, a.songCount, a.durationMs, a.addedAt, a.artwork),
                )
            }
            for (a in rows.artists) {
                db.execSQL(
                    "INSERT OR IGNORE INTO artist (id, sourceId, name, searchKey, sortKey, albumCount, songCount, artwork) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(a.id, a.sourceId, a.name, a.searchKey, a.sortKey, a.albumCount, a.songCount, a.artwork),
                )
            }
            for (p in 0 until queue) {
                db.execSQL("INSERT INTO queue_item (position, trackId, shuffledPosition) VALUES (?, ?, ?)", arrayOf<Any?>(p, tracks[p % tracks.size].id, (p * 7919L) % queue))
            }
            db.execSQL("INSERT INTO queue_state (id, currentIndex, positionMs, repeatMode, shuffle, savedAt) VALUES (0, 0, 0, 0, 0, 1)")
            db.execSQL(
                "INSERT INTO playlist (id, name, createdAt, updatedAt, family, syncedSongs) VALUES ('all', 'All', 1, 1, 1, ?)",
                arrayOf(tracks.joinToString("\n") { it.nativeId }),
            )
            for (p in 0 until playlist) {
                val t = tracks[p % tracks.size]
                db.execSQL(
                    "INSERT INTO playlist_item (playlistId, trackId, relinkKey, position, serverSongId) VALUES ('all', ?, ?, ?, ?)",
                    arrayOf<Any?>(t.id, t.relinkKey, p, t.nativeId),
                )
            }
            more(db)
            db.setTransactionSuccessful(); db.endTransaction()
            db.version = 18
        }
        return file
    }

    // The file's size and the most the upgrade took on top of it, measured
    // as it ends, before its one transaction commits, with Android's
    // settings (auto-vacuum, secure delete) and a small page cache, so
    // everything it wrote is on disk in the file and its log.
    private fun upgradePeak(file: File): Pair<Long, Long> {
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

package app.winters.octo.catalog

import android.database.sqlite.SQLiteDatabase
import app.winters.octo.testing.OldSqlite
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.schemaFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

// A version 19 library, made from Room's own schema for 19 with a linked
// playlist, its row and its base, opens through the app's own builder
// (catalogSetup, as CatalogModule opens it): Room checks every table, index
// and column against the entities, and the rows are kept.
@RunWith(RobolectricTestRunner::class)
class MigrationTo20Test {
    private fun version19(dir: File): File {
        val file = File(dir, CATALOG_FILE)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            OldSqlite.schemaStatements(19).forEach(db::execSQL)
            val identity = Json.parseToJsonElement(schemaFile(19).readText()).jsonObject["database"]!!.jsonObject["identityHash"]!!.jsonPrimitive.content
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$identity')")
            db.execSQL("INSERT INTO playlist_meta (id, nextItemKey) VALUES (0, 3)")
            db.execSQL(
                "INSERT INTO playlist (id, name, createdAt, updatedAt, serverId, sourceId, syncedAt, syncedName, serverStamp, family, itemSet, baseSet, syncedSongsKept) " +
                    "VALUES ('p', 'Road', 1, 2, 'srv', 'server:h', 2, 'Road', 'a|1', 0, 0, 0, 1)",
            )
            db.execSQL("INSERT INTO playlist_item (playlistId, itemSet, trackId, relinkKey, ord, itemKey, serverSongId) VALUES ('p', 0, 'find:x', '', 0, 1, 'x')")
            db.execSQL("INSERT INTO playlist_synced_song (playlistId, songId, copies, baseSet) VALUES ('p', 'x', 1, 0)")
            db.version = 19
        }
        return file
    }

    @Test
    fun aVersion19LibraryUpgradesThroughTheAppsBuilderAndKeepsItsPlaylists() = runBlocking {
        val dir = Files.createTempDirectory("m20").toFile()
        version19(dir)
        val db = openTestCatalog(dir)
        val w = db.openHelper.writableDatabase
        w.query("PRAGMA user_version").use { it.moveToFirst(); assertEquals(CATALOG_VERSION, it.getInt(0)) }
        assertEquals(20, CATALOG_VERSION)
        val row = db.userDao().playlistRow("p")!!
        assertFalse("upkeep starts off", row.upkeep)
        assertEquals(null, row.upkeepKind)
        assertFalse(row.octoImport)
        assertEquals("srv", row.serverId)
        assertTrue(row.syncedSongsKept)
        w.query("SELECT trackId, serverSongId FROM playlist_item WHERE playlistId = 'p'").use {
            it.moveToFirst()
            assertEquals("find:x", it.getString(0))
            assertEquals("x", it.getString(1))
        }
        w.query("SELECT copies FROM playlist_synced_song WHERE playlistId = 'p' AND songId = 'x'").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        for (table in listOf("playlist_folder", "playlist_place", "folder_op", "playlist_base_mark")) {
            w.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); assertEquals(table, 0, it.getInt(0)) }
        }
        // The new tables take rows as the app writes them.
        db.playlistFolderDao().putFolder(PlaylistFolderEntity("acct", "pfAAAAAAAAAAAAAAAAAAAA", "Road trips", null, "2026-11-14T10:00:00Z"))
        db.playlistFolderDao().putPlace(PlaylistPlaceEntity("acct", "srv", "pfAAAAAAAAAAAAAAAAAAAA"))
        assertEquals("Road trips", db.playlistFolderDao().folder("acct", "pfAAAAAAAAAAAAAAAAAAAA")?.name)
        assertFalse(db.playlistFolderDao().place("acct", "srv")!!.localOnly)
        assertFalse(db.userDao().baseSeeded("server:h"))
        db.userDao().markBaseSeeded("server:h", 1)
        assertTrue(db.userDao().baseSeeded("server:h"))
        db.close()
    }
}

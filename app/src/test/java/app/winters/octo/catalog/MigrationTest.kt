package app.winters.octo.catalog

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.testing.OldSqlite
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.schemaFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    // A version 18 library file, made from Room's own schema for 18, with
    // rows in an order that differs from their ids'.
    private fun version18(dir: File, rows: Int = 300): File {
        val file = File(dir, CATALOG_FILE)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            OldSqlite.schemaStatements(18).forEach(db::execSQL)
            val identity = Json.parseToJsonElement(schemaFile(18).readText()).jsonObject["database"]!!.jsonObject["identityHash"]!!.jsonPrimitive.content
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$identity')")
            for (i in rows downTo 1) {
                val src = if (i % 2 == 0) "device" else "server:host"
                db.execSQL(
                    "INSERT INTO source_track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, " +
                        "durationMs, addedAt, albumOrder, relinkKey, genre, mergedId) VALUES " +
                        "('$src:$i', '$src', '$i', 'T$i', 't$i', 't$i', 'A', '$src:a', 'Al', '$src:al', ${i * 1000}, $i, ${i % 7}, 'k$i', '', '$src:$i')",
                )
                db.execSQL(
                    "INSERT INTO track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, relinkKey) " +
                        "VALUES ('$src:$i', '$src', '$i', 'T$i', 't$i', 't$i', 'A', '$src:a', 'Al', '$src:al', ${i * 1000}, $i, 'k$i')",
                )
            }
            for (i in 1..20) {
                db.execSQL("INSERT INTO source_album (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, songCount, durationMs, addedAt) VALUES ('al$i', 'device', '$i', 'Al', 'al', 'al', 'A', 'a', 1, 1, 1)")
                db.execSQL("INSERT INTO source_artist (id, sourceId, name, searchKey, sortKey, albumCount, songCount) VALUES ('ar$i', 'device', 'A', 'a', 'a', 1, 1)")
            }
            db.version = 18
        }
        return file
    }

    @Test
    fun upgradesEveryRowAndRoomAcceptsTheSchema() = runBlocking {
        val dir = Files.createTempDirectory("m").toFile()
        version18(dir)
        // Room checks every table and index against the entities on open,
        // and throws on any difference.
        val db = openTestCatalog(dir)
        val conn = db.openHelper.writableDatabase
        conn.query("SELECT COUNT(*) FROM source_track").use { it.moveToFirst(); assertEquals(300, it.getInt(0)) }
        conn.query("SELECT COUNT(*) FROM track").use { it.moveToFirst(); assertEquals(300, it.getInt(0)) }
        // seq is the old rowid: the first row written (id 300) comes first.
        conn.query("SELECT id, seq, keysVersion, titleKey FROM source_track ORDER BY seq LIMIT 1").use {
            it.moveToFirst()
            assertEquals("device:300", it.getString(0))
            assertEquals(1L, it.getLong(1))
            assertEquals(0, it.getInt(2))
            assertEquals("", it.getString(3))
        }
        conn.query("SELECT COUNT(*) FROM source_album WHERE seq = rowid").use { it.moveToFirst(); assertEquals(20, it.getInt(0)) }
        conn.query("SELECT COUNT(*) FROM source_artist WHERE seq = rowid").use { it.moveToFirst(); assertEquals(20, it.getInt(0)) }
        db.close()
    }

    // The migration's statements on SQLite 3.21: every index is created on
    // an empty table (album-sized source_album and source_artist apart),
    // and no song-sized statement sorts or builds a temporary table.
    @Test
    fun noIndexIsBuiltOverSongRows() {
        val conn = OldSqlite.open(18)
        conn.createStatement().use { st ->
            repeat(50) { i -> st.execute("INSERT INTO source_track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, albumOrder, relinkKey, genre) VALUES ('s$i','d','$i','t','t','t','a','a','a','a',1,1,0,'k','')") }
            repeat(50) { i -> st.execute("INSERT INTO track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt) VALUES ('s$i','d','$i','t','t','t','a','a','a','a',1,1)") }
        }
        val recorded = RecordingDatabase(conn).also { MIGRATION_18_19.migrate(it) }
        val allowed = setOf("source_album", "source_artist")
        recorded.indexRowsBefore.forEach { (sql, rows) ->
            val table = INDEX_TARGET.find(sql)!!.groupValues[2]
            if (table !in allowed) assertEquals("index on $table built over rows: $sql", 0L, rows)
        }
        assertEquals(2, recorded.copyOpcodes.size)
        recorded.copyOpcodes.forEach { (sql, ops) ->
            assertTrue("sort in $sql", "SorterOpen" !in ops)
            assertTrue("temporary table in $sql", "OpenEphemeral" !in ops)
        }
    }
}

private val INDEX_TARGET = Regex("""CREATE (UNIQUE )?INDEX IF NOT EXISTS `[^`]+` ON `([^`]+)`""")

// Runs a migration's statements on a JDBC connection and notes, before
// each runs, what an index is built over and how a copy runs.
private class RecordingDatabase(private val conn: java.sql.Connection) : androidx.sqlite.db.SupportSQLiteDatabase by unsupported() {
    val indexRowsBefore = LinkedHashMap<String, Long>()
    val copyOpcodes = LinkedHashMap<String, List<String>>()

    override fun execSQL(sql: String) {
        INDEX_TARGET.find(sql)?.let { m ->
            conn.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM `${m.groupValues[2]}`").use { rs -> rs.next(); indexRowsBefore[sql] = rs.getLong(1) } }
        }
        if (sql.startsWith("INSERT INTO `_new_")) copyOpcodes[sql] = OldSqlite.opcodes(conn, sql)
        conn.createStatement().use { it.execute(sql) }
    }
}

// Every other database call fails the test.
private fun unsupported(): androidx.sqlite.db.SupportSQLiteDatabase =
    java.lang.reflect.Proxy.newProxyInstance(
        androidx.sqlite.db.SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java),
    ) { _, method, _ -> throw UnsupportedOperationException(method.name) } as androidx.sqlite.db.SupportSQLiteDatabase

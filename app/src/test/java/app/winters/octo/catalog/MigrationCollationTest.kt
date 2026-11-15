package app.winters.octo.catalog

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.winters.octo.testing.OldSqlite
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class MigrationCollationTest {
    // Room never checks how a column compares text, nor every detail of an
    // index, so an upgraded file is compared with a new one directly: the
    // same tables, each column with the same type, default and collation,
    // and the same indexes, each column in the same order, direction and
    // collation.
    @Test
    fun anUpgradedFileMatchesANewOne() {
        val fresh = Files.createTempDirectory("fresh").toFile()
        openTestCatalog(fresh).apply { openHelper.writableDatabase; close() }
        val upgraded = upgraded()
        val expected = describe(File(fresh, CATALOG_FILE))
        val actual = describe(upgraded)
        assertEquals(expected.keys, actual.keys)
        for (name in expected.keys) assertEquals(name, expected[name], actual[name])
    }

    // A version 18 file upgraded by the migrations alone, as Room would run them.
    private fun upgraded(): File {
        val file = File(Files.createTempDirectory("old").toFile(), CATALOG_FILE)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            OldSqlite.schemaStatements(18).forEach(db::execSQL)
            db.version = 18
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(testContext())
                .name(file.path)
                .callback(object : SupportSQLiteOpenHelper.Callback(CATALOG_VERSION) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        MIGRATION_18_19.migrate(db)
                        MIGRATION_19_20.migrate(db)
                    }
                })
                .build(),
        )
        helper.writableDatabase
        helper.close()
        return file
    }

    // Each table's columns and each index's columns, as text to compare.
    private fun describe(file: File): Map<String, String> =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val tables = db.rawQuery(
                "SELECT name, sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                    "AND name NOT IN ('android_metadata', 'room_master_table')",
                null,
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
            buildMap {
                for ((table, sql) in tables) {
                    val collations = columnCollations(sql)
                    val columns = db.rawQuery("PRAGMA table_info(`$table`)", null).use { c ->
                        buildList {
                            while (c.moveToNext()) {
                                val name = c.getString(1)
                                add("$name ${c.getString(2)} notnull=${c.getInt(3)} default=${c.getString(4)} pk=${c.getInt(5)} collate=${collations[name]}")
                            }
                        }.sorted()
                    }
                    put("table $table", columns.joinToString("\n"))
                    val indexes = db.rawQuery("PRAGMA index_list(`$table`)", null).use { c ->
                        buildList { while (c.moveToNext()) add(c.getString(1) to c.getInt(2)) }
                    }
                    for ((index, unique) in indexes) {
                        // SQLite names a key's own index after its table.
                        if (index.startsWith("sqlite_autoindex_")) continue
                        val parts = db.rawQuery("PRAGMA index_xinfo(`$index`)", null).use { c ->
                            buildList { while (c.moveToNext()) add("${c.getString(2)} desc=${c.getInt(3)} coll=${c.getString(4)} key=${c.getInt(5)}") }
                        }
                        put("index $index", "on $table unique=$unique\n" + parts.joinToString("\n"))
                    }
                }
            }
        }

    // The collation each column of a CREATE TABLE names, BINARY where it
    // names none. Constraints after the columns are skipped.
    private fun columnCollations(sql: String): Map<String, String> {
        val body = sql.substring(sql.indexOf('(') + 1, sql.lastIndexOf(')'))
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        for ((i, ch) in body.withIndex()) {
            when (ch) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) {
                    parts += body.substring(start, i)
                    start = i + 1
                }
            }
        }
        parts += body.substring(start)
        return parts.map { it.trim() }
            .filter { it.startsWith("`") || it.startsWith("\"") }
            .associate { part ->
                val name = part.substring(1, part.indexOf(part[0], 1))
                name to (COLLATE.find(part)?.groupValues?.get(1)?.uppercase() ?: "BINARY")
            }
    }

    private companion object {
        val COLLATE = Regex("""COLLATE\s+(\w+)""", RegexOption.IGNORE_CASE)
    }
}

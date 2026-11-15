package app.winters.octo.testing

import app.winters.octo.catalog.CATALOG_VERSION
import app.winters.octo.catalog.SAVE_RENAME_TRIGGERS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.DriverManager

// SQLite 3.21, older than any phone Octo runs on (Android 10 has 3.22),
// with the library's schema: for checking that a query runs there and how
// it runs.
object OldSqlite {
    // An empty library database at a schema version (the current one
    // unless named), from Room's own file.
    fun open(version: Int = CATALOG_VERSION): Connection {
        // Loaded by name, so the driver registers itself with the class
        // loader the test runs in (Robolectric has its own).
        Class.forName("org.sqlite.JDBC")
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        check(conn.metaData.databaseProductVersion.startsWith("3.21")) { "expected SQLite 3.21, got ${conn.metaData.databaseProductVersion}" }
        conn.createStatement().use { st -> schemaStatements(version).forEach(st::execute) }
        return conn
    }

    // Every CREATE statement in a schema file, tables then their indexes,
    // and from version 19 on the triggers that move a row written with a
    // renamed id, last, as the app makes them (SAVE_RENAME_TRIGGERS). The
    // current SQLite's schema (CurrentSqlite) is this one too.
    fun schemaStatements(version: Int): List<String> {
        val db = Json.parseToJsonElement(schemaFile(version).readText()).jsonObject["database"]!!.jsonObject
        val tables = db["entities"]!!.jsonArray.flatMap { e ->
            val entity = e.jsonObject
            val name = entity["tableName"]!!.jsonPrimitive.content
            listOf(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name)) +
                (entity["indices"]?.jsonArray ?: emptyList()).map { it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name) }
        }
        return if (version >= 19) tables + SAVE_RENAME_TRIGGERS else tables
    }

    // The query plan's detail lines.
    fun plan(conn: Connection, sql: String, args: List<Any?> = emptyList()): List<String> =
        conn.prepareStatement("EXPLAIN QUERY PLAN $sql").use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString("detail") else null }.toList() }
        }

    // The program's opcodes.
    fun opcodes(conn: Connection, sql: String, args: List<Any?> = emptyList()): List<String> =
        conn.prepareStatement("EXPLAIN $sql").use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString("opcode") else null }.toList() }
        }

    // A plan with nothing that grows memory with the table: no temporary
    // sort, no index built on the fly, no list built from a subquery, and
    // (for a statement read in pages) no scan. Answers what is wrong.
    fun planProblems(conn: Connection, sql: String, args: List<Any?> = emptyList(), paged: Boolean = false): List<String> =
        plan(conn, sql, args).filter { line ->
            "USE TEMP B-TREE" in line || "AUTOMATIC" in line || "LIST SUBQUERY" in line ||
                (paged && line.startsWith("SCAN") && "USING" !in line)
        }
}

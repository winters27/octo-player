package app.winters.octo.device

import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.OldSqlite
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.sql.Connection

// Every statement of a phone scan, the readers of where the phone's files
// sit and the check that a copy equals the library's rows run on an index
// on SQLite 3.21, the oldest the app runs on, and on a current one: no sort
// or temporary index in memory, no list built from a subquery, and no walk
// of a whole file-sized table out of an index's order. A folder's songs and
// a folder with everything under it are found on the folder index.
@RunWith(RobolectricTestRunner::class)
class PhoneScanQueryPlanTest {
    private val fileSized = Regex(
        """\b(scan_file|scan_row|scan_cluster|scan_group|scan_tally|scan_artist|scan_genre_name|file_tags|source_track|source_album|source_artist|stage_track|stage_album|stage_artist)\b""",
    )

    // Counts that walk a table without holding it, chunks that find their
    // last row by walking at most 2,000 in the table's own order, and
    // deletes of what is left after the chunks.
    private val wholeAllowed = setOf(PhoneScanSql.FILE_TAG_COUNT, PhoneScanSql.ROW_COUNT) + PhoneScanSql.DRAINS.flatMap { it.toList() }

    private val listFromRows = Regex("""IN\s*\(\s*SELECT""", RegexOption.IGNORE_CASE)

    // Room's named parameters as positional ones, a list as three.
    private fun positional(sql: String): String =
        sql.replace(Regex("""IN \(:\w+\)"""), "IN (?, ?, ?)").replace(Regex(""":\w+"""), "?")

    private fun plans(conn: Connection, sql: String): List<String> {
        val text = positional(sql)
        val args = List(text.count { it == '?' }) { null }
        return OldSqlite.plan(conn, text, args) + CurrentSqlite.plan(text, args)
    }

    private fun problems(conn: Connection, name: String, sql: String, whole: Boolean): List<String> =
        plans(conn, sql).filter { line ->
            "TEMP B-TREE" in line || "AUTOMATIC" in line || ("LIST SUBQUERY" in line && listFromRows.containsMatchIn(sql)) ||
                (!whole && line.startsWith("SCAN") && "USING" !in line && fileSized.containsMatchIn(line))
        }.map { "$name: $it" }

    private fun scanStatements(): Map<String, String> =
        PhoneScanSql::class.java.declaredFields
            .filter { it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .associate { it.name to it.get(null) as String }

    @Test
    fun everyScanStatementRunsOnAnIndex() {
        val statements = scanStatements()
        assertTrue("found ${statements.size}", statements.size > 50)
        OldSqlite.open().use { conn ->
            val problems = statements.flatMap { (name, sql) -> problems(conn, name, sql, sql in wholeAllowed) }
            assertTrue(problems.joinToString("\n"), problems.isEmpty())
        }
    }

    @Test
    fun theUnchangedChecksRunOnIndexes() {
        OldSqlite.open().use { conn ->
            val problems = listOf("track", "album", "artist").flatMap { kind ->
                val columns = conn.createStatement().use { st ->
                    st.executeQuery("PRAGMA table_info(source_$kind)").use { rs -> generateSequence { if (rs.next()) rs.getString("name") else null }.toList() }
                } - "mergedId"
                problems(conn, "same $kind", sameRows(kind, columns), whole = false)
            }
            assertTrue(problems.joinToString("\n"), problems.isEmpty())
        }
    }

    @Test
    fun aFolderAndEverythingUnderItAreFoundOnTheFolderIndex() {
        val level = "SELECT id FROM source_track WHERE sourceId = 'device' AND storageVolume = ? AND folder = ? ORDER BY mergedId, id"
        val subtree = "SELECT id FROM source_track WHERE sourceId = 'device' AND storageVolume = ? AND folder > ? AND folder < ?"
        OldSqlite.open().use { conn ->
            for (sql in listOf(level, subtree)) {
                val plan = plans(conn, sql)
                assertTrue("$sql\n$plan", plan.all { "index_source_track_folder" in it })
                assertTrue("$sql\n$plan", problems(conn, "folder", sql, whole = false).isEmpty())
            }
        }
    }
}

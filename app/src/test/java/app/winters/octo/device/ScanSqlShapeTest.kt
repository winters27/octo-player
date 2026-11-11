package app.winters.octo.device

import app.winters.octo.testing.OldSqlite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

// The shapes a phone scan's statements must keep. An insert into a scan
// table names every column that is NOT NULL with no default (SQLite's OR
// IGNORE skips a row that leaves one out, without a word). A delete takes
// a chunk, a page by key, or what is left after its table's chunks, so no
// delete holds the write lock for a whole table.
class ScanSqlShapeTest {
    private val statements = PhoneScanSql::class.java.declaredFields
        .filter { it.type == String::class.java && Modifier.isStatic(it.modifiers) }
        .associate { it.name to it.get(null) as String }

    private val into = Regex("""\bINTO\s+(scan_\w+|phone_scan_state)\s*\(([^)]*)\)""", RegexOption.IGNORE_CASE)

    @Test
    fun everyInsertNamesTheColumnsWithNoDefault() {
        val inserts = statements.filterValues { into.containsMatchIn(it) }
        assertTrue("found ${inserts.keys}", inserts.size >= 6)
        val problems = OldSqlite.open().use { conn ->
            inserts.flatMap { (name, sql) ->
                val match = into.find(sql)!!
                val table = match.groupValues[1]
                val named = match.groupValues[2].split(',').map { it.trim().trim('`') }.toSet()
                val required = conn.createStatement().use { st ->
                    st.executeQuery("PRAGMA table_info($table)").use { rs ->
                        generateSequence {
                            if (!rs.next()) {
                                null
                            } else {
                                // An integer primary key is the row's own number, given when left out.
                                val rowNumber = rs.getInt("pk") == 1 && rs.getString("type").equals("INTEGER", ignoreCase = true)
                                rs.getString("name").takeIf { rs.getInt("notnull") == 1 && rs.getString("dflt_value") == null && !rowNumber }
                            }
                        }.filterNotNull().toList()
                    }
                }
                (required - named).map { "$name leaves out $table.$it" }
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun everyDeleteTakesAChunkAPageOrWhatIsLeft() {
        val rests = PhoneScanSql.DRAINS.map { it.second }.toSet()
        val chunks = PhoneScanSql.DRAINS.map { it.first }
        val deletes = statements.filterValues { it.trimStart().startsWith("DELETE", ignoreCase = true) }
        val problems = deletes.filter { (_, sql) -> sql !in rests && "OFFSET 1999" !in sql && "IN (:" !in sql }.keys
        assertEquals("deletes of no chunk or page", emptySet<String>(), problems)
        // Each chunk deletes up to its table's 2,000th row by key, and is
        // followed by the delete of what is left of the same rows.
        for ((chunk, rest) in PhoneScanSql.DRAINS) {
            assertTrue(chunk, "OFFSET 1999" in chunk)
            val table = Regex("""DELETE FROM (\w+)""").find(chunk)!!.groupValues[1]
            assertTrue(rest, rest.startsWith("DELETE FROM $table"))
        }
        assertEquals(chunks.size, deletes.values.count { "OFFSET 1999" in it })
    }
}

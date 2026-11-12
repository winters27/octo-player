package app.winters.octo.server

import app.winters.octo.testing.OldSqlite
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class SortKeyHexTest {
    // Short texts mixing plain letters, accented ones, high BMP characters,
    // surrogate pairs and lone surrogates.
    private fun texts(): List<String> {
        val random = Random(20261103)
        val pieces = listOf("a", "B", "z", "é", "Ω", "Ａ", "�", "🎵", "😀", "􏿿", "\uD800", "\uDC00", " ", "0")
        return List(3_000) { buildString { repeat(random.nextInt(0, 7)) { append(pieces[random.nextInt(pieces.size)]) } } }
    }

    @Test
    fun hexOrderIsStringOrder() {
        val all = texts()
        assertEquals(all.sorted(), all.sortedBy(::sortKeyHex))
    }

    @Test
    fun theDatabaseSortsTheHexAsKotlinSortsTheText() {
        val all = texts().distinct()
        OldSqlite.open().use { conn ->
            conn.createStatement().use { it.execute("CREATE TABLE t (hex TEXT NOT NULL, i INTEGER NOT NULL)") }
            conn.prepareStatement("INSERT INTO t VALUES (?, ?)").use { insert ->
                all.forEachIndexed { i, text ->
                    insert.setString(1, sortKeyHex(text))
                    insert.setInt(2, i)
                    insert.executeUpdate()
                }
            }
            val order = conn.createStatement().use { s ->
                s.executeQuery("SELECT i FROM t ORDER BY hex").use { rows -> buildList { while (rows.next()) add(all[rows.getInt(1)]) } }
            }
            assertEquals(all.sorted(), order)
        }
    }
}

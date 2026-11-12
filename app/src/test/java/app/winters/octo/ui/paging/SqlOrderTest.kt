package app.winters.octo.ui.paging

import android.database.sqlite.SQLiteDatabase
import app.winters.octo.testing.OldSqlite
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.sql.Connection

@RunWith(RobolectricTestRunner::class)
class SqlOrderTest {
    private val texts = listOf(
        "", "a", "A", "ab", "a b", "b", "Z", "z", "é", "e\u0301", "\u00FF", "\u0100", "\uE000", "\uFFFD", "\uFFFD\uFFFD",
        "\uD83D\uDE00", "\uD83D\uDE00a", "\uD800\uDC00", "\uD83E\uDD18", "10", "9", "Untitled", "untitled",
    )

    private val values: List<SqlValue> = buildList {
        add(SqlValue.Null)
        add(SqlValue.Null)
        listOf(Long.MIN_VALUE, -2, -1, 0, 1, 2, 3, 9_007_199_254_740_992, 9_007_199_254_740_993, Long.MAX_VALUE).forEach { add(SqlValue.Int64(it)) }
        listOf(-1e19, -2.5, -2.0, -0.0, 0.0, 0.5, 2.0, 2.5, 9_007_199_254_740_992.0, 9.3e18, 1e300).forEach { add(SqlValue.Real(it)) }
        texts.forEach { add(SqlValue.Text(it)) }
        listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(0, 0), byteArrayOf(1), byteArrayOf(127), byteArrayOf(-128), byteArrayOf(-1), byteArrayOf(-1, 0))
            .forEach { add(SqlValue.Blob(it.toByteString())) }
    }

    // Kotlin sorts every kind of value as SQLite's ORDER BY does, on the
    // oldest SQLite Octo meets and on a current one: NULL, numbers (an
    // integer and a real exact against each other), text by code point,
    // BLOBs byte by byte.
    @Test
    fun valuesSortAsSqliteSortsThem() {
        val kotlin = values.indices.sortedWith(compareBy<Int, SqlValue>(SqlOrder) { values[it] }.thenBy { it })
        OldSqlite.open().use { assertEquals(kotlin, jdbcOrder(it)) }
        assertEquals(kotlin, androidOrder())
    }

    // Kotlin's own String.compareTo disagrees past U+FFFF, which is why
    // merges of SQL-ordered streams compare with SqlOrder.
    @Test
    fun textIsCodePointOrderNotUtf16() {
        assertTrue(SqlOrder.text("\uFFFD", "\uD83D\uDE00") < 0)
        assertTrue("\uFFFD".compareTo("\uD83D\uDE00") > 0)
        assertTrue(SqlOrder.text("\uE000", "\uD83D\uDE00") < 0)
        assertEquals(0, SqlOrder.text("same", "same"))
        assertTrue(SqlOrder.text("ab", "abc") < 0)
    }

    // A list's key order (segment, a lead in the chosen direction, ascending
    // ties, then the id) is SQLite's ORDER BY over the same columns, ids
    // with U+FFFD and U+1F600 among them.
    @Test
    fun rowKeysSortAsTheirOrderBy() {
        val ids = listOf("s:\uFFFD", "s:\uD83D\uDE00", "s:a", "s:A", "s:\uE000", "s:z")
        val keys = buildList {
            var n = 0
            for (segment in 0..1) for (lead in listOf(3L, 1L, 2L)) for (tie in listOf("b", "\uD83D\uDE00", "\uFFFD")) for (id in ids) {
                if ((n++ % 3) != 0) add(RowKey(segment, listOf(SqlValue.Int64(lead), SqlValue.Text(tie)), id))
            }
        }
        for (descending in listOf(false, true)) {
            val kotlin = keys.sortedWith(SqlOrder.rows(lead = 1, descending = descending))
            OldSqlite.open().use { conn ->
                conn.createStatement().use { it.execute("CREATE TABLE k (segment INTEGER, lead INTEGER, tie TEXT, id TEXT)") }
                conn.prepareStatement("INSERT INTO k VALUES (?, ?, ?, ?)").use { st ->
                    keys.forEach { k ->
                        st.setInt(1, k.segment)
                        st.setLong(2, (k.values[0] as SqlValue.Int64).v)
                        st.setString(3, (k.values[1] as SqlValue.Text).v)
                        st.setString(4, k.id)
                        st.executeUpdate()
                    }
                }
                val sql = "SELECT segment, lead, tie, id FROM k ORDER BY segment, lead ${if (descending) "DESC" else "ASC"}, tie, id"
                val sqlite = conn.createStatement().use { st ->
                    st.executeQuery(sql).use { rs ->
                        generateSequence {
                            if (rs.next()) RowKey(rs.getInt(1), listOf(SqlValue.Int64(rs.getLong(2)), SqlValue.Text(rs.getString(3))), rs.getString(4)) else null
                        }.toList()
                    }
                }
                assertEquals("descending=$descending", sqlite, kotlin)
            }
        }
    }

    private fun jdbcOrder(conn: Connection): List<Int> {
        conn.createStatement().use { it.execute("CREATE TABLE v (n INTEGER PRIMARY KEY, x)") }
        conn.prepareStatement("INSERT INTO v VALUES (?, ?)").use { st ->
            values.forEachIndexed { n, v ->
                st.setInt(1, n)
                when (v) {
                    SqlValue.Null -> st.setNull(2, java.sql.Types.NULL)
                    is SqlValue.Int64 -> st.setLong(2, v.v)
                    is SqlValue.Real -> st.setDouble(2, v.v)
                    is SqlValue.Text -> st.setString(2, v.v)
                    is SqlValue.Blob -> st.setBytes(2, v.v.toByteArray())
                }
                st.executeUpdate()
            }
        }
        return conn.createStatement().use { st ->
            st.executeQuery("SELECT n FROM v ORDER BY x, n").use { rs -> generateSequence { if (rs.next()) rs.getInt(1) else null }.toList() }
        }
    }

    private fun androidOrder(): List<Int> {
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL("CREATE TABLE v (n INTEGER PRIMARY KEY, x)")
            db.compileStatement("INSERT INTO v VALUES (?, ?)").use { st ->
                values.forEachIndexed { n, v ->
                    st.clearBindings()
                    st.bindLong(1, n.toLong())
                    when (v) {
                        SqlValue.Null -> st.bindNull(2)
                        is SqlValue.Int64 -> st.bindLong(2, v.v)
                        is SqlValue.Real -> st.bindDouble(2, v.v)
                        is SqlValue.Text -> st.bindString(2, v.v)
                        is SqlValue.Blob -> st.bindBlob(2, v.v.toByteArray())
                    }
                    st.executeInsert()
                }
            }
            return db.rawQuery("SELECT n FROM v ORDER BY x, n", null).use { c -> buildList { while (c.moveToNext()) add(c.getInt(0)) } }
        } finally {
            db.close()
        }
    }
}

package app.winters.octo.playlists

import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.OldSqlite
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A list's first found entries (a backup's likes and ratings counted for its
// plan, a playlist's songs each once) probe each entry's earlier twins by
// its song, on the found index. Probed on the list's own index, each entry
// would walk every entry before it, which for a list of 50,000 likes is
// over a billion steps. Both forms, a count and a page with a value range,
// on SQLite 3.21 and on a current one.
@RunWith(RobolectricTestRunner::class)
class MatchFirstFoundPlanTest {
    private fun plans(sql: String): List<String> {
        val args = List(sql.count { it == '?' }) { null }
        return OldSqlite.open().use { conn -> OldSqlite.plan(conn, sql, args) } + CurrentSqlite.plan(sql, args)
    }

    @Test
    fun earlierTwinsAreProbedBySong() {
        for (sql in listOf(matchFirstFoundSql("COUNT(*)", null, null), matchFirstFoundSql("q.id, q.foundId", 1..5, 50))) {
            val lines = plans(sql)
            val probes = lines.filter { it.startsWith("SEARCH p ") || "match_query AS p" in it }
            assertTrue("$lines", probes.size == 2 && probes.all { "index_match_query_found" in it })
        }
    }
}

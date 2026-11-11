package app.winters.octo.catalog

import app.winters.octo.backup.LIKED_KEYS_SQL
import app.winters.octo.backup.RATED_KEYS_SQL
import app.winters.octo.playlists.MATCH_LAST_VALUE_SQL
import app.winters.octo.playlists.PHONE_PATHS_SQL
import app.winters.octo.playlists.matchFirstFoundSql
import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.OldSqlite
import app.winters.octo.ui.paging.songColumns
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Every statement the server lists, the network starts, backups and
// playlist files run over song-sized rows runs on SQLite 3.21, the oldest
// the app meets, and on a current one, each on its own, led by its named
// index (or one of the owner's indexes, "|" between them, for a count that
// reads every row of its owner): no sort or index built in memory, no list
// built from a subquery but an IN list of bound ids, no walk of a whole
// table out of an index's order.
@RunWith(RobolectricTestRunner::class)
class ServerReadsPlanTest {
    private val shown = serverShown().joinToString(" AND ")
    private val library = (serverShown() + "e.kind = 1").joinToString(" AND ")
    private val select = "e.position AS position, e.trackId AS shownId, (t.id IS NULL) AS isFind, NULL AS entryOrder, " + songColumns()
    private val anyOwnerIndex = "sqlite_autoindex_server_entry_1|index_server_entry_owner_trackId_position|index_server_entry_owner_kind_songId_position"

    // The statements a list's rows loader runs, for both of its filters.
    private fun rowStatements(name: String, where: String, found: String) = listOf(
        Triple("$name page", "SELECT $select FROM $SERVER_ROWS_WALK_FROM WHERE $where AND (e.position) > (?) ORDER BY e.position LIMIT 50", "sqlite_autoindex_server_entry_1"),
        Triple("$name back", "SELECT $select FROM $SERVER_ROWS_WALK_FROM WHERE $where AND (e.position) < (?) ORDER BY e.position DESC LIMIT 50", "sqlite_autoindex_server_entry_1"),
        Triple("$name place", "SELECT $select FROM $SERVER_ROWS_WALK_FROM WHERE $where ORDER BY e.position LIMIT 1 OFFSET 7", "sqlite_autoindex_server_entry_1"),
        Triple("$name count", "SELECT COUNT(*) FROM $SERVER_ROWS_WALK_FROM WHERE $where", "sqlite_autoindex_server_entry_1"),
        Triple("$name before", "SELECT COUNT(*) FROM $SERVER_ROWS_WALK_FROM WHERE $where AND (e.position) < (?)", "sqlite_autoindex_server_entry_1"),
        Triple("$name small", "SELECT * FROM (SELECT $select FROM $SERVER_ROWS_WALK_FROM WHERE $where LIMIT 501)", "sqlite_autoindex_server_entry_1"),
        Triple("$name song's row", "SELECT e.position FROM $SERVER_ROWS_FROM WHERE $found AND e.trackId = ? LIMIT 1", "index_server_entry_owner_trackId_position"),
        Triple("$name present", "SELECT e.trackId FROM $SERVER_ROWS_FROM WHERE $found AND e.trackId IN (?, ?, ?)", "index_server_entry_owner_trackId_position"),
    )

    // Statements whose IN list of bound ids 3.21 reads as a list.
    private val idLists = setOf("rows present", "library rows present", "phone paths")

    private val statements = listOf(
        Triple("entry heads", SERVER_HEADS_SQL, "sqlite_autoindex_server_entry_1"),
        Triple("link", SERVER_LINK_SQL, "sqlite_autoindex_server_entry_1"),
        Triple("wait", SERVER_WAIT_SQL, "sqlite_autoindex_server_entry_1"),
        Triple("match group", SERVER_GROUP_SQL, "index_server_match_owner_title_artist_lengthMs"),
        Triple("pool page", SERVER_POOL_SQL, "index_track_searchKey_id"),
        Triple("open groups", SERVER_OPEN_GROUPS_SQL, "sqlite_autoindex_server_lookup_1"),
        Triple("settle", SERVER_SETTLE_SQL, "sqlite_autoindex_server_entry_1"),
        Triple("finds by song", SERVER_FINDS_BY_SONG_SQL, "index_server_entry_owner_kind_songId_position"),
        Triple("first finds", SERVER_FIRST_FINDS_SQL, "sqlite_autoindex_server_entry_1"),
        Triple("adopt", SERVER_ADOPT_SQL, "index_server_entry_owner_trackId_position"),
        Triple("suggested", SERVER_SUGGESTED_SQL, "index_server_entry_owner_songId_position"),
        Triple("newest read", SERVER_NEWEST_SQL, "index_server_head_list"),
        Triple("copy page", SERVER_COPY_SQL, "sqlite_autoindex_server_entry_1"),
        Triple("last place", SERVER_LAST_POSITION_SQL, "sqlite_autoindex_server_entry_1"),
        Triple("first of a song", SERVER_FIRST_OF_SONG_SQL, "index_server_entry_owner_songId_position"),
        Triple("song at a place", SERVER_TRACK_AT_SQL, "sqlite_autoindex_server_entry_1"),
        Triple("facts", serverFactsSql(false), anyOwnerIndex),
        Triple("facts, library only", serverFactsSql(true), anyOwnerIndex),
        Triple("backup likes", LIKED_KEYS_SQL, "index_liked_track_likedAt"),
        Triple("backup ratings", RATED_KEYS_SQL, "index_track_rating_ratedAt"),
        Triple("first found", matchFirstFoundSql("q.id, q.foundId", null, 500), "index_match_query_list"),
        Triple("first found, rated", matchFirstFoundSql("q.id, q.foundId", 1..5, 500), "index_match_query_list"),
        Triple("distinct found", matchFirstFoundSql("COUNT(*)", null, null), "index_match_query_list"),
        Triple("last value", "$MATCH_LAST_VALUE_SQL ORDER BY id DESC LIMIT 1", "index_match_query_found"),
        Triple("phone paths", PHONE_PATHS_SQL.replace("%s", "?, ?, ?"), "index_source_track_mergedId"),
    ) + rowStatements("rows", shown, serverFoundWhere(false)) + rowStatements("library rows", library, serverFoundWhere(true))

    private val songSized = Regex("""\b(server_entry|track|source_track|liked_track|track_rating|match_query)\b""")

    private fun plans(sql: String): List<Pair<String, List<String>>> {
        val args = List(Regex("""\?""").findAll(sql).count()) { null }
        return listOf(
            "3.21" to OldSqlite.open().use { conn -> OldSqlite.plan(conn, sql, args) },
            "current" to CurrentSqlite.plan(sql, args),
        )
    }

    @Test
    fun everyStatementIsLedByItsIndex() {
        val problems = mutableListOf<String>()
        var planned = 0
        for ((name, sql, index) in statements) {
            for ((version, lines) in plans(sql)) {
                planned += lines.size
                for (line in lines) {
                    val wholeScan = line.startsWith("SCAN") && "USING" !in line && songSized.containsMatchIn(line)
                    val subquery = "LIST SUBQUERY" in line && name !in idLists
                    if ("TEMP B-TREE" in line || "AUTOMATIC" in line || subquery || wholeScan) problems += "$name ($version): $line"
                }
                val led = index.split('|')
                if (lines.none { line -> led.any { it in line } }) problems += "$name ($version): not led by $index: $lines"
            }
        }
        assertTrue(planned >= statements.size)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    // The writes prepare on the oldest.
    @Test
    fun theWritesPrepareOnTheOldest() {
        OldSqlite.open().use { conn ->
            listOf(SERVER_ENTRY_INSERT_SQL, SERVER_NEW_GROUP_SQL, SERVER_LOOKUP_SQL, SERVER_KEY_SQL, SERVER_PLACE_SQL, SERVER_UNLOOK_SQL, SERVER_HEAD_PUT_SQL, SERVER_COMPLETE_SQL)
                .forEach { conn.prepareStatement(it).close() }
        }
    }
}

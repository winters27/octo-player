package app.winters.octo.catalog

import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.OldSqlite
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Every statement the playlist sets, the edit log and a sync step run over
// a song-sized table or a step's work tables is led by an index on SQLite
// 3.21, the oldest the app runs on, and on a current one: no sort or
// temporary index in memory, and no walk of a whole table outside an
// index's order. The baseline statement reads both its arms by their
// named indexes in one statement, with no sort.
@RunWith(RobolectricTestRunner::class)
class PlaylistSyncPlanTest {
    private val statements = mapOf(
        "baseline page" to BASELINE_PAGE_SQL,
        "log rows" to LOG_ROWS_SQL,
        "log restore" to LOG_RESTORE_SQL,
        "pool page" to POOL_PAGE_SQL,
        "open groups near a length" to OPEN_GROUPS_NEAR_SQL,
        "settle" to SETTLE_SQL,
        "finds by song" to FINDS_BY_SONG_SQL,
        "entry items" to ENTRY_ITEMS_SQL,
        "local page" to LOCAL_PAGE_SQL,
        "anchored page" to ANCHORED_PAGE_SQL,
        "next by track" to NEXT_BY_TRACK_SQL,
        "next untaken" to NEXT_UNTAKEN_SQL,
        "phone by song" to PHONE_BY_SONG_SQL,
        "changes by song" to CHANGES_BY_SONG_SQL,
        "change groups" to CHANGE_GROUPS_SQL,
        "base from entries" to BASE_FROM_ENTRIES_SQL,
        "base from phone" to BASE_FROM_PHONE_SQL,
        "max item set" to "SELECT MAX(itemSet) FROM playlist_item WHERE playlistId = :playlistId",
        "next item set" to "SELECT MIN(itemSet) FROM playlist_item WHERE playlistId = :playlistId AND itemSet > :after",
        "next base set" to "SELECT MIN(baseSet) FROM playlist_synced_song WHERE playlistId = :playlistId AND baseSet > :after",
        "set rows" to "SELECT id FROM playlist_item WHERE playlistId = :playlistId AND itemSet = :itemSet LIMIT :limit",
        "set pinned" to "SELECT EXISTS(SELECT 1 FROM playlist_set_pin WHERE playlistId = :playlistId AND itemSet = :itemSet)",
        "log page" to "SELECT seq FROM playlist_edit_log WHERE playlistId = :playlistId AND itemSet = :itemSet LIMIT :limit",
        "row at" to "SELECT ord, itemKey FROM playlist_item WHERE playlistId = :playlistId AND itemSet = :itemSet AND itemKey = :itemKey",
        "first ord of" to "SELECT MIN(ord) FROM playlist_item WHERE playlistId = :playlistId AND itemSet = :itemSet AND trackId = :trackId",
        "first key at" to "SELECT MIN(itemKey) FROM playlist_item WHERE playlistId = :playlistId AND itemSet = :itemSet AND ord = :ord",
        "first position of" to "SELECT MIN(position) FROM playlist_entry WHERE read = :read AND trackId = :trackId",
        "position from end" to "SELECT position FROM playlist_entry WHERE read = :read AND songId = :songId ORDER BY position DESC LIMIT 1 OFFSET :skip",
        "entries between" to "SELECT COUNT(*) FROM playlist_entry WHERE read = :read AND songId = :songId AND position > :after AND position <= :upTo",
        "last positions" to "SELECT position FROM playlist_entry WHERE read = :read AND songId = :songId AND position < :before ORDER BY position DESC LIMIT :limit",
        "copies on" to COPIES_ON_SQL,
        "set count" to "SELECT COUNT(*) FROM playlist_item WHERE playlistId = :playlistId AND itemSet = :itemSet",
        "base page" to "SELECT songId, copies FROM playlist_synced_song WHERE playlistId = :playlistId AND baseSet = :baseSet AND songId >= :from ORDER BY songId LIMIT :limit",
        "first finds" to "SELECT * FROM playlist_entry WHERE read = :read AND position > :after AND firstFind = 1 ORDER BY position LIMIT :limit",
    )

    // The indexes the baseline statement's arms are read by.
    private val baselineIndexes = listOf("index_playlist_item_set", "index_playlist_edit_log_ord", "index_playlist_edit_log_key")

    private val songSized = Regex("""\b(track|source_track|playlist_item|playlist_edit_log|playlist_synced_song)\b""")

    private fun positional(sql: String): String =
        sql.replace(Regex("""IN \(:\w+\)"""), "IN (?, ?, ?)").replace(Regex(""":\w+"""), "?")

    private fun plans(sql: String): List<String> {
        val text = positional(sql)
        val args = List(text.count { it == '?' }) { null }
        return OldSqlite.open().use { conn -> OldSqlite.plan(conn, text, args) } + CurrentSqlite.plan(text, args)
    }

    @Test
    fun everyStatementIsLedByAnIndex() {
        val problems = mutableListOf<String>()
        for ((name, sql) in statements) {
            val lines = plans(sql)
            for (line in lines) {
                val wholeScan = line.startsWith("SCAN") && "USING" !in line && (songSized.containsMatchIn(line) || "playlist_" in line)
                if ("TEMP B-TREE" in line || "AUTOMATIC" in line || wholeScan) problems += "$name: $line"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    // A page's copies on the server are found from its songs: an index that
    // starts with the source would walk every copy the server has for each
    // half page of a write-back.
    @Test
    fun copiesOnAPageAreLedByItsSongs() {
        val lines = plans(COPIES_ON_SQL)
        val reads = lines.filter { "source_track" in it }
        assertTrue("$lines", reads.size == 2 && reads.all { "index_source_track_mergedId (mergedId=?)" in it })
    }

    @Test
    fun theBaselinePageReadsBothArmsByTheirIndexes() {
        val lines = plans(BASELINE_PAGE_SQL)
        for (index in baselineIndexes) assertTrue("$index in $lines", lines.any { index in it })
        assertTrue("$lines", lines.none { "TEMP B-TREE" in it })
    }
}

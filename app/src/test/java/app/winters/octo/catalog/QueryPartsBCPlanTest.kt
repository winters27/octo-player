package app.winters.octo.catalog

import app.winters.octo.offline.DONE_AMONG_SQL
import app.winters.octo.offline.DONE_FOR_SQL
import app.winters.octo.offline.IS_DONE_SQL
import app.winters.octo.offline.KEPT_AMONG_SQL
import app.winters.octo.offline.KEPT_ROW_SQL
import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.OldSqlite
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Every statement the likes, the download lookups and the playlist
// summaries run over a song-sized table is led by an index on SQLite 3.21,
// the oldest the app runs on, and on a current one: no sort or temporary
// index in memory, no list built from a subquery, and no walk of a whole
// table that is not in an index's order.
@RunWith(RobolectricTestRunner::class)
class QueryPartsBCPlanTest {
    private val statements = mapOf(
        "liked among" to LIKED_AMONG_SQL,
        "liked cover page" to LIKED_COVER_PAGE_SQL,
        "done among" to DONE_AMONG_SQL,
        "done for" to DONE_FOR_SQL,
        "is done" to IS_DONE_SQL,
        "kept among" to KEPT_AMONG_SQL,
        "kept row" to KEPT_ROW_SQL,
        "playlist totals" to PLAYLIST_TOTALS_SQL,
        "playlist cover page" to PLAYLIST_COVER_PAGE_SQL,
        "playlist holds find" to PLAYLIST_HOLDS_FIND_SQL,
    )

    // The index each must be led by.
    private val leads = mapOf(
        "liked among" to "sqlite_autoindex_liked_track_1",
        "liked cover page" to "index_liked_track_likedAt",
        "done among" to "index_download_trackId",
        "done for" to "index_download_trackId",
        "is done" to "index_download_trackId",
        "kept among" to "index_download_trackId",
        "kept row" to "index_download_trackId",
        "playlist totals" to "index_playlist_item_set",
        "playlist cover page" to "index_playlist_item_set",
        "playlist holds find" to "index_playlist_item_set_track",
    )

    private val songSized = Regex("""\b(track|liked_track|download|playlist_item|online_song)\b""")

    // A list built from a subquery's rows; a list of bound values (a chunk
    // of at most 900) is not one.
    private val listFromRows = Regex("""IN\s*\(\s*SELECT""", RegexOption.IGNORE_CASE)

    // Room's named parameters as positional ones, a list as three.
    private fun positional(sql: String): String =
        sql.replace(Regex("""IN \(:\w+\)"""), "IN (?, ?, ?)").replace(Regex(""":\w+"""), "?")

    private fun plans(sql: String): List<String> {
        val text = positional(sql)
        val args = List(text.count { it == '?' }) { null }
        return OldSqlite.open().use { conn -> OldSqlite.plan(conn, text, args) } + CurrentSqlite.plan(text, args)
    }

    @Test
    fun everyPartsBAndCStatementIsLedByAnIndex() {
        val problems = mutableListOf<String>()
        for ((name, sql) in statements) {
            val lines = plans(sql)
            for (line in lines) {
                val wholeScan = line.startsWith("SCAN") && "USING" !in line && songSized.containsMatchIn(line)
                val rowList = "LIST SUBQUERY" in line && listFromRows.containsMatchIn(sql)
                if ("TEMP B-TREE" in line || "AUTOMATIC" in line || rowList || wholeScan) problems += "$name: $line"
            }
            leads[name]?.let { index -> if (lines.none { index in it }) problems += "$name: not led by $index: $lines" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    // The list of playlists is playlist-sized: it may sort, but walks no
    // song-sized table.
    @Test
    fun thePlaylistListReadsOnlyPlaylists() {
        val lines = plans(PLAYLIST_ROWS_SQL)
        assertTrue("$lines", lines.none { songSized.containsMatchIn(it) })
    }
}

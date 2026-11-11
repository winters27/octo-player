package app.winters.octo.catalog

import app.winters.octo.listening.ALBUMS_AMONG_SQL
import app.winters.octo.listening.ALBUM_PLAYS_PAGE_SQL
import app.winters.octo.listening.ALBUM_TOP_SQL
import app.winters.octo.listening.ARTIST_TOP_SQL
import app.winters.octo.listening.COPIES_OF_SQL
import app.winters.octo.listening.DELETE_TOTALS_SQL
import app.winters.octo.listening.DIFF_COUNT_SQL
import app.winters.octo.listening.DIFF_PAGE_SQL
import app.winters.octo.listening.DROP_DIFFS_SQL
import app.winters.octo.listening.KNOWN_TRACKS_SQL
import app.winters.octo.listening.MOST_PLAYED_SQL
import app.winters.octo.listening.PHONE_PLAYS_OF_SQL
import app.winters.octo.listening.PHONE_PLAY_PAGE_SINCE_SQL
import app.winters.octo.listening.PHONE_PLAY_PAGE_SQL
import app.winters.octo.listening.PLAYS_AMONG_SQL
import app.winters.octo.listening.RECENT_PHONE_SQL
import app.winters.octo.listening.RECENT_PLAYS_SQL
import app.winters.octo.listening.SERVER_COPY_PAGE_SQL
import app.winters.octo.listening.STORED_COUNT_SQL
import app.winters.octo.listening.STORED_PAGE_SQL
import app.winters.octo.listening.SWEEP_PAGE_SQL
import app.winters.octo.listening.SWEEP_SQL
import app.winters.octo.listening.TOTAL_OF_SQL
import app.winters.octo.listening.TRACKS_AMONG_SQL
import app.winters.octo.listening.UNPLAYED_ALBUM_PAGE_SQL
import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.OldSqlite
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Every statement round 1b runs over a song-sized table is led by an index
// on SQLite 3.21, the oldest the app runs on, and on a current one: no sort
// or temporary index in memory, no list built from a subquery, and no walk
// of a whole table that is not in an index's order.
@RunWith(RobolectricTestRunner::class)
class Query1bPlanTest {
    // Part A: the play walks, the totals' writes and their readers.
    private val statements = mapOf(
        "phone play page" to PHONE_PLAY_PAGE_SQL,
        "phone play page since" to PHONE_PLAY_PAGE_SINCE_SQL,
        "phone plays of" to PHONE_PLAYS_OF_SQL,
        "server copy page" to SERVER_COPY_PAGE_SQL,
        "copies of" to COPIES_OF_SQL,
        "stored page" to STORED_PAGE_SQL,
        "total of" to TOTAL_OF_SQL,
        "stored count" to STORED_COUNT_SQL,
        "diff page" to DIFF_PAGE_SQL,
        "drop diffs" to DROP_DIFFS_SQL,
        "diff count" to DIFF_COUNT_SQL,
        "known tracks" to KNOWN_TRACKS_SQL,
        "delete totals" to DELETE_TOTALS_SQL,
        "sweep page" to SWEEP_PAGE_SQL,
        "sweep" to SWEEP_SQL,
        "most played" to MOST_PLAYED_SQL,
        "album plays page" to ALBUM_PLAYS_PAGE_SQL,
        "unplayed album page" to UNPLAYED_ALBUM_PAGE_SQL,
        "albums among" to ALBUMS_AMONG_SQL,
        "tracks among" to TRACKS_AMONG_SQL,
        "recent plays" to RECENT_PLAYS_SQL,
        "plays among" to PLAYS_AMONG_SQL,
        "album top" to ALBUM_TOP_SQL,
        "artist top" to ARTIST_TOP_SQL,
        "recent phone" to RECENT_PHONE_SQL,
    )

    // Album-sized, and sorted keeping only its limit: Home's favorite albums.
    private val albumSized = mapOf("latest liked albums" to LATEST_LIKED_ALBUMS_SQL)

    // The index each walk must be led by.
    private val leads = mapOf(
        "phone play page" to "index_play_event_trackId",
        "phone play page since" to "index_play_event_trackId",
        "server copy page" to "index_source_track_mergedId",
        "copies of" to "index_source_track_mergedId",
        "most played" to "index_play_total_most",
        "album plays page" to "index_play_total_album",
        "unplayed album page" to "index_play_total_album",
        "recent plays" to "index_play_event_startedAt",
        "album top" to "index_play_total_album",
        "artist top" to "index_play_total_artist",
        "recent phone" to "index_play_total_phone",
    )

    private val listFromRows = Regex("""IN\s*\(\s*SELECT""", RegexOption.IGNORE_CASE)

    private val songSized = Regex("""\b(play_event|source_track|track|play_total|play_total_diff|sent_play|sent_recent)\b""")

    // Room's named parameters as positional ones, a list as three.
    private fun positional(sql: String): String =
        sql.replace(Regex("""IN \(:\w+\)"""), "IN (?, ?, ?)").replace(Regex(""":\w+"""), "?")

    private fun plans(sql: String): List<String> {
        val text = positional(sql)
        val args = List(text.count { it == '?' }) { null }
        return OldSqlite.open().use { conn -> OldSqlite.plan(conn, text, args) } + CurrentSqlite.plan(text, args)
    }

    @Test
    fun everyPartAStatementIsLedByAnIndex() {
        val problems = mutableListOf<String>()
        for ((name, sql) in statements) {
            val lines = plans(sql)
            for (line in lines) {
                val wholeScan = line.startsWith("SCAN") && "USING" !in line && songSized.containsMatchIn(line)
                val bad = "TEMP B-TREE" in line || "AUTOMATIC" in line || wholeScan ||
                    ("LIST SUBQUERY" in line && listFromRows.containsMatchIn(sql))
                if (bad) problems += "$name: $line"
            }
            leads[name]?.let { index -> if (lines.none { index in it }) problems += "$name: not led by $index: $lines" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun albumSizedSortsKeepTheirLimit() {
        val problems = mutableListOf<String>()
        for ((name, sql) in albumSized) {
            for (line in plans(sql)) {
                if ("AUTOMATIC" in line || (line.startsWith("SCAN") && songSized.containsMatchIn(line) && "USING" !in line)) problems += "$name: $line"
            }
            if ("LIMIT" !in sql) problems += "$name: no limit"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}

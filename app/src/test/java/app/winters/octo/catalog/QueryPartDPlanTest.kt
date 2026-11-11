package app.winters.octo.catalog

import app.winters.octo.offline.ALBUM_SONG_PAGE_SQL
import app.winters.octo.offline.DONE_FILES_PAGE_SQL
import app.winters.octo.offline.HELD_PAGE_SQL
import app.winters.octo.offline.KEY_DONE_SQL
import app.winters.octo.offline.LIKED_PAGE_SQL
import app.winters.octo.offline.NUMBERED_PAGE_SQL
import app.winters.octo.offline.LIBRARY_AMONG_SQL
import app.winters.octo.offline.PLAYLIST_SETS_SQL
import app.winters.octo.offline.REASONS_OF_SQL
import app.winters.octo.offline.RELINK_SQL
import app.winters.octo.offline.SET_SONG_PAGE_SQL
import app.winters.octo.offline.SOURCE_PAGE_SQL
import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.OldSqlite
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Every statement the rules walk, the paged downloads and the finds
// pruning run over a song-sized table is led by an index on SQLite 3.21,
// the oldest the app runs on, and on a current one: no sort or temporary
// index in memory, no list built from a subquery, and no walk of a whole
// table that is not in an index's order.
@RunWith(RobolectricTestRunner::class)
class QueryPartDPlanTest {
    private val statements = mapOf(
        "held page" to HELD_PAGE_SQL,
        "liked page" to LIKED_PAGE_SQL,
        "set song page" to SET_SONG_PAGE_SQL,
        "reasons of" to REASONS_OF_SQL,
        "library among" to LIBRARY_AMONG_SQL,
        "album song page" to ALBUM_SONG_PAGE_SQL,
        "done files page" to DONE_FILES_PAGE_SQL,
        "numbered page" to NUMBERED_PAGE_SQL,
        "source page" to SOURCE_PAGE_SQL,
        "key done" to KEY_DONE_SQL,
    )

    // The index each must be led by.
    private val leads = mapOf(
        "held page" to "index_download_trackId",
        "liked page" to "sqlite_autoindex_liked_track_1",
        "set song page" to "index_playlist_item_set_track",
        "reasons of" to "index_download_trackId",
        "library among" to "sqlite_autoindex_track_1",
        "album song page" to "index_track_album_songs",
        "done files page" to "sqlite_autoindex_download_1",
        "numbered page" to "INTEGER PRIMARY KEY",
        "source page" to "sqlite_autoindex_download_1",
        "key done" to "sqlite_autoindex_download_1",
    )

    private val songSized = Regex("""\b(track|source_track|liked_track|download|playlist_item|online_song|queue_entry|queue_item|playlist_edit_log)\b""")

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
    fun everyPartDStatementIsLedByAnIndex() {
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

    // The relink goes over every download by design and writes only the
    // ones whose song changed; each looks up its server song by its index.
    @Test
    fun theRelinkLooksEachSongUpByItsIndex() {
        val lines = plans(RELINK_SQL)
        val problems = lines.filter { "TEMP B-TREE" in it || "AUTOMATIC" in it || ("SCAN" in it && "source_track" in it) }
        assertTrue("$problems in $lines", problems.isEmpty())
        assertTrue("$lines", lines.any { "source_track" in it && "index_source_track_sourceId_nativeId" in it })
    }

    // The finds pruning goes over the finds, which are not song-sized, and
    // looks each up in the playlist rows of every set, the queue's entries
    // and the edit log by their song id index. queue_item has no such
    // index and is read once, as today.
    @Test
    fun thePruneLooksEachFindUpByIndex() {
        val lines = plans(PRUNE_FINDS_SQL)
        for (index in listOf("index_playlist_item_trackId", "index_queue_entry_trackId", "index_playlist_edit_log_trackId")) {
            assertTrue("$index in $lines", lines.any { index in it })
        }
        val problems = lines.filter { line ->
            "TEMP B-TREE" in line || "AUTOMATIC" in line ||
                (line.startsWith("SCAN") && songSized.containsMatchIn(line) && "online_song" !in line && "queue_item" !in line)
        }
        assertTrue("$problems in $lines", problems.isEmpty())
    }

    // The kept playlists' sets are playlist-sized.
    @Test
    fun theKeptSetsReadOnlyPlaylists() {
        val lines = plans(PLAYLIST_SETS_SQL)
        assertTrue("$lines", lines.none { songSized.containsMatchIn(it) })
    }
}

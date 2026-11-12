package app.winters.octo.catalog

import app.winters.octo.playback.ALBUM_LIST_BACK_SQL
import app.winters.octo.playback.ALBUM_LIST_COUNT_SQL
import app.winters.octo.playback.ALBUM_LIST_PAGE_SQL
import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.OldSqlite
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Every statement the queue runs over its song-sized rows (and the library
// reads its plays and adds make) runs on SQLite 3.21, the oldest the app
// meets, and on a current one, led by its named index: no sort or index
// built in memory, no list built from a subquery, no walk of a whole table
// out of an index's order. The build's two writes prepare on 3.21, where an
// UPSERT would not.
@RunWith(RobolectricTestRunner::class)
class QueuePlanTest {
    // Each statement, the index that must lead it, and its arguments.
    private val statements = listOf(
        Triple("window after, play order", QUEUE_AFTER_QK_SQL, "index_queue_entry_qk"),
        Triple("window before, play order", QUEUE_BEFORE_QK_SQL, "index_queue_entry_qk"),
        Triple("window after, shuffled", QUEUE_AFTER_SK_SQL, "index_queue_entry_sk"),
        Triple("window before, shuffled", QUEUE_BEFORE_SK_SQL, "index_queue_entry_sk"),
        Triple("tail count, play order", QUEUE_COUNT_AFTER_QK_SQL, "index_queue_entry_qk"),
        Triple("tail count, shuffled", QUEUE_COUNT_AFTER_SK_SQL, "index_queue_entry_sk"),
        Triple("tail length, play order", QUEUE_LENGTH_AFTER_QK_SQL, "index_queue_entry_qk"),
        Triple("tail length, shuffled", QUEUE_LENGTH_AFTER_SK_SQL, "index_queue_entry_sk"),
        Triple("count", QUEUE_COUNT_SQL, "index_queue_entry_"),
        Triple("next play key", QUEUE_NEXT_QK_SQL, "index_queue_entry_qk"),
        Triple("previous play key", QUEUE_PREV_QK_SQL, "index_queue_entry_qk"),
        Triple("next shuffled key", QUEUE_NEXT_SK_SQL, "index_queue_entry_sk"),
        Triple("previous shuffled key", QUEUE_PREV_SK_SQL, "index_queue_entry_sk"),
        Triple("last play key", QUEUE_MAX_QK_SQL, "index_queue_entry_qk"),
        Triple("last shuffled key", QUEUE_MAX_SK_SQL, "index_queue_entry_sk"),
        Triple("first play key", QUEUE_MIN_QK_SQL, "index_queue_entry_qk"),
        Triple("first shuffled key", QUEUE_MIN_SK_SQL, "index_queue_entry_sk"),
        Triple("a list's first play key", QUEUE_LIST_FIRST_QK_SQL, "sqlite_autoindex_queue_entry_1"),
        Triple("a list's first shuffled key", QUEUE_LIST_FIRST_SK_SQL, "sqlite_autoindex_queue_entry_1"),
        Triple("build update", QUEUE_BUILD_UPDATE_SQL, "sqlite_autoindex_queue_entry_1"),
        Triple("song held", QUEUE_HOLDS_SONG_SQL, "index_queue_entry_trackId"),
        Triple("page end", QUEUE_PAGE_END_SQL, "sqlite_autoindex_queue_entry_1"),
        Triple("delete range", QUEUE_DELETE_RANGE_SQL, "sqlite_autoindex_queue_entry_1"),
        Triple("heal page", QUEUE_HEAL_PAGE_SQL, "sqlite_autoindex_queue_entry_1"),
        Triple("follow", QUEUE_FOLLOW_SQL, "sqlite_autoindex_source_track_1"),
        Triple("follow or gone", QUEUE_FOLLOW_OR_GONE_SQL, "sqlite_autoindex_source_track_1"),
        Triple("held among", queueHeldAmongSql(3), "index_queue_entry_trackId"),
        Triple("a list's rows of a song", QUEUE_LIST_HOLDS_SQL, "index_queue_entry_trackId"),
        Triple("rows holding the first key", QUEUE_FIRST_KEYED_SQL, "index_queue_entry_skBefore"),
        Triple("next generation with rows", QUEUE_NEXT_GENERATION_SQL, "(queueId>?)"),
        Triple("next generation with lists", QUEUE_NEXT_LISTED_SQL, "index_queue_list_queueId"),
        Triple("album list page", ALBUM_LIST_PAGE_SQL, "index_track_album_songs"),
        Triple("album list back", ALBUM_LIST_BACK_SQL, "index_track_album_songs"),
        Triple("album list count", ALBUM_LIST_COUNT_SQL, "index_track_album_songs"),
    )

    private val songSized = Regex("""\b(queue_entry|track|source_track|merged_id_next)\b""")

    // The statement's plan on 3.21 and on a current SQLite, each on its own.
    private fun plans(sql: String): List<Pair<String, List<String>>> {
        val args = List(Regex("""\?(\d+)?""").findAll(sql).count()) { null }
        val numbered = Regex("""\?(\d+)""").findAll(sql).map { it.groupValues[1].toInt() }.maxOrNull()
        val bound = if (numbered != null) List(numbered) { null } else args
        return listOf(
            "3.21" to OldSqlite.open().use { conn -> OldSqlite.plan(conn, sql, bound) },
            "current" to CurrentSqlite.plan(sql, bound),
        )
    }

    @Test
    fun everyQueueStatementIsLedByItsIndex() {
        val problems = mutableListOf<String>()
        var planned = 0
        for ((name, sql, index) in statements) {
            for ((version, lines) in plans(sql)) {
                planned += lines.size
                for (line in lines) {
                    val wholeScan = line.startsWith("SCAN") && "USING" !in line && songSized.containsMatchIn(line)
                    if ("TEMP B-TREE" in line || "AUTOMATIC" in line || "LIST SUBQUERY" in line || wholeScan) problems += "$name ($version): $line"
                }
                // Every seek of the queue's rows uses the named index.
                val seeks = lines.filter { "queue_entry" in it && it.startsWith("SEARCH") }
                if (lines.none { index in it } || seeks.any { index !in it && index.startsWith("index_queue_entry") }) {
                    problems += "$name ($version): not led by $index: $lines"
                }
            }
        }
        // The plans were read at all.
        assertTrue(planned >= statements.size)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    // The build writes a row by number with an update and an insert that
    // leaves a row already there: both prepare on 3.21, which has no UPSERT.
    @Test
    fun theBuildsWritesPrepareOnTheOldest() {
        OldSqlite.open().use { conn ->
            conn.prepareStatement(QUEUE_BUILD_UPDATE_SQL).close()
            conn.prepareStatement(QUEUE_BUILD_INSERT_SQL).close()
        }
    }
}

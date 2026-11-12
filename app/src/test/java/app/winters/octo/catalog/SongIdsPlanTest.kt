package app.winters.octo.catalog

import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.OldSqlite
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The reads of ids as they are now, and the reads that show a song's
// hearts, stars, favorites and pins through either id, each seek by an
// index on SQLite 3.21 and a current one: no sort or temporary index, and
// no walk of a song-sized table. The renames are read by their key or by
// the index on the new id.
@RunWith(RobolectricTestRunner::class)
class SongIdsPlanTest {
    private val statements = mapOf(
        "current id" to CURRENT_ID_SQL,
        "current row id" to CURRENT_ROW_ID_SQL,
        "song renames of" to SONG_RENAMES_OF_SQL,
        "renamed to" to RENAMED_TO_SQL,
        "songs renamed to" to SONGS_RENAMED_TO_SQL,
        "liked rows" to "SELECT * FROM liked_track WHERE trackId IN (:trackIds)",
        "liked among" to LIKED_AMONG_SQL,
        "liked either" to LIKED_EITHER_SQL,
        "rating either" to RATING_EITHER_SQL,
        "favorite albums" to FAVOURITE_ALBUM_IDS_SQL,
        "favorite artists" to FAVOURITE_ARTIST_IDS_SQL,
        "shown pins" to SHOWN_PINS_SQL,
        "unlike old" to "DELETE FROM liked_track WHERE trackId = ? AND (? = 0 OR relinkKey = ?)",
        "unrate old" to "DELETE FROM track_rating WHERE trackId = ? AND (? = 0 OR relinkKey = ?)",
        "old rating shown" to "UPDATE track SET rating = ? WHERE id = ? AND (? = 0 OR relinkKey = ?)",
    )

    // Each must read save_rename through its key or the new id's index.
    private val renames = Regex("""save_rename""")
    private val renameIndex = Regex("""sqlite_autoindex_save_rename_1|index_save_rename_kind_new""")
    private val songSized = Regex("""\b(track|liked_track|track_rating|play_event|download|playlist_item|online_song)\b""")

    private fun positional(sql: String): String =
        sql.replace(Regex("""IN \(:\w+\)"""), "IN (?, ?, ?)").replace(Regex(""":\w+"""), "?")

    private fun plans(sql: String): List<List<String>> {
        val text = positional(sql)
        val args = List(text.count { it == '?' }) { null }
        return listOf(OldSqlite.open().use { conn -> OldSqlite.plan(conn, text, args) }, CurrentSqlite.plan(text, args))
    }

    // Home's pins sort by their place as today's read of them does: the
    // table holds a row a pin, a few at most, and has no index on the place.
    private val pinSort = setOf("shown pins")

    @Test
    fun everyIdReadSeeksByAnIndex() {
        val problems = mutableListOf<String>()
        for ((name, sql) in statements) {
            for (lines in plans(sql)) {
                assertTrue("$name: no plan", lines.isNotEmpty())
                for (line in lines) {
                    val wholeScan = line.trimStart().startsWith("SCAN") && "USING" !in line && songSized.containsMatchIn(line)
                    val renameScan = renames.containsMatchIn(line) && line.trimStart().startsWith("SCAN") && "USING" !in line
                    val sort = "TEMP B-TREE" in line && !(name in pinSort && "ORDER BY" in line)
                    if (sort || "AUTOMATIC" in line || wholeScan || renameScan) problems += "$name: $line"
                }
                if (renames.containsMatchIn(sql) && lines.none { renameIndex.containsMatchIn(it) }) problems += "$name: save_rename by no index: $lines"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}

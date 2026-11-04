package app.winters.octo.catalog

import app.winters.octo.listening.COMPLETE_STARRED_SQL
import app.winters.octo.listening.COPY_PAGE_SQL
import app.winters.octo.listening.DROP_HELD_SQL
import app.winters.octo.listening.DROP_UNKNOWN_RECENT_SQL
import app.winters.octo.listening.DROP_UNKNOWN_SENT_SQL
import app.winters.octo.listening.FOLD_HELD_SQL
import app.winters.octo.listening.HAS_COPIES_SQL
import app.winters.octo.listening.LIKED_AMONG_SQL
import app.winters.octo.listening.LINKS_AMONG_SQL
import app.winters.octo.listening.NEXT_GENERATION_SQL
import app.winters.octo.listening.PENDING_SQL
import app.winters.octo.listening.PHONE_RATINGS_AMONG_SQL
import app.winters.octo.listening.RATING_PAGE_SQL
import app.winters.octo.listening.SENT_ROWS_SQL
import app.winters.octo.listening.STARRED_AMONG_SQL
import app.winters.octo.listening.STARRED_OF_SQL
import app.winters.octo.listening.SYNCED_RATINGS_AMONG_SQL
import app.winters.octo.listening.SYNCED_STARS_AMONG_SQL
import app.winters.octo.listening.TRIM_PENDING_SQL
import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.OldSqlite
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Every statement the steps after a copy run over song-sized tables is led
// by an index on SQLite 3.21, the oldest the app runs on, and on a current
// one: no sort or temporary index in memory, and no walk of a whole table.
@RunWith(RobolectricTestRunner::class)
class AfterSyncQueryPlanTest {
    private val statements = mapOf(
        "copy page" to COPY_PAGE_SQL,
        "rating page" to RATING_PAGE_SQL,
        "links among" to LINKS_AMONG_SQL,
        "has copies" to HAS_COPIES_SQL,
        "liked among" to LIKED_AMONG_SQL,
        "phone ratings among" to PHONE_RATINGS_AMONG_SQL,
        "starred among" to STARRED_AMONG_SQL,
        "synced stars among" to SYNCED_STARS_AMONG_SQL,
        "synced ratings among" to SYNCED_RATINGS_AMONG_SQL,
        "next generation" to NEXT_GENERATION_SQL,
        "complete starred" to COMPLETE_STARRED_SQL,
        "starred of" to STARRED_OF_SQL,
        "drop unknown recent" to DROP_UNKNOWN_RECENT_SQL,
        "drop unknown sent" to DROP_UNKNOWN_SENT_SQL,
        "fold held" to FOLD_HELD_SQL,
        "drop held" to DROP_HELD_SQL,
        "sent rows" to SENT_ROWS_SQL,
        "pending" to PENDING_SQL,
        "trim pending" to TRIM_PENDING_SQL,
        "album copy page" to ALBUM_COPY_PAGE_SQL,
        "album library after" to ALBUM_LIBRARY_AFTER_SQL,
        "album songs in" to ALBUM_SONGS_IN_SQL,
        "artist copy page" to ARTIST_COPY_PAGE_SQL,
        "artist library after" to ARTIST_LIBRARY_AFTER_SQL,
        "artist songs in" to ARTIST_SONGS_IN_SQL,
        "tracks with key page" to TRACKS_WITH_KEY_PAGE_SQL,
    )

    // The list a subquery builds here holds only the plays over the cap.
    private val listAllowed = setOf("trim pending")

    // A list built from a subquery grows with what it reads; one built from
    // bound values holds only the chunk the caller passed.
    private val listFromRows = Regex("""IN\s*\(\s*SELECT""", RegexOption.IGNORE_CASE)

    private val scanOfSongSized = Regex(
        """\bSCAN (TABLE )?(source_track|track|sync_starred|sent_play|sent_recent|pending_play|liked_track|track_rating|synced_star|synced_rating|source_album|source_artist)\b""",
    )

    // Room's named parameters as positional ones, a list as three.
    private fun positional(sql: String): String =
        sql.replace(Regex("""IN \(:\w+\)"""), "IN (?, ?, ?)").replace(Regex(""":\w+"""), "?")

    @Test
    fun everyAfterSyncStatementIsLedByAnIndex() {
        OldSqlite.open().use { conn ->
            val problems = mutableListOf<String>()
            for ((name, sql) in statements) {
                val text = positional(sql)
                val args = List(text.count { it == '?' }) { null }
                for (line in OldSqlite.plan(conn, text, args) + CurrentSqlite.plan(text, args)) {
                    val bad = "TEMP B-TREE" in line || "AUTOMATIC" in line || scanOfSongSized.containsMatchIn(line) ||
                        ("LIST SUBQUERY" in line && name !in listAllowed && listFromRows.containsMatchIn(sql))
                    if (bad) problems += "$name: $line"
                }
            }
            assertTrue(problems.joinToString("\n"), problems.isEmpty())
        }
    }
}

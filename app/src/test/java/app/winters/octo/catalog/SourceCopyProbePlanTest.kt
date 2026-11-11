package app.winters.octo.catalog

import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.OldSqlite
import app.winters.octo.testing.SongCopyRule
import app.winters.octo.ui.library.health.SPLIT_JOINABLE_SQL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.sql.Connection

// A song's copy on a server is looked for by the song. Several indexes on
// source_track start with the source, and given a source and a song both
// planners would take one of those and walk every copy the server has for
// each song asked about. Each of these statements reads source_track by
// index_source_track_mergedId alone, on SQLite 3.21 and on a current one;
// and so does every statement the app's code writes that looks a song's
// copies up by the song (SongCopyRule), however its lines are laid out.
@RunWith(RobolectricTestRunner::class)
class SourceCopyProbePlanTest {
    private val statements = mapOf(
        "a write-back page's copies" to COPIES_ON_SQL,
        "a kept playlist's songs only on the phone" to PHONE_ONLY_COUNT_SQL,
        "a split album's join" to SPLIT_JOINABLE_SQL,
    )

    // The plan's lines on both versions, every argument null.
    private fun plans(sql: String): List<String> {
        val text = sql.replace(Regex("""IN \(:\w+\)"""), "IN (?, ?, ?)").replace(Regex(""":\w+"""), "?")
        val numbered = Regex("""\?(\d+)""").findAll(text).map { it.groupValues[1].toInt() }.maxOrNull()
        val args = List(numbered ?: text.count { it == '?' }) { null }
        return OldSqlite.open().use { conn -> OldSqlite.plan(conn, text, args) } + CurrentSqlite.plan(text, args)
    }

    @Test
    fun aSongsCopiesAreFoundByTheSong() {
        val problems = mutableListOf<String>()
        for ((name, sql) in statements) {
            val lines = plans(sql)
            val copies = lines.filter { "index_source_track_" in it }
            val bySong = copies.count { "index_source_track_mergedId (mergedId=?)" in it }
            if (bySong < 2 || bySong != copies.size) problems += "$name: $lines"
            if (SongCopyRule.lookups(sql).isEmpty()) problems += "$name: the rule sees no lookup"
            problems += SongCopyRule.problems(sql, lines).map { "$name: $it" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    // A source's albums are looked up by the source's own ids on their own
    // index, never by a walk of every album the source has.
    @Test
    fun aSourcesAlbumsAreFoundByTheirIds() {
        for (sql in listOf(app.winters.octo.server.CopySql.KNOWN_ALBUMS, "SELECT nativeId AS serverId, songCount FROM source_album WHERE sourceId = :sourceId AND nativeId IN (:serverIds)")) {
            val lines = plans(sql)
            val reads = lines.filter { "source_album" in it }
            assertTrue("$lines", reads.size == 2 && reads.all { "index_source_album_sourceId_nativeId (sourceId=? AND nativeId=?)" in it })
        }
    }

    // The rule finds a lookup on any line of a statement, and only a song's.
    @Test
    fun theRuleReadsAStatementAsOneText() {
        val split = "SELECT id FROM source_track\n  WHERE sourceId = ?\n    AND mergedId\n      IN (?, ?)"
        assertEquals(setOf("source_track"), SongCopyRule.lookups(split))
        assertEquals(1, SongCopyRule.problems(split, listOf("SEARCH TABLE source_track USING INDEX index_source_track_sourceId_seq (sourceId=?)")).size)
        assertEquals(0, SongCopyRule.problems(split, listOf("SEARCH TABLE source_track USING INDEX index_source_track_mergedId (mergedId=?)")).size)
        val probe = "SELECT 1 FROM playlist_item i WHERE NOT EXISTS (SELECT 1 FROM source_track s\n WHERE s.mergedId = i.trackId AND +s.sourceId = ?)"
        assertEquals(setOf("s"), SongCopyRule.lookups(probe))
        assertEquals(1, SongCopyRule.problems(probe, listOf("SEARCH s USING INDEX index_source_track_folder (sourceId=?)")).size)
        // A walk of a source's copies, its merged ids set or checked for
        // none, is no lookup.
        assertEquals(emptySet<String>(), SongCopyRule.lookups("UPDATE source_track SET mergedId = ? WHERE sourceId = ? AND mergedId != ''"))
        assertEquals(emptySet<String>(), SongCopyRule.lookups("SELECT t.id FROM track t JOIN source_track s ON s.mergedId = t.id WHERE s.sourceId = ?"))
    }

    private val mainSources = File(System.getProperty("octo.mainSources")!!)

    // A statement as written made plannable: templates and named arguments
    // as marks, a list as three; null for a piece of a statement.
    private fun plannable(sql: String): String? {
        val text = sql.trim()
        if (!Regex("""^(SELECT|INSERT|UPDATE|DELETE|WITH|REPLACE)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        // A list's marks may come with their parentheses (ui/paging's).
        return text.replace("\${}", "?").replace("%s", "?, ?, ?")
            .replace(Regex("""\bIN \?, \?, \?"""), "IN (?, ?, ?)")
            .replace(Regex("""IN \(:\w+\)"""), "IN (?, ?, ?)").replace(Regex(""":\w+"""), "?")
    }

    private fun plansOn(conn: Connection, text: String): List<String> {
        val numbered = Regex("""\?(\d+)""").findAll(text).map { it.groupValues[1].toInt() }.maxOrNull()
        val args = List(numbered ?: text.count { it == '?' }) { null }
        return OldSqlite.plan(conn, text, args) + CurrentSqlite.plan(text, args)
    }

    // Every statement in the app's code that looks a song's copies up by the
    // song, read from the source files whatever lines it spans, planned on
    // both versions. A piece of a statement, or one that cannot be planned
    // as written, is named, so none goes unchecked.
    @Test
    fun everyLookupOfASongsCopiesIsLedByTheSong() {
        val problems = mutableListOf<String>()
        val unplanned = mutableListOf<String>()
        var checked = 0
        OldSqlite.open().use { conn ->
            for (file in mainSources.walkTopDown().filter { it.extension == "kt" }.sortedBy { it.path }) {
                for (sql in SongCopyRule.statementsIn(file)) {
                    if (SongCopyRule.lookups(sql).isEmpty()) continue
                    val text = plannable(sql)
                    val lines = text?.let { runCatching { plansOn(conn, it) }.getOrNull() }
                    if (text == null || lines == null) {
                        unplanned += "${file.name}: ${SongCopyRule.flat(sql)}"
                        continue
                    }
                    checked++
                    problems += SongCopyRule.problems(text, lines).map { "${file.name}: $it\n    ${SongCopyRule.flat(sql)}" }
                }
            }
        }
        assertTrue("only $checked lookups found", checked >= 15)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
        assertEquals("lookups not planned", emptyList<String>(), unplanned)
    }
}

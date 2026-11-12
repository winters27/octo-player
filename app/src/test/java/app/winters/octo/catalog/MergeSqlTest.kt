package app.winters.octo.catalog

import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.OldSqlite
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.sql.Connection

// The merge's statements on the oldest SQLite a phone can have (3.21, as
// sqlite-jdbc runs it): each runs from an index, with no sort or table
// built in memory.
@RunWith(RobolectricTestRunner::class)
class MergeSqlTest {
    private lateinit var conn: Connection

    @Before
    fun open() {
        conn = OldSqlite.open()
    }

    @After
    fun close() {
        conn.close()
    }

    private fun columns(table: String): List<String> =
        conn.createStatement().use { s -> s.executeQuery("PRAGMA table_info(`$table`)").use { r -> buildList { while (r.next()) add(r.getString("name")) } } }

    private fun assertIndexed(sql: String, args: List<Any?>, chunked: Boolean) {
        for (plan in listOf(OldSqlite.plan(conn, sql, args), CurrentSqlite.plan(sql, args))) {
            val text = plan.joinToString(" / ")
            assertFalse(text, plan.any { "TEMP B-TREE" in it || "AUTOMATIC" in it || "LIST SUBQUERY" in it })
            if (chunked) assertFalse(text, plan.any { it.trimStart().startsWith("SCAN") })
        }
    }

    // Phones before SQLite 3.32 take at most 999 bound values a statement,
    // and no engine a test can run keeps that limit, so the sizes lists are
    // cut to are read from the code: every chunk and page is 900 or fewer.
    @Test
    fun noListIsCutBiggerThanOldPhonesBind() {
        val root = java.io.File(System.getProperty("octo.mainSources")!!)
        val sizes = Regex("""\.chunked\((\d[\d_]*)\)|\b\w*(?:PAGE|CHUNK|BATCH)\w*\s*=\s*(\d[\d_]*)""")
        val tooBig = root.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            sizes.findAll(file.readText()).map { m ->
                val n = (m.groupValues[1].ifEmpty { m.groupValues[2] }).replace("_", "").toInt()
                "${file.name}: ${m.value}" to n
            }
        }.filter { it.second > 900 }.map { it.first }.toList()
        assertEquals(emptyList<String>(), tooBig)
    }

    @Test
    fun pageReadsFitsAndOrdersUseIndexes() {
        for (table in listOf("source_track", "stage_track")) assertIndexed(trackPageSql(table), listOf("s", 0, 0L, ""), chunked = true)
        for (table in listOf("source_album", "stage_album", "source_artist", "stage_artist")) assertIndexed(seqPageSql(table), listOf("s", 0L), chunked = true)
        assertIndexed(FIT_IN_ALBUM_SQL, listOf("a", "t", 1L, 2L), chunked = true)
        assertIndexed(FIT_ANYWHERE_SQL, listOf("m", "t", 1L, 2L), chunked = true)
        assertIndexed(BY_ARRIVAL_SQL, listOf("a", 0L), chunked = true)
        assertIndexed(BY_TRACK_NUMBER_SQL, listOf("a", 0L, 0L, 0L), chunked = true)
        assertIndexed(ADD_COPY_SQL, List(14) { 1 }, chunked = true)
        assertIndexed("SELECT id, artist, title FROM source_track WHERE keysVersion = ?1 AND id > ?2 ORDER BY id LIMIT 500", listOf(0, ""), chunked = true)
        assertIndexed("SELECT keysVersion FROM source_track WHERE keysVersion < ?1 LIMIT 1", listOf(1), chunked = true)
        assertIndexed("SELECT id, nativeId FROM source_track WHERE sourceId = ?1 AND nativeId > ?2 ORDER BY nativeId LIMIT 500", listOf("s", ""), chunked = true)
        assertIndexed("SELECT MAX(seq) FROM source_track WHERE sourceId = ?1", listOf("s"), chunked = true)
        assertIndexed("SELECT sourceId FROM source_track WHERE sourceId > ?1 ORDER BY sourceId LIMIT 1", listOf(""), chunked = true)
    }

    // Each of the apply's statements names its row by key: no scan, sort or
    // table built in memory.
    @Test
    fun swapStatementsSortNothing() {
        val sql = SwapSql(
            columns("source_track"), columns("source_album"), columns("source_artist"),
            columns("track"), columns("album"), columns("artist"),
        )
        assertTrue(sql.all.size > 10)
        for (statement in sql.all) {
            val count = Regex("""\?(\d+)""").findAll(statement).maxOf { it.groupValues[1].toInt() }
            assertIndexed(statement, List(count) { "x" }, chunked = true)
        }
    }

    // A save's pick of the lowest song under a relink key, over the merge's
    // output and over the library, as SaveFind.keyTarget runs it.
    @Test
    fun relinkPicksUseTheIdOrderedIndex() {
        for ((songs, index) in listOf("merge_candidate" to "index_merge_candidate_relinkKey_id", "track" to "index_track_relinkKey_id")) {
            val pick = "SELECT id FROM `$songs` WHERE relinkKey = ?1 ORDER BY id LIMIT 1"
            for (plan in listOf(OldSqlite.plan(conn, pick, listOf("k")), CurrentSqlite.plan(pick, listOf("k")))) {
                assertTrue(plan.joinToString(" / "), plan.any { index in it })
                assertFalse(plan.joinToString(" / "), plan.any { "TEMP B-TREE" in it })
            }
        }
    }

    @Test
    fun aFitLooksAtNoTakenSong() {
        var visits = 0
        org.sqlite.Function.create(
            conn, "visit",
            object : org.sqlite.Function() {
                override fun xFunc() {
                    visits++
                    result(1)
                }
            },
        )
        conn.prepareStatement(
            "INSERT INTO merge_candidate (${CANDIDATE_COLUMNS.joinToString()}) VALUES (${CANDIDATE_COLUMNS.joinToString { "?" }})",
        ).use { insert ->
            for (i in 0 until 10_000) {
                val untaken = i % 1_000 == 999
                val values = listOf<Any?>(
                    "c$i", "device", "$i", "Untitled", "untitled", "untitled", "A", "artist", "Album", "album",
                    null, null, null, if (untaken && i != 9_999) 999_000L else 200_000L, 0L, null, null, null, null, 0,
                    "", "", 1, 0, null, "", "", UNTITLED.lookupKey, UNTITLED.looseKey,
                    i.toLong(), "untitled", "a|untitled", null, 1, 1, if (untaken) 0 else 1, 0, 0,
                )
                values.forEachIndexed { at, value -> insert.setObject(at + 1, value) }
                insert.addBatch()
            }
            insert.executeBatch()
        }
        val counted = FIT_IN_ALBUM_SQL.replace("lastSourceRank = 0", "lastSourceRank = 0 AND visit() = 1")
        repeat(10) {
            visits = 0
            conn.prepareStatement(counted).use { s ->
                listOf<Any?>("album", "untitled", 199_000L, 201_000L).forEachIndexed { at, v -> s.setObject(at + 1, v) }
                s.executeQuery().use { r -> assertTrue(r.next()); assertEquals("c9999", r.getString(1)) }
            }
            assertTrue("visited $visits", visits <= 10 + 1)
        }
    }

    @Test
    fun workingTablesCarryEveryColumnOfWhatTheyStandFor() = runBlocking {
        val db = openTestCatalog()
        try {
            val sql = db.openHelper.writableDatabase
            assertEquals(columnsOf(sql, "source_track").toSet(), columnsOf(sql, "stage_track").toSet())
            assertEquals(columnsOf(sql, "source_album").toSet(), columnsOf(sql, "stage_album").toSet())
            assertEquals(columnsOf(sql, "source_artist").toSet(), columnsOf(sql, "stage_artist").toSet())
            assertTrue(columnsOf(sql, "merge_candidate").containsAll(columnsOf(sql, "track")))
            assertEquals(CANDIDATE_COLUMNS.toSet(), columnsOf(sql, "merge_candidate").toSet())
            assertEquals(columnsOf(sql, "album").toSet(), columnsOf(sql, "album_next").toSet())
            assertEquals(columnsOf(sql, "artist").toSet(), columnsOf(sql, "artist_next").toSet())
        } finally {
            db.close()
        }
    }

    // A save relinks a like whose song is gone to the lowest song under its
    // key: of two library songs filed under it, the phone's.
    @Test
    fun aRelinkPrefersThePhonesCopyOnATie() = runBlocking {
        val db = openTestCatalog()
        try {
            val server = "server:x"
            db.put(SourceCatalog(app.winters.octo.device.DEVICE, true, listOf(song("device", "1", "Tee", "al").copy(relinkKey = "same")), listOf(albumRow("device", "al")), listOf(artistRow("device"))))
            db.put(SourceCatalog(server, false, listOf(song(server, "1", "Other", "al").copy(relinkKey = "same")), listOf(albumRow(server, "al")), listOf(artistRow(server))))
            db.exec("INSERT INTO liked_track (trackId, relinkKey, likedAt) VALUES ('gone', 'same', 1)")
            db.testMerge(server).rebuild()
            assertEquals(setOf("device:1", "server:x:1"), db.query("SELECT id FROM track", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } })
            assertEquals(listOf("device:1"), db.query("SELECT trackId FROM liked_track", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } })
        } finally {
            db.close()
        }
    }

    private companion object {
        // The lookup keys of the candidates written here, all "Untitled" by "A".
        val UNTITLED = titleLookup("Untitled", "A")
    }
}

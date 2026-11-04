package app.winters.octo.catalog

import app.winters.octo.testing.OldSqlite
import app.winters.octo.testing.openTestCatalog
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
        val plan = OldSqlite.plan(conn, sql, args)
        val text = plan.joinToString(" / ")
        assertFalse(text, plan.any { "TEMP B-TREE" in it || "AUTOMATIC" in it || "LIST SUBQUERY" in it })
        if (chunked) assertFalse(text, plan.any { it.trimStart().startsWith("SCAN") })
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

    @Test
    fun swapStatementsSortNothing() {
        val sql = SwapSql(
            columns("source_track"), columns("source_album"), columns("source_artist"),
            columns("track"), columns("album"), columns("artist"),
        )
        sql.applyStage.forEach { assertIndexed(it, listOf("s", 1L), chunked = false) }
        sql.applyLibrary.forEach { assertIndexed(it, emptyList(), chunked = false) }
        assertIndexed(sql.mergedIds, listOf("s"), chunked = false)
    }

    @Test
    fun relinkPicksUseTheIdOrderedIndex() {
        // As UserDao.relinkLikes runs it.
        val relink = """
            UPDATE OR IGNORE liked_track
            SET trackId = (SELECT t.id FROM track t WHERE t.relinkKey = liked_track.relinkKey ORDER BY t.id LIMIT 1)
            WHERE trackId NOT IN (SELECT id FROM track)
              AND EXISTS (SELECT 1 FROM track t WHERE t.relinkKey = liked_track.relinkKey AND t.relinkKey != '')
        """.trimIndent()
        val plan = OldSqlite.plan(conn, relink, emptyList())
        assertTrue(plan.joinToString(" / "), plan.any { "index_track_relinkKey_id" in it })
        assertFalse(plan.joinToString(" / "), plan.any { "TEMP B-TREE" in it })
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
                    "", "", 1, 0, null,
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

    @Test
    fun aRelinkPrefersThePhonesCopyOnATie() = runBlocking {
        val db = openTestCatalog()
        try {
            db.exec("INSERT INTO track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, relinkKey) VALUES ('server:x:1', 'server:x', '1', 'T', 't', 't', 'A', 'a', 'B', 'b', 1, 0, 'same')")
            db.exec("INSERT INTO track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, relinkKey) VALUES ('device:1', 'device', '1', 'T', 't', 't', 'A', 'a', 'B', 'b', 1, 0, 'same')")
            db.exec("INSERT INTO liked_track (trackId, relinkKey, likedAt) VALUES ('gone', 'same', 1)")
            db.userDao().relinkAll()
            assertTrue(db.userDao().isLiked("device:1"))
        } finally {
            db.close()
        }
    }
}

package app.winters.octo.server

import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.OldSqlite
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The copy's statements over song-sized tables, planned on the oldest
// SQLite the app runs on and on a current one: each walks an index, sorts
// nothing in memory and makes no index of its own.
@RunWith(RobolectricTestRunner::class)
class CopyQueryPlanTest {
    private val conn = OldSqlite.open()

    @After
    fun close() = conn.close()

    private fun list(sql: String, name: String, n: Int) = sql.replace("(:$name)", (1..n).joinToString(",", "(", ")") { "?" })

    private fun assertIndexed(sql: String, args: List<Any?>, scanAllowed: Boolean = false) {
        for (plan in listOf(OldSqlite.plan(conn, sql, args), CurrentSqlite.plan(sql, args))) {
            assertTrue("$sql\n$plan", plan.none { "TEMP B-TREE" in it || "AUTOMATIC" in it })
            // A select of values alone reads its one row of constants.
            if (!scanAllowed) assertTrue("$sql\n$plan", plan.none { it.trimStart().startsWith("SCAN") && "CONSTANT ROW" !in it })
        }
    }

    @Test
    fun readsOfTheServersPagesWalkIndexes() {
        assertIndexed(CopySql.SONG_ALBUM_KEYS, listOf(0L, 2_000))
        assertIndexed(CopySql.ALBUM_SONG_COUNT, listOf("al1"))
        assertIndexed(CopySql.ALBUM_SONGS, listOf("al1"))
        assertIndexed(CopySql.ALBUM_SONG_PAGE, listOf("al1", 0L, 500))
        assertIndexed(list(CopySql.SONGS_BY_IDS, "ids", 500), List(500) { "s$it" })
        assertIndexed(list(CopySql.DELETE_ALBUM_SONGS, "keys", 500), List(500) { "al$it" })
        assertIndexed(CopySql.SYNC_ALBUM, listOf("al1"))
        assertIndexed(CopySql.SYNC_ALBUM_PAGE, listOf(-1L, 500))
        assertIndexed(CopySql.SYNC_ARTIST_PAGE, listOf(-1L, 500))
    }

    @Test
    fun aGiantAlbumsOrderIsReadByKeyset() {
        assertIndexed(CopySql.FIRST_ORDERED, listOf("server:h#1", 500))
        assertIndexed(CopySql.ORDERED_AFTER, listOf("server:h#1", 1, 3, "0061", 17L, 500))
        // A leftover cleanup over a table that holds one album at most.
        assertIndexed(CopySql.DELETE_SYNC_ORDER_OF, listOf("server:h#"), scanAllowed = true)
    }

    @Test
    fun stageAndSourceStatementsWalkIndexes() {
        assertIndexed(CopySql.CLEAR_STAGE_TRACKS, listOf("server:h"))
        assertIndexed(CopySql.CLEAR_STAGE_ALBUMS, listOf("server:h"))
        assertIndexed(CopySql.CLEAR_STAGE_ARTISTS, listOf("server:h"))
        assertIndexed(list(CopySql.KNOWN_TRACKS, "ids", 900), listOf<Any?>("server:h") + List(900) { "s$it" })
        assertIndexed(list(CopySql.KNOWN_ALBUMS, "ids", 900), listOf<Any?>("server:h") + List(900) { "al$it" })
        assertIndexed(CopySql.MAX_TRACK_SEQ, listOf("server:h"))
        assertIndexed(CopySql.MAX_ALBUM_SEQ, listOf("server:h"))
        assertIndexed(CopySql.MAX_ARTIST_SEQ, listOf("server:h"))
        assertIndexed(CopySql.LIVE_TRACKS, listOf("server:h"))
        assertIndexed(CopySql.LIVE_ALBUMS, listOf("server:h"))
        assertIndexed(CopySql.COPY_STATE, listOf("server:h", "device"))
        // source_meta holds a row per source.
        assertIndexed(CopySql.LAST_GENERATION, emptyList(), scanAllowed = true)
    }
}

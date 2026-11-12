package app.winters.octo.catalog

import androidx.room.withTransaction
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Each found row takes the set of the id it moves with (FOUND_SET_SQL): a
// song's own row, its copies' source rows and merged ids and its totals
// row share the song's set; an album's and an artist's rows share theirs.
// Two of each, in sets of their own, so a row that took the first set of
// its kind would show.
@RunWith(RobolectricTestRunner::class)
class SaveSetIdsTest {
    @Test
    fun aSongsAnAlbumsAndAnArtistsRowsShareTheirSet() = runBlocking {
        val db = openTestCatalog()
        try {
            val numbers = SaveNodeNumbers(0)
            val ids = listOf(RENAME_SONG to "song:a", RENAME_SONG to "song:b", RENAME_ALBUM to "album:a", RENAME_ALBUM to "album:b", RENAME_ARTIST to "artist:a", RENAME_ARTIST to "artist:b")
            db.withTransaction { SaveSets(db.openHelper.writableDatabase, numbers).use { s -> ids.forEach { (kind, id) -> s.touch(SaveNodeKey(kind, id)) } } }
            val setOf = db.withTransaction { SaveSets(db.openHelper.writableDatabase, numbers).use { s -> ids.associate { (kind, id) -> id to s.find(SaveNodeKey(kind, id))!! } } }
            assertEquals("each id a set of its own", ids.size, setOf.values.toSet().size)
            // Each id's rows: (tbl, op, id, oldId, newId).
            val rows = listOf("song:a", "song:b").flatMap { song ->
                listOf(
                    listOf("track", FOUND_CHANGED, song, null, null),
                    listOf("source_track", FOUND_CHANGED, "copy:$song", song, null),
                    listOf("source_track", FOUND_NEW, "new:$song", null, song),
                    listOf(FOUND_MERGED_ID, FOUND_CHANGED, "new:$song", "", song),
                    listOf(FOUND_UNMERGE, FOUND_CHANGED, "out:$song", song, null),
                    listOf(FOUND_TOTALS, FOUND_CHANGED, song, null, null),
                ).map { song to it }
            } + listOf("album:a", "album:b").map { it to listOf("album", FOUND_CHANGED, it, null, null) } +
                listOf("artist:a", "artist:b").map { it to listOf("artist", FOUND_CHANGED, it, null, null) }
            for ((_, row) in rows) {
                db.exec("INSERT INTO save_found (tbl, op, id, oldId, newId, cost) VALUES (?, ?, ?, ?, ?, 1)", *row.toTypedArray<Any?>())
            }
            db.exec(FOUND_SET_SQL, Long.MIN_VALUE, Long.MAX_VALUE)
            val got = db.query("SELECT tbl, id, setId FROM save_found ORDER BY seq", null).use { c ->
                buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getLong(2))) }
            }
            assertEquals(rows.size, got.size)
            for ((i, owner) in rows.map { it.first }.withIndex()) {
                val (tbl, id, set) = got[i]
                assertEquals("$tbl $id takes the set of $owner", setOf.getValue(owner), set)
            }
        } finally {
            db.close()
        }
    }
}

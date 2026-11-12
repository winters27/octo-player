package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The tests' stand-in for a save's rename (renameLikeASave) leaves what a
// real save leaves: on the same library and the same rows, a real save that
// renames a song (the server's copy joined by the phone's) and the stand-in
// give the same renames in force, the same rows on the song's ids and the
// same swap count.
@RunWith(RobolectricTestRunner::class)
class RenameLikeASaveTest {
    private val server = "server:music.example.net:4533"
    private val old = "$server:s0"
    private val new = "$DEVICE:s0"

    // A library of one server song, and rows of every kind on it.
    private suspend fun start(): CatalogDatabase {
        val db = openTestCatalog()
        db.put(library(server, 1))
        db.testMerge(server).rebuild()
        val key = db.stringOf("SELECT relinkKey FROM track WHERE id = ?", old)!!
        db.userDao().like(LikedTrackEntity(old, key, 1))
        db.userDao().rate(TrackRatingEntity(old, key, 4, 1))
        db.userDao().addPlay(PlayEventEntity(trackId = old, relinkKey = key, startedAt = 1, playedMs = 1, durationMs = 1))
        insertRow(db, "playlist", mapOf("id" to "p", "name" to "P", "createdAt" to 1, "updatedAt" to 1))
        insertRow(db, "playlist_item", mapOf("playlistId" to "p", "itemSet" to 0, "trackId" to old, "relinkKey" to key, "ord" to 0, "itemKey" to 0))
        insertRow(db, "queue_entry", mapOf("queueId" to 1, "entry" to 1, "trackId" to old, "listId" to 1))
        insertRow(db, "server_entry", mapOf("owner" to WorkOwners.marked("vm:1"), "position" to 0, "trackId" to old, "relinkKey" to key))
        return db
    }

    private fun CatalogDatabase.rows(table: String, columns: String): List<String> =
        query("SELECT $columns FROM `$table` ORDER BY $columns", null).use { c -> buildList { while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { c.getString(it) ?: "-" }) } }

    private fun CatalogDatabase.state(): List<String> =
        rows("save_rename", "kind, `old`, `new`, byKey, `key`").filter { it.startsWith("$RENAME_SONG|") } +
            rows("liked_track", "trackId, relinkKey") + rows("track_rating", "trackId, relinkKey, rating") +
            rows("play_event", "trackId, relinkKey, startedAt") + rows("playlist_item", "trackId, relinkKey, ord") +
            rows("queue_entry", "queueId, entry, trackId") + rows("server_entry", "owner, position, trackId")

    @Test
    fun theStandInLeavesWhatASaveLeaves() = guarded(60) {
        val saved = start()
        val stood = start()
        try {
            val before = saved.workDao().swapCount()
            saved.put(library(DEVICE, 1))
            saved.testMerge(server).rebuild()
            val raised = saved.workDao().swapCount() - before
            assertTrue("the save renamed nothing", saved.rows("save_rename", "`old`").contains(old))

            val standBefore = stood.workDao().swapCount()
            stood.renameLikeASave(old, new)
            assertEquals(saved.state(), stood.state())
            assertEquals(raised, stood.workDao().swapCount() - standBefore)
        } finally {
            saved.close()
            stood.close()
        }
    }
}

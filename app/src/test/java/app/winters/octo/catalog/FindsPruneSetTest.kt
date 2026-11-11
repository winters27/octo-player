package app.winters.octo.catalog

import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A find is let go of only when nothing needs it. A find held only by a
// playlist's item set still being written, not yet shown, survives a
// prune; once that set is shown and the old one deleted, a find in
// neither goes. A find the queue holds survives, in either table a queue
// is kept in; so does one asked for, one seen lately, and one of another
// kept server, while a server no longer kept loses its finds.
@RunWith(RobolectricTestRunner::class)
class FindsPruneSetTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private suspend fun find(id: String, sourceId: String = "server:h", seenAt: Long = 0, requestedAt: Long = 0) =
        db.onlineDao().insert(
            listOf(
                OnlineSongEntity(
                    id = id, sourceId = sourceId, nativeId = id.removePrefix(FIND_PREFIX), title = id, artist = "", album = "",
                    albumId = null, artistId = null, durationMs = 0, coverId = null, mimeType = null, bitrate = null, seenAt = seenAt,
                    requestedAt = requestedAt,
                ),
            ),
        )

    private fun finds(): Set<String> = db.query("SELECT id FROM online_song", null).use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(0)) }
    }

    private fun row(playlistId: String, itemSet: Long, trackId: String, key: Long) = db.exec(
        "INSERT INTO playlist_item (playlistId, itemSet, trackId, relinkKey, ord, itemKey) VALUES (?, ?, ?, '', ?, ?)",
        playlistId, itemSet, trackId, key, key,
    )

    private suspend fun prune() = db.onlineDao().prune("server:h", listOf("server:h", "server:k"), before = 10)

    @Test
    fun aFindInASetNotYetShownSurvives() = timed {
        db.userDao().insertPlaylist(PlaylistEntity("p", "List", createdAt = 1, updatedAt = 1))
        row("p", 0, "find:shown", 1)
        row("p", 1, "find:coming", 2)
        find("find:shown")
        find("find:coming")
        find("find:none")
        prune()
        assertEquals(setOf("find:shown", "find:coming"), finds())

        // The new set is shown and the old one deleted.
        db.exec("UPDATE playlist SET itemSet = 1 WHERE id = 'p'")
        db.exec("DELETE FROM playlist_item WHERE playlistId = 'p' AND itemSet = 0")
        prune()
        assertEquals(setOf("find:coming"), finds())
    }

    @Test
    fun aFindTheQueueHoldsSurvives() = timed {
        // The queue lives in queue_entry alone since the upgrade moved it there.
        db.exec("INSERT INTO queue_entry (queueId, entry, trackId, listId, qk, sk, durationMs) VALUES (1, 1, 'find:queued', 1, 1, 1, 0)")
        find("find:queued")
        find("find:none")
        prune()
        assertEquals(setOf("find:queued"), finds())

        db.exec("DELETE FROM queue_entry")
        prune()
        assertEquals(emptySet<String>(), finds())
    }

    @Test
    fun aFindNeededForAnotherReasonSurvives() = timed {
        find("find:asked", requestedAt = 5)
        find("find:lately", seenAt = 20)
        find("find:kept", sourceId = "server:k")
        find("find:dropped", sourceId = "server:gone", seenAt = 20, requestedAt = 5)
        find("find:none")
        prune()
        assertEquals(setOf("find:asked", "find:lately", "find:kept"), finds())
    }
}

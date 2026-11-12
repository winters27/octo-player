package app.winters.octo.catalog

import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A find of this server whose library song is gone is let go of: it is
// neither asked for nor arrived. A find whose song is still there, a find
// not yet arrived, and a find of another server keep what they had.
@RunWith(RobolectricTestRunner::class)
class FindsForgetGoneTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private fun track(id: String) = TrackEntity(
        id = id, sourceId = "server:h", nativeId = id, title = id, searchKey = id, sortKey = id,
        artist = "", artistId = "", album = "a", albumId = "a", trackNo = null, discNo = null, year = null,
        durationMs = 0, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null, relinkKey = "k$id",
    )

    private suspend fun find(id: String, adoptedId: String, sourceId: String = "server:h") =
        db.onlineDao().insert(
            listOf(
                OnlineSongEntity(
                    id = id, sourceId = sourceId, nativeId = id.removePrefix(FIND_PREFIX), title = id, artist = "", album = "",
                    albumId = null, artistId = null, durationMs = 0, coverId = null, mimeType = null, bitrate = null, seenAt = 0,
                    requestedAt = 5, adoptedId = adoptedId,
                ),
            ),
        )

    private fun state(): Map<String, Pair<Long, String>> =
        db.query("SELECT id, requestedAt, adoptedId FROM online_song", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1) to c.getString(2)) }
        }

    @Test
    fun onlyAFindWhoseSongIsGoneIsForgotten() = timed {
        db.dao().insertTracks(listOf(track("t1")))
        find("find:kept", adoptedId = "t1")
        find("find:gone", adoptedId = "t2")
        find("find:waiting", adoptedId = "")
        find("find:other", adoptedId = "t3", sourceId = "server:k")

        db.onlineDao().forgetGone("server:h")

        assertEquals(
            mapOf(
                "find:kept" to (5L to "t1"),
                "find:gone" to (0L to ""),
                "find:waiting" to (5L to ""),
                "find:other" to (5L to "t3"),
            ),
            state(),
        )
    }
}

package app.winters.octo.catalog

import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A playlist row taken out while a walk had its item set pinned lives on in
// the set's edit log, which a walk still reading the set as it stood will
// queue: a find only the log names survives a prune, and goes once the
// log lets it go.
@RunWith(RobolectricTestRunner::class)
class EditLogRelinkAndPruneTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private suspend fun find(id: String) = db.onlineDao().insert(
        listOf(
            OnlineSongEntity(
                id = id, sourceId = "server:h", nativeId = id.removePrefix(FIND_PREFIX), title = id, artist = "", album = "",
                albumId = null, artistId = null, durationMs = 0, coverId = null, mimeType = null, bitrate = null, seenAt = 0,
            ),
        ),
    )

    private fun finds(): Set<String> = db.query("SELECT id FROM online_song", null).use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(0)) }
    }

    @Test
    fun aFindOnlyTheEditLogNamesSurvivesAPrune() = timed {
        db.exec(
            "INSERT INTO playlist_edit_log (playlistId, itemSet, op, itemKey, ord, trackId, relinkKey, serverSongId) " +
                "VALUES ('p', 0, 1, 7, 0, 'find:removed', '', NULL)",
        )
        find("find:removed")
        find("find:none")
        db.onlineDao().prune("server:h", listOf("server:h"), before = 10)
        assertEquals(setOf("find:removed"), finds())

        db.exec("DELETE FROM playlist_edit_log")
        db.onlineDao().prune("server:h", listOf("server:h"), before = 10)
        assertEquals(emptySet<String>(), finds())
    }

    private fun track(id: String, relinkKey: String) = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = id, searchKey = id, sortKey = id, artist = "", artistId = "", album = "",
        albumId = "", trackNo = null, discNo = null, year = null, durationMs = 0, addedAt = 0, mimeType = null, sizeBytes = null,
        artwork = null, uri = null, relinkKey = relinkKey,
    )

    // Mid-walk, a removed row's song is renamed by a save's renames (by
    // its relink key, and as a server copy merged into a library song),
    // and a removed find is pruned: the walk reads the removed rows with
    // their songs as the library has them now, and the find is still there.
    @Test
    fun aRemovedRowFollowsTheLibraryAndItsFindStays() = timed {
        db.dao().insertTracks(listOf(track("t:old", "rk"), track("t:keep", "keep"), track("t:merged", "m")))
        db.sourceDao().putTracks(
            listOf(
                SourceTrackEntity(
                    id = "server:h:s1", sourceId = "server:h", nativeId = "s1", title = "s1", searchKey = "s1", sortKey = "s1", artist = "",
                    artistId = "", album = "", albumId = "", trackNo = null, discNo = null, year = null, durationMs = 0, addedAt = 0,
                    mimeType = null, sizeBytes = null, artwork = null, uri = null, albumOrder = 0, relinkKey = "", genre = "", mergedId = "t:merged",
                ),
            ),
        )
        find("find:removed")
        val sets = PlaylistSets(db)
        sets.create(PlaylistEntity("p", "P", 1, 1)) { emit ->
            emit(listOf(NewItem("t:keep", "keep"), NewItem("t:old", "rk"), NewItem("find:removed", ""), NewItem("server:h:s1", "")))
        }
        val edits = Edits(db, sets, "p")
        val set = sets.pin(WorkOwners.marked("queue:1"), "p")!!
        val walked = sets.walked(set, 1) { page ->
            if (page != 1) return@walked
            val rows = edits.rows()
            rows.drop(1).forEach { edits.remove(it.itemKey) }
            db.onlineDao().prune("server:h", listOf("server:h"), before = 10)
            // The save: t:old is now t:new, by its relink key, and the
            // server copy's id follows the song it merged into.
            db.exec("DELETE FROM track WHERE id = 't:old'")
            db.dao().insertTracks(listOf(track("t:new", "rk")))
            db.renameLikeASave("t:old", "t:new", key = "rk", raise = false)
            db.renameLikeASave("server:h:s1", "t:merged", raise = false)
        }
        assertEquals(listOf("t:keep", "t:new", "find:removed", "t:merged"), walked)
        assertEquals(setOf("find:removed"), finds())
        sets.releasePin(WorkOwners.marked("queue:1"), "p")
    }
}

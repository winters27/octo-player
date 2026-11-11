package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.testing.openTestCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Ratings through a save: a song renamed and re-rated by its copy in the
// same sync shows the copy's rating under its new id; a rating written
// after the find (from the find to the first group) or tapped between two
// groups stands over what the find saw, on the song and on its server
// copies; a rating tapped since the claim (a rating note) shows over the
// copy's.
@RunWith(RobolectricTestRunner::class)
class SaveRatingTest {
    private val server = "server:rating.test"

    private fun rating(db: CatalogDatabase, id: String) = db.longOf("SELECT rating FROM track WHERE id = ?", id)

    private fun copyRating(db: CatalogDatabase, id: String) = db.longOf("SELECT COALESCE(rating, 0) FROM source_track WHERE id = ?", id)

    @Test
    fun aRenameAndARerateBySyncShowTheCopysRating() = guarded {
        val db = openTestCatalog()
        try {
            val copy = library(server, 10).let { c -> c.copy(tracks = c.tracks.map { if (it.nativeId == "s4") it.copy(rating = 3) else it }) }
            db.put(copy)
            rebuildSettled(db, server)
            assertEquals(3L, rating(db, "$server:s4"))
            // The phone gets the song (its id becomes the phone's) and the
            // server's next copy rates it 5.
            db.put(library(DEVICE, 5).let { it.copy(tracks = it.tracks.filter { t -> t.nativeId == "s4" }, albums = it.albums.take(1)) })
            db.stage(copy.copy(tracks = copy.tracks.map { if (it.nativeId == "s4") it.copy(rating = 5) else it }), generation = 2)
            db.boundMerge(server, SAVE_GROUP_FLOOR).rebuild()
            assertEquals(5L, rating(db, "$DEVICE:s4"))
            assertEquals(5L, copyRating(db, "$server:s4"))
        } finally {
            db.close()
        }
    }

    @Test
    fun aRatingAfterTheFindAndATapBetweenGroupsStand() = guarded {
        val db = openTestCatalog()
        try {
            val copy = library(server, 30)
            db.put(copy)
            rebuildSettled(db, server)
            // The next copy rates every song 2 and retitles some, so the save
            // rewrites their rows in many groups.
            db.stage(copy.copy(tracks = copy.tracks.map { it.copy(rating = 2, title = it.title + " (Live)") }), generation = 2)
            val hooks = object : GroupHooks() {
                override suspend fun checking() {
                    // After the find, before the first group.
                    db.userDao().noteTap("$server:s29", 4, "drake|song 29", 1)
                }
            }
            hooks.between = { turn -> if (turn == 1L) db.userDao().noteTap("$server:s28", 5, "drake|song 28", 2) }
            db.boundMerge(server, SAVE_GROUP_FLOOR, hooks).rebuild()
            assertTrue("one group only", hooks.saves.single() > 2)
            assertEquals("a rating after the find stands", 4L, rating(db, "$server:s29"))
            assertEquals(4L, copyRating(db, "$server:s29"))
            assertEquals("a tap between groups stands", 5L, rating(db, "$server:s28"))
            assertEquals(5L, copyRating(db, "$server:s28"))
            assertEquals("the copy's rating elsewhere", 2L, rating(db, "$server:s3"))
            assertEquals("Song 28 (Live)", db.stringOf("SELECT title FROM track WHERE id = ?", "$server:s28"))
        } finally {
            db.close()
        }
    }

    @Test
    fun aTapSinceTheClaimShowsOverTheCopys() = guarded {
        val db = openTestCatalog()
        try {
            val copy = library(server, 8)
            db.put(copy)
            rebuildSettled(db, server)
            db.stage(copy.copy(tracks = copy.tracks.map { it.copy(rating = 1) }), generation = 2)
            db.exec("INSERT INTO rating_note (sourceRowId, rating) VALUES (?, 4)", "$server:s2")
            db.boundMerge(server, SAVE_GROUP_FLOOR).rebuild()
            assertEquals(4L, rating(db, "$server:s2"))
            assertEquals(1L, rating(db, "$server:s3"))
            assertEquals("the notes go once the save is in", 0L, db.longOf("SELECT COUNT(*) FROM rating_note"))
        } finally {
            db.close()
        }
    }
}

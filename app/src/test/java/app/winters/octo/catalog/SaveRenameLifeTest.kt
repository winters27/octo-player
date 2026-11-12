package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// How long a rename stays in force, and which rows a rename by key
// reaches. An id a car cached before a save still names its song after the
// save, and after a skipped merge too; the renames go only when a later
// save publishes renames of its own. A like written to an old id with
// another song's key, during the save and after it, is not moved by the
// rename by key, reads as no heart through either id, survives an unlike of
// the song, and the next save relinks it by its own key.
@RunWith(RobolectricTestRunner::class)
class SaveRenameLifeTest {
    private fun CatalogDatabase.renames(): Set<Triple<String, String, String>> =
        query("SELECT kind, `old`, `new` FROM save_rename", null).use { c -> buildSet { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2))) } }

    private fun CatalogDatabase.likes(): Map<String, String> =
        query("SELECT trackId, relinkKey FROM liked_track", null).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }

    @Test
    fun aCachedIdFollowsAfterTheSaveAndASkippedMerge() = guarded(60) {
        IdsRig(SAVE_GROUP_FLOOR).use { rig ->
            val phone = rig.serverLibraryThenPhone(20)
            // The car cached a server id before the save.
            val cached = rig.serverSong(3)
            rig.db.put(phone)
            rig.merge.rebuild()
            assertEquals(phoneSong(3), rig.ids.song(cached))
            val renames = rig.db.renames()
            assertTrue(renames.toString(), Triple(RENAME_SONG, cached, phoneSong(3)) in renames)
            // A merge with nothing new is skipped, and keeps the renames.
            val skips = TestHooks()
            rig.merge.hooks = skips
            rig.merge.rebuild()
            assertEquals(1, skips.skips.get())
            assertEquals(renames, rig.db.renames())
            assertEquals(phoneSong(3), rig.ids.song(cached))
            // A later save with renames of its own puts its own in force.
            val later = library(DEVICE, 20).let { lib -> lib.copy(tracks = lib.tracks.map { if (it.nativeId == "s5") it.copy(id = "$DEVICE:s5b", nativeId = "s5b") else it }) }
            rig.merge.hooks = GroupHooks()
            rig.db.put(later)
            rig.merge.rebuild()
            val after = rig.db.renames()
            assertTrue(after.toString(), Triple(RENAME_SONG, phoneSong(5), "$DEVICE:s5b") in after)
            assertFalse(after.toString(), after.any { it.second == cached })
        }
    }

    @Test
    fun aRenameByKeyReachesOnlyRowsWithItsKey() = guarded(60) {
        val db = openTestCatalog()
        try {
            val songs = 30
            db.put(library(DEVICE, songs))
            val hooks = GroupHooks()
            val merge = db.boundMerge(null, SAVE_GROUP_FLOOR, hooks)
            merge.rebuild()
            val ids = SongIds(db, merge)
            // Every file's id changes: each song is renamed by its key.
            db.put(library(DEVICE, songs).let { lib -> lib.copy(tracks = lib.tracks.map { it.copy(id = it.id + "b", nativeId = it.nativeId + "b") }) })
            var during: Triple<String, String, String>? = null
            val seen = ArrayList<String>()
            hooks.between = between@{ _ ->
                if (during != null) return@between
                val (old, new, key) = db.query("SELECT `old`, `new`, `key` FROM save_rename WHERE kind = 'track' AND byKey = 1 ORDER BY `old` LIMIT 1", null)
                    .use { c -> if (c.moveToFirst()) Triple(c.getString(0), c.getString(1), c.getString(2)) else null } ?: return@between
                val second = db.query("SELECT `old`, `new`, `key` FROM save_rename WHERE kind = 'track' AND byKey = 1 AND `old` != ? ORDER BY `old` LIMIT 1", arrayOf(old))
                    .use { c -> if (c.moveToFirst()) Triple(c.getString(0), c.getString(1), c.getString(2)) else null } ?: return@between
                during = Triple(old, new, key)
                // A like with another song's key, and one with the song's own.
                db.userDao().like(LikedTrackEntity(old, "someone|else", 1))
                db.userDao().like(LikedTrackEntity(second.first, second.third, 2))
                // The stale like reads as no heart through either id; the
                // song's own like reads through both.
                seen += "stale old ${db.userDao().isLiked(old)}"
                seen += "stale new ${db.userDao().isLiked(new)}"
                seen += "own old ${db.userDao().isLiked(second.first)}"
                seen += "own new ${db.userDao().isLiked(second.second)}"
                // An unlike of the song leaves the stale like; one of the
                // other song takes its like still on the old id.
                db.userDao().unlikeEither(new)
                db.userDao().unlikeEither(second.second)
                seen += "after unlikes ${db.likes().keys.sorted()}"
            }
            merge.rebuild()
            val (old, new, _) = during ?: throw AssertionError("no rename by key was published")
            assertEquals(listOf("stale old false", "stale new false", "own old true", "own new true", "after unlikes [$old]"), seen)
            // The stale like stayed on the old id; the song's own moved.
            assertEquals("someone|else", db.likes()[old])
            assertFalse(new in db.likes())
            // After the save, a stale-key like written to the old id stays
            // there too, through the trigger's key rule.
            val old2 = db.query("SELECT `old` FROM save_rename WHERE kind = 'track' AND byKey = 1 AND `old` NOT IN (SELECT trackId FROM liked_track) ORDER BY `old` LIMIT 1", null)
                .use { c -> c.moveToFirst(); c.getString(0) }
            db.userDao().like(LikedTrackEntity(old2, "someone|else", 3))
            assertEquals("someone|else", db.likes()[old2])
            assertEquals(old2, ids.row(RENAME_SONG, old2, "someone|else"))
            assertFalse(db.userDao().likedFlow(ids.song(old2)).first())
            // The next save relinks the stale likes by their own key: to the
            // song filed under it, once the library has one.
            db.put(library(DEVICE, songs).let { lib -> lib.copy(tracks = lib.tracks.map { it.copy(id = it.id + "b", nativeId = it.nativeId + "b") } + song(DEVICE, "z", "Else", "album0").copy(relinkKey = "someone|else")) })
            merge.rebuild()
            val z = "$DEVICE:z"
            val stale = db.query("SELECT trackId FROM liked_track WHERE relinkKey = 'someone|else'", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            assertTrue(stale.toString(), z in stale)
        } finally {
            db.close()
        }
    }
}

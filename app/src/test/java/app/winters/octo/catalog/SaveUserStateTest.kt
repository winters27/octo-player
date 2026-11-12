package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.favourites.PinKey
import app.winters.octo.ui.menu.PinSpot
import app.winters.octo.ui.menu.pinSpotOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// What the user wrote shows through an old id and through the id it
// became at every park of a save, while the save has moved some of a song's
// rows and not others: every heart, favorite and pin, and stars, which never
// flip back to the copies' older rating, a tap made during the save
// included.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SaveUserStateTest {
    @Test
    fun heartsStarsFavoritesAndPinsShowThroughEitherIdAtEveryPark() = guarded(240) {
        for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) {
            IdsRig(bound).use { rig ->
                val songs = if (bound == SAVE_GROUP_FLOOR) 80 else 1_200
                // The server's copies say 2 stars; the phone's taps say 5.
                rig.db.put(library(rig.source, songs).let { lib -> lib.copy(tracks = lib.tracks.map { it.copy(rating = 2) }) })
                rig.db.boundMerge(rig.source, bound).rebuild()
                for (id in (0 until songs).map(rig::serverSong)) {
                    val key = rig.db.stringOf("SELECT relinkKey FROM track WHERE id = ?", id)!!
                    rig.db.userDao().like(LikedTrackEntity(id, key, 1))
                    rig.db.userDao().noteTap(id, 5, key, 1)
                }
                val albums = rig.db.query("SELECT id, searchKey FROM album ORDER BY id", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
                val pinned = albums.take(4).map { it.first }.toSet()
                rig.db.favouritesDao().likeAlbums(albums.map { LikedAlbumEntity(it.first, it.second, 1) })
                rig.db.favouritesDao().savePins(albums.take(4).mapIndexed { i, a -> PinnedItemEntity("album", a.first, a.second, i) })
                rig.db.put(library(DEVICE, songs))
                var parks = 0
                val parkedAt = ArrayList<Long>()
                var split = 0
                // A song tapped to 4 stars at the first park, through its old id.
                var tapped: String? = null
                val wrong = ArrayList<String>()
                rig.hooks.between = { turn ->
                    parks++
                    parkedAt += turn
                    val renames = rig.db.query("SELECT kind, `old`, `new` FROM save_rename", null).use { c ->
                        buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2))) }
                    }
                    val songRenames = renames.filter { it.first == RENAME_SONG }
                    if (tapped == null && songRenames.isNotEmpty()) {
                        val (_, old, new) = songRenames.first()
                        tapped = old
                        rig.ratings.rate(old, 4)
                        withTimeout(10_000) { while (rig.db.userDao().rating(new) != 4) delay(5) }
                    }
                    // A song split between its ids: renamed, with its like
                    // still on the old one.
                    split += songRenames.count { rig.db.longOf("SELECT COUNT(*) FROM liked_track WHERE trackId = ?", it.second) == 1L }
                    for ((_, old, new) in songRenames) {
                        val stars = if (old == tapped) 4 else 5
                        for (id in listOf(old, new)) {
                            if (!rig.likes.likedFlow(id).first()) wrong += "turn $turn: $id shows no heart"
                            val rated = rig.db.userDao().rating(id)
                            if (rated != stars) wrong += "turn $turn: $id keeps $rated stars, not $stars"
                            val shown = rig.db.query("SELECT rating FROM track WHERE id = ?", arrayOf(id)).use { c -> if (c.moveToFirst()) c.getInt(0) else null }
                            if (shown != null && shown != stars) wrong += "turn $turn: $id shows $shown stars, not $stars"
                        }
                        if (rig.likes.likedAmong(listOf(old, new)).first() != setOf(old, new)) wrong += "turn $turn: $old and $new among the liked"
                    }
                    val favourites = rig.db.favouritesDao().favouriteAlbumIds().first().toSet()
                    val shown = rig.db.favouritesDao().shownPins().first().map { PinKey(PinKind.of(it.kind)!!, it.itemId) }.distinct()
                    withTimeout(10_000) { rig.pins.shown.first { it == shown } }
                    for ((_, old, new) in renames.filter { it.first == RENAME_ALBUM }) {
                        if (old !in favourites || new !in favourites) wrong += "turn $turn: album $old or $new not a favorite: $favourites"
                        if (old in pinned) {
                            for (id in listOf(old, new)) {
                                if (pinSpotOf(rig.pins, PinKey(PinKind.Album, id)).first() == PinSpot.None) wrong += "turn $turn: album $id reads unpinned ($shown)"
                            }
                        }
                    }
                }
                rig.merge.rebuild()
                assertTrue(wrong.take(20).joinToString("\n"), wrong.isEmpty())
                assertTrue("bound $bound: $parks parks", parks >= 2)
                assertTrue("bound $bound: no run was parked", parkedAt.any(rig.hooks::inRun))
                assertTrue("bound $bound: no park found a song split between its ids", split > 0)
                assertTrue("bound $bound: no song was tapped during the save", tapped != null)
            }
        }
    }
}

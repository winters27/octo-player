package app.winters.octo.playlists

import androidx.room.withTransaction
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.Flip
import app.winters.octo.catalog.LikedTrackEntity
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.exec
import app.winters.octo.catalog.renameLikeASave
import app.winters.octo.listening.timed
import app.winters.octo.testing.addSongs
import app.winters.octo.testing.counted
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// What a step does when the library, the playlist or another writer moves
// under it. A swap mid-step renames songs under rows already written: the
// flip, comparing the swap count, refuses, and the write-back tries again
// from the snapshot (never resolution), the third try inside
// betweenRebuilds; every row then names songs by their new ids. An unlink
// or an edit meanwhile refuses the flip and wins. A like waits for one
// page of the write-back at most.
@RunWith(RobolectricTestRunner::class)
class PlaylistSyncRacesTest {
    // A save as a step sees one: a library song takes a new id, every row
    // naming it follows (a group's rename), and the swap count moves, in
    // one transaction.
    private suspend fun CatalogDatabase.rename(from: String, to: String) = withTransaction {
        exec("UPDATE track SET id = ? WHERE id = ?", to, from)
        exec("UPDATE source_track SET mergedId = ? WHERE mergedId = ?", to, from)
        renameLikeASave(from, to)
    }

    private suspend fun pulled(rig: SyncRig, entries: List<String>, family: Boolean = false) {
        rig.library()
        rig.serverPlaylist("srv", "Mix", entries)
        rig.phonePlaylist(
            PlaylistEntity("p", "Mix", 1, 5, serverId = "srv", sourceId = SOURCE, syncedAt = 5, syncedName = "Mix", serverStamp = "old", family = family),
            rig.world.phoneRows(40),
            if (family) entries else null,
        )
    }

    private val PlaylistWorld.renamed get() = copies.first().mergedId

    private fun names(rig: SyncRig) = rig.db.query(
        "SELECT i.trackId FROM playlist p CROSS JOIN playlist_item i ON i.playlistId = p.id AND i.itemSet = p.itemSet WHERE p.id = 'p' ORDER BY i.ord, i.itemKey",
        arrayOf<Any?>(),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    @Test
    fun aSwapBeforeTheFlipIsTriedAgainFromTheSnapshot() = timed(60) {
        SyncRig(PlaylistWorld(11, 200), page = 7).use { rig ->
            val song = rig.world.renamed
            val native = rig.world.copies.first().nativeId
            val others = rig.world.linked.filter { it != native }
            pulled(rig, List(300) { if (it % 10 == 0) native else others[it % others.size] })
            var resolutions = 0
            rig.reads.hooks = object : ReadHooks() {
                override fun resolving(read: Long) {
                    resolutions++
                }
            }
            val flips = ArrayList<Flip>()
            rig.steps.hooks = object : StepHooks() {
                override suspend fun beforeFlip(playlistId: String, attempt: Int) {
                    if (attempt == 1) rig.db.rename(song, "$song:new")
                }

                override suspend fun flipped(playlistId: String, attempt: Int, flip: Flip) {
                    flips += flip
                }
            }
            rig.steps.run(rig.serverOf(false), PlaylistStep.Pull("p", "srv", "new", keepName = false))
            assertEquals(listOf(Flip.Swapped, Flip.Shown), flips)
            assertEquals("resolved once", 1, resolutions)
            val rows = names(rig)
            assertEquals(30, rows.count { it == "$song:new" })
            assertFalse(song in rows)
        }
    }

    @Test
    fun aSwapOnEveryTryEndsInsideBetweenRebuilds() = timed(60) {
        SyncRig(PlaylistWorld(12, 200)).use { rig ->
            val songs = rig.world.copies.take(3).map { it.mergedId }
            val native = rig.world.copies.first().nativeId
            val others = rig.world.linked.filter { it != native }
            pulled(rig, List(100) { if (it % 5 == 0) native else others[it % others.size] }, family = true)
            var resolutions = 0
            rig.reads.hooks = object : ReadHooks() {
                override fun resolving(read: Long) {
                    resolutions++
                }
            }
            val flips = ArrayList<Flip>()
            var inside = false
            rig.steps.hooks = object : StepHooks() {
                override suspend fun beforeFlip(playlistId: String, attempt: Int) {
                    if (attempt < 3) rig.db.rename(songs[attempt - 1], songs[attempt - 1] + ":new") else inside = rig.merge.holdsMergeLock()
                }

                override suspend fun flipped(playlistId: String, attempt: Int, flip: Flip) {
                    flips += flip
                }
            }
            rig.steps.run(rig.serverOf(false), PlaylistStep.Pull("p", "srv", "new", keepName = false))
            assertEquals(listOf(Flip.Swapped, Flip.Swapped, Flip.Shown), flips)
            assertTrue("the third try held the merge lock", inside)
            assertEquals(1, resolutions)
            val rows = names(rig)
            assertEquals(20, rows.count { it == songs[0] + ":new" })
            assertFalse(rows.any { it == songs[0] || it == songs[1] })
            assertEquals(
                "each key once",
                rig.db.count("SELECT COUNT(*) FROM playlist_item WHERE playlistId = 'p'"),
                rig.db.count("SELECT COUNT(DISTINCT itemKey) FROM playlist_item WHERE playlistId = 'p'"),
            )
        }
    }

    // A swap while resolution pages through the pool: the ids already
    // written follow it; resolution is not run again.
    @Test
    fun aSwapDuringResolutionLeavesNoOldId() = timed(60) {
        SyncRig(PlaylistWorld(13, 200), page = 7).use { rig ->
            val entries = rig.world.entries(400)
            pulled(rig, entries)
            var resolutions = 0
            val renamed = ArrayList<String>()
            rig.reads.hooks = object : ReadHooks() {
                override fun resolving(read: Long) {
                    resolutions++
                }

                override suspend fun pooled(read: Long, pages: Int) {
                    if (pages == 1) {
                        for (song in rig.world.copies.take(10).map { it.mergedId }.distinct()) {
                            rig.db.rename(song, "$song:new")
                            renamed += song
                        }
                    }
                }
            }
            rig.steps.run(rig.serverOf(false), PlaylistStep.Pull("p", "srv", "new", keepName = false))
            assertEquals(1, resolutions)
            assertTrue(renamed.isNotEmpty())
            val rows = names(rig)
            assertFalse(rows.any { it in renamed })
            assertEquals(0L, rig.db.count("SELECT COUNT(*) FROM playlist_item WHERE trackId NOT LIKE 'find:%' AND trackId NOT LIKE 'gone%' AND trackId NOT IN (SELECT id FROM track)"))
        }
    }

    // An unlink while the step resolves: the flip refuses, and the playlist
    // stays only on the phone, its base gone.
    @Test
    fun anUnlinkDuringAStepWins() = timed(60) {
        SyncRig(PlaylistWorld(14, 100)).use { rig ->
            val entries = rig.world.entries(200)
            pulled(rig, entries, family = true)
            rig.reads.hooks = object : ReadHooks() {
                override fun resolving(read: Long) {}

                override suspend fun streamed(read: Long, pages: Int) {
                    coroutineScope { launch(Dispatchers.IO) { rig.sets.unlinkServer(SOURCE) }.join() }
                }
            }
            rig.steps.run(rig.serverOf(false), PlaylistStep.Pull("p", "srv", "new", keepName = false))
            assertEquals(listOf("null|null|0"), rig.db.strings("SELECT IFNULL(serverId, 'null') || '|' || IFNULL(sourceId, 'null') || '|' || syncedSongsKept FROM playlist"))
            assertEquals(0L, rig.db.count("SELECT COUNT(*) FROM playlist_synced_song"))
            assertEquals(40L, rig.db.count("SELECT COUNT(*) FROM playlist_item"))
        }
    }

    // An add and a rename while the write-back's new set waits to be shown:
    // the flip refuses, the playlist keeps them and its old rows, and the
    // new set is gone.
    @Test
    fun anEditDuringAWriteBackWins() = timed(60) {
        SyncRig(PlaylistWorld(15, 100)).use { rig ->
            pulled(rig, rig.world.entries(200))
            val before = names(rig)
            val added = rig.world.tracks.first()
            rig.steps.hooks = object : StepHooks() {
                override suspend fun beforeFlip(playlistId: String, attempt: Int) {
                    rig.db.addSongs("p", listOf(added), now = 50)
                    rig.db.userDao().renamePlaylist("p", "Mine now", now = 51)
                }
            }
            rig.steps.run(rig.serverOf(false), PlaylistStep.Pull("p", "srv", "new", keepName = false))
            assertEquals(before + added.id, names(rig))
            assertEquals(listOf("Mine now|0"), rig.db.strings("SELECT name || '|' || itemSet FROM playlist"))
            assertEquals(0L, rig.db.count("SELECT COUNT(*) FROM playlist_item WHERE itemSet != 0"))
        }
    }

    // A like during a write-back of 3,000 rows commits between two of its
    // pages: no transaction of the write-back holds the database longer.
    @Test
    fun aLikeWaitsForOnePageAtMost() = timed(60) {
        SyncRig(PlaylistWorld(16, 300)).use { rig ->
            pulled(rig, rig.world.entries(3_000))
            var liked = false
            rig.steps.hooks = object : StepHooks() {
                override suspend fun wrotePage(playlistId: String, attempt: Int, page: Int) {
                    if (page != 3) return
                    val done = CompletableDeferred<Unit>()
                    coroutineScope {
                        val like = async(Dispatchers.IO) {
                            rig.db.userDao().like(LikedTrackEntity(rig.world.tracks.first().id, "k", 1))
                            done.complete(Unit)
                        }
                        withTimeout(5_000) { done.await() }
                        like.await()
                    }
                    liked = true
                }
            }
            rig.db.counted.reset()
            rig.db.counted.recording = true
            rig.steps.run(rig.serverOf(false), PlaylistStep.Pull("p", "srv", "new", keepName = false))
            assertTrue(liked)
            assertTrue(rig.db.counted.transactions.size > 12)
            val writes = rig.db.counted.transactions.filter { it.label != CLEAR_WRITE }
            assertTrue("no transaction writes more than a page: ${writes.maxByOrNull { it.rowsChanged }}", writes.all { it.rowsChanged <= 500 + 2 })
        }
    }
}

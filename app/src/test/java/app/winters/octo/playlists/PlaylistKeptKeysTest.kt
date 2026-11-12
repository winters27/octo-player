package app.winters.octo.playlists

import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.listening.timed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A family pull keeps the key of every phone row whose song the server
// still has, across the new set it writes, so a picked row or one a
// pending Undo would take out is still there; a new row takes a fresh key.
// A pull of a playlist that is not the family's gives every row a fresh
// key, as today gives every row a fresh id.
@RunWith(RobolectricTestRunner::class)
class PlaylistKeptKeysTest {
    private fun keys(rig: SyncRig): List<Pair<Long, String>> = rig.db.strings(
        "SELECT i.itemKey || '|' || i.trackId FROM playlist p CROSS JOIN playlist_item i ON i.playlistId = p.id AND i.itemSet = p.itemSet ORDER BY i.ord, i.itemKey",
    ).map { it.substringBefore('|').toLong() to it.substringAfter('|') }

    private suspend fun pulled(family: Boolean): Triple<List<Pair<Long, String>>, List<Pair<Long, String>>, Long> =
        SyncRig(PlaylistWorld(61, 200), page = 7).use { rig ->
            rig.library()
            val natives = rig.world.linked.take(60)
            val rows = natives.map { native -> rig.world.copies.first { it.nativeId == native }.mergedId to native }
            // The server holds the phone's songs in another order, less a few, with new ones.
            rig.server.add("srv", "Mix", natives.drop(5).reversed() + rig.world.linked.drop(60).take(10))
            rig.phonePlaylist(
                PlaylistEntity("p", "Mix", 1, 5, serverId = "srv", sourceId = SOURCE, syncedAt = 5, syncedName = "Mix", serverStamp = "old", family = family),
                rows,
                if (family) natives else null,
            )
            val before = keys(rig)
            val next = rig.db.count("SELECT nextItemKey FROM playlist_meta")
            rig.steps.run(rig.serverOf(false), PlaylistStep.Pull("p", "srv", "new", keepName = false))
            Triple(before, keys(rig), next)
        }

    @Test
    fun aFamilyPullKeepsTheKeysOfRowsItKeeps() = timed(60) {
        val (before, after, next) = pulled(family = true)
        val kept = before.drop(5).toSet()
        assertEquals(kept, after.filter { it.first < next }.toSet())
        assertTrue(after.filter { it.first >= next }.size == 10)
        assertEquals(after.size, after.map { it.first }.distinct().size)
    }

    @Test
    fun aPullOfAnotherPlaylistGivesFreshKeys() = timed(60) {
        val (_, after, next) = pulled(family = false)
        assertTrue(after.all { it.first >= next })
        assertEquals(65, after.size)
    }
}

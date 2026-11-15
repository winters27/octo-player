package app.winters.octo.playlists

import android.database.sqlite.SQLiteFullException
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.listening.timed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The base step seeds only the listener's own linked playlists of the
// signed-in server that are unedited since their last sync, with the songs
// the phone would send under the outside rule; leaves family playlists'
// bases alone; skips a playlist edited or unlinked while it runs; writes its
// mark last, so a failure part way leaves it for next time; runs once per
// server, and only with octoPlaylistSongs.
@RunWith(RobolectricTestRunner::class)
class PlaylistBaseStepTest {
    private fun own(id: String, serverId: String?, updatedAt: Long = 3, sourceId: String = SOURCE, family: Boolean = false) = PlaylistEntity(
        id = id, name = id, createdAt = 1, updatedAt = updatedAt, serverId = serverId, sourceId = sourceId, syncedAt = 3, syncedName = id,
        serverStamp = "2026-11-01T00:00:00Z|1", family = family, familyMayAdd = family, familyMayRemove = family,
    )

    private fun SyncRig.baseOf(id: String): Map<String, Int> =
        db.strings("SELECT b.songId || '=' || b.copies FROM playlist_synced_song b JOIN playlist p ON p.id = b.playlistId AND b.baseSet = p.baseSet WHERE p.id = ?", id)
            .associate { it.substringBeforeLast('=') to it.substringAfterLast('=').toInt() }

    private fun SyncRig.kept(id: String): Boolean = db.strings("SELECT syncedSongsKept FROM playlist WHERE id = ?", id).single() == "1"

    private fun CatalogDatabase.exec(sql: String, vararg args: Any?) = openHelper.writableDatabase.execSQL(sql, args)

    @Test
    fun theStepSeedsTheSignedInServersUneditedOwnPlaylistsByTheOutsideRule() = timed(60) {
        SyncRig(PlaylistWorld(51, 10), playlistSongs = true).use { rig ->
            rig.library()
            rig.phonePlaylist(own("a", "srvA"), listOf("t1" to "s1", "t2" to "x1", "find:f0" to "f0"), base = null)
            rig.phonePlaylist(own("b", "srvB", updatedAt = 5), listOf("t1" to "s1"), base = null)
            rig.phonePlaylist(own("c", "srvC", family = true), listOf("t1" to "s1"), base = listOf("s9"))
            rig.phonePlaylist(own("d", "srvD", sourceId = "server:other"), listOf("t1" to "s1"), base = null)
            rig.phonePlaylist(own("e", null), listOf("t1" to null), base = null)
            rig.steps.ensureBase(rig.serverOf(false))
            assertEquals(mapOf("s1" to 1, "x1" to 1, "f0" to 1), rig.baseOf("a"))
            assertTrue(rig.kept("a"))
            assertFalse("edited before the step", rig.kept("b"))
            assertEquals("the family path's base stays", mapOf("s9" to 1), rig.baseOf("c"))
            assertFalse("another server's", rig.kept("d"))
            assertFalse("not linked", rig.kept("e"))
            assertTrue(rig.db.userDao().baseSeeded(SOURCE))
            assertFalse(rig.db.userDao().baseSeeded("server:other"))
        }
    }

    @Test
    fun aPlaylistEditedOrUnlinkedWhileTheStepRunsKeepsNoBase() = timed(60) {
        SyncRig(PlaylistWorld(52, 10), playlistSongs = true).use { rig ->
            rig.library()
            rig.phonePlaylist(own("a", "srvA"), listOf("t1" to "s1"), base = null)
            rig.phonePlaylist(own("b", "srvB"), listOf("t1" to "s1"), base = null)
            rig.phonePlaylist(own("c", "srvC"), listOf("t1" to "s1"), base = null)
            rig.steps.hooks = object : StepHooks() {
                override suspend fun seeding(playlistId: String) {
                    when (playlistId) {
                        "a" -> rig.db.exec("UPDATE playlist SET updatedAt = updatedAt + 1 WHERE id = 'a'")
                        "b" -> rig.db.exec("UPDATE playlist SET serverId = NULL, sourceId = NULL WHERE id = 'b'")
                    }
                }
            }
            rig.steps.ensureBase(rig.serverOf(false))
            assertFalse(rig.kept("a"))
            assertFalse(rig.kept("b"))
            assertTrue(rig.kept("c"))
            assertTrue("the mark goes after every row", rig.db.userDao().baseSeeded(SOURCE))
        }
    }

    @Test
    fun aFullDiskPartWayLeavesTheStepForTheNextHolder() = timed(60) {
        SyncRig(PlaylistWorld(53, 10), playlistSongs = true).use { rig ->
            rig.library()
            rig.phonePlaylist(own("a", "srvA"), listOf("t1" to "s1"), base = null)
            rig.steps.hooks = object : StepHooks() {
                override suspend fun seeding(playlistId: String) {
                    throw SQLiteFullException("full")
                }
            }
            assertTrue(runCatching { rig.steps.ensureBase(rig.serverOf(false)) }.exceptionOrNull() is SQLiteFullException)
            assertFalse(rig.db.userDao().baseSeeded(SOURCE))
            rig.steps.hooks = StepHooks()
            rig.steps.ensureBase(rig.serverOf(false))
            assertTrue(rig.kept("a"))
            assertTrue(rig.db.userDao().baseSeeded(SOURCE))
        }
    }

    @Test
    fun theStepRunsOncePerServerAndOnlyWithTheExtension() = timed(60) {
        SyncRig(PlaylistWorld(54, 10), playlistSongs = true).use { rig ->
            rig.library()
            rig.phonePlaylist(own("a", "srvA"), listOf("t1" to "s1"), base = null)
            rig.steps.ensureBase(SyncServer(rig.server.client(), SOURCE, "account", formPost = false, familyRules = false, playlistSongs = false))
            assertFalse(rig.kept("a"))
            assertFalse(rig.db.userDao().baseSeeded(SOURCE))
            rig.steps.ensureBase(rig.serverOf(false))
            assertTrue(rig.kept("a"))
            rig.phonePlaylist(own("z", "srvZ"), listOf("t1" to "s1"), base = null)
            rig.steps.ensureBase(rig.serverOf(false))
            assertFalse("once per server", rig.kept("z"))
        }
    }
}

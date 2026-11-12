package app.winters.octo.playlists

import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.listening.timed
import app.winters.octo.subsonic.SubsonicException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A family push without a form body sends more than one call's worth of
// changes a call at a time: removals first, 150 a call from the end of the
// list, then adds, 150 a call; each call that went through is taken into
// the base at once. So when a call fails part way, the next sync sends
// exactly the rest: no song taken out twice, none added twice.
@RunWith(RobolectricTestRunner::class)
class FamilyChunksTest {
    private fun family(stamp: String) = PlaylistEntity(
        id = "p", name = "Ours", createdAt = 1, updatedAt = 5, serverId = "srv", sourceId = SOURCE, syncedAt = 3, syncedName = "Ours",
        serverStamp = stamp, family = true, familyMayAdd = true, familyMayRemove = true,
    )

    private fun stampOf(rig: SyncRig) = rig.server.playlists.getValue("srv").let { stampOf("2026-11-01T%02d:%02d:00Z".format(it.changes / 60 % 24, it.changes % 60), it.entries.size) }

    private fun songParams(call: PlaylistServer.Call) = call.params.count { it.first in setOf("songIndexToRemove", "octoSongToRemove", "songIdToAdd") }

    private suspend fun SyncRig.base(): Map<String, Int> =
        db.strings("SELECT b.songId || '=' || b.copies FROM playlist_synced_song b JOIN playlist p ON p.id = b.playlistId AND b.baseSet = p.baseSet")
            .associate { it.substringBeforeLast('=') to it.substringAfterLast('=').toInt() }

    @Test
    fun removalsGoInCallsAndARetrySendsTheRest() = timed(60) {
        SyncRig(PlaylistWorld(21, 10), familyRules = true).use { rig ->
            val removed = List(400) { "r$it" }
            val kept = List(10) { "k$it" }
            rig.server.add("srv", "Ours", removed + kept, owner = "Family", octoFamily = true)
            rig.phonePlaylist(family(stampOf(rig)), kept.map { "find:$it" to it }, removed + kept)
            rig.server.failChange = { it == 2 }
            val failed = runCatching { rig.steps.run(rig.serverOf(false), PlaylistStep.Push("p", "srv")) }.exceptionOrNull()
            assertTrue(failed is SubsonicException)
            assertEquals(removed.take(250) + kept, rig.server.playlists.getValue("srv").entries)
            assertTrue(rig.server.changeCalls().all { songParams(it) <= 150 })
            assertEquals("the base lost the 150 that went", (removed.take(250) + kept).associateWith { 1 }, rig.base())

            rig.server.failChange = { false }
            rig.server.calls.clear()
            rig.db.exec("UPDATE playlist SET serverStamp = ? WHERE id = 'p'", stampOf(rig))
            rig.steps.run(rig.serverOf(false), PlaylistStep.Push("p", "srv"))
            val retry = rig.server.changeCalls().filter { it.endpoint == "updatePlaylist" && it.params.any { p -> p.first == "octoSongToRemove" } }
            assertEquals("only the rest is sent", 250, retry.sumOf(::songParams))
            assertTrue(retry.all { songParams(it) <= 150 })
            assertEquals(kept, rig.server.playlists.getValue("srv").entries)
            assertEquals(kept.map { "find:$it" }, rig.db.strings("SELECT trackId FROM playlist_item i JOIN playlist p ON p.id = i.playlistId AND i.itemSet = p.itemSet ORDER BY i.ord"))
        }
    }

    @Test
    fun addsGoInCallsAndARetryAddsEachOnce() = timed(60) {
        SyncRig(PlaylistWorld(22, 10)).use { rig ->
            val there = List(10) { "k$it" }
            val added = List(400) { "a$it" }
            rig.server.add("srv", "Ours", there, owner = "Family", octoFamily = true)
            rig.phonePlaylist(family(stampOf(rig)), (there + added).map { "find:$it" to it }, there)
            rig.server.failChange = { it == 2 }
            assertTrue(runCatching { rig.steps.run(rig.serverOf(false), PlaylistStep.Push("p", "srv")) }.exceptionOrNull() is SubsonicException)
            assertEquals(there + added.take(150), rig.server.playlists.getValue("srv").entries)
            assertEquals("the base gained the 150 that went", (there + added.take(150)).associateWith { 1 }, rig.base())

            rig.server.failChange = { false }
            rig.server.calls.clear()
            rig.db.exec("UPDATE playlist SET serverStamp = ? WHERE id = 'p'", stampOf(rig))
            rig.steps.run(rig.serverOf(false), PlaylistStep.Push("p", "srv"))
            val retry = rig.server.changeCalls().filter { it.params.any { p -> p.first == "songIdToAdd" } }
            assertEquals("only the rest is sent", 250, retry.sumOf(::songParams))
            assertEquals(there + added, rig.server.playlists.getValue("srv").entries)
        }
    }

    // A call whose answer was lost, that landed, counts as sent: it is not
    // sent again, and the calls after it go on.
    @Test
    fun aLostAnswerThatLandedIsNotSentAgain() = timed(60) {
        SyncRig(PlaylistWorld(23, 10)).use { rig ->
            val there = List(10) { "k$it" }
            val added = List(200) { "a$it" }
            rig.server.add("srv", "Ours", there, owner = "Family", octoFamily = true)
            rig.phonePlaylist(family(stampOf(rig)), (there + added).map { "find:$it" to it }, there)
            rig.server.loseAnswer = { it == 1 }
            rig.steps.run(rig.serverOf(false), PlaylistStep.Push("p", "srv"))
            assertEquals(there + added, rig.server.playlists.getValue("srv").entries)
        }
    }
}

private fun app.winters.octo.catalog.CatalogDatabase.exec(sql: String, vararg args: Any?) = openHelper.writableDatabase.execSQL(sql, args)

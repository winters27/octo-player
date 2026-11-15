package app.winters.octo.playlists

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.FOLDER_OP_PLACE
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistFolderEntity
import app.winters.octo.catalog.PlaylistPlaceEntity
import app.winters.octo.listening.timed
import app.winters.octo.playback.savesForFinds
import app.winters.octo.subsonic.IF_STAMP_PARAM
import app.winters.octo.subsonic.OWN_IDS_PARAM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A server listing octoPlaylistSongs: pushes send the server's own ids for
// songs found online, every replace carries octoIfStamp and octoOwnIds, a
// stale replace is settled on top of the server's list, a playlist changed
// on both sides keeps the phone's adds and removals (Family off), every push
// ends with the write-back, skipped songs leave with a word, a playlist
// with no base keeps the phone's version aside, and a phone-only playlist
// holding a find goes to the server with its folder placement.
@RunWith(RobolectricTestRunner::class)
class PlaylistSongsSyncTest {
    private fun row(stamp: String, id: String = "p", name: String = "Road", serverId: String? = "srv") = PlaylistEntity(
        id = id, name = name, createdAt = 1, updatedAt = 5, serverId = serverId, sourceId = SOURCE, syncedAt = 3, syncedName = name, serverStamp = stamp,
    )

    private fun stampOf(rig: SyncRig, id: String = "srv") =
        rig.server.playlists.getValue(id).let { stampOf("2026-11-01T%02d:%02d:00Z".format(it.changes / 60 % 24, it.changes % 60), it.entries.size) }

    private fun SyncRig.base(id: String = "p"): Map<String, Int> =
        db.strings("SELECT b.songId || '=' || b.copies FROM playlist_synced_song b JOIN playlist p ON p.id = b.playlistId AND b.baseSet = p.baseSet WHERE p.id = ?", id)
            .associate { it.substringBeforeLast('=') to it.substringAfterLast('=').toInt() }

    private fun SyncRig.rows(id: String = "p"): List<String> =
        db.strings("SELECT i.trackId FROM playlist_item i JOIN playlist p ON p.id = i.playlistId AND i.itemSet = p.itemSet WHERE p.id = ? ORDER BY i.ord, i.itemKey", id)

    private fun SyncRig.noticing(words: MutableList<String>) =
        PlaylistSteps(db, sets, merge, reads, walks, { null }, { _, _ -> }, { NOW }, notice = { words += it })

    private fun values(call: PlaylistServer.Call, name: String) = call.params.filter { it.first == name }.map { it.second }

    private fun one(call: PlaylistServer.Call, name: String) = call.params.firstOrNull { it.first == name }?.second

    private fun CatalogDatabase.exec(sql: String, vararg args: Any?) = openHelper.writableDatabase.execSQL(sql, args)

    @Test
    fun aPushSendsTheServersOwnIdsForSongsFoundOnlineWithItsStamp() = timed(60) {
        SyncRig(PlaylistWorld(31, 10), playlistSongs = true).use { rig ->
            rig.library()
            rig.server.add("srv", "Road", listOf("s1", "x1"))
            val stamp = stampOf(rig)
            // t2 was matched to x1 by title on an older pull: it still names x1.
            rig.phonePlaylist(row(stamp), listOf("t1" to "s1", "t2" to "x1", "find:f0" to null), base = listOf("s1", "x1"))
            rig.steps.run(rig.serverOf(false), PlaylistStep.Push("p", "srv"))
            val replace = rig.server.changeCalls().single { it.endpoint == "createPlaylist" }
            assertEquals(listOf("s1", "x1", "f0"), values(replace, "songId"))
            assertEquals(changedOf(stamp), one(replace, IF_STAMP_PARAM))
            assertEquals("1", one(replace, OWN_IDS_PARAM))
            assertEquals(listOf("s1", "x1", "f0"), rig.server.playlists.getValue("srv").entries)
            // The write-back: the server's list is the base, and both sides agree.
            assertEquals(mapOf("s1" to 1, "x1" to 1, "f0" to 1), rig.base())
            val now = rig.db.userDao().playlistRow("p")!!
            assertEquals(now.updatedAt, now.syncedAt)
            assertEquals(stampOf(rig), now.serverStamp)
        }
    }

    @Test
    fun aStaleReplaceTakesThePhonesChangesOnTopOfTheServers() = timed(60) {
        SyncRig(PlaylistWorld(32, 10), playlistSongs = true).use { rig ->
            rig.library()
            rig.server.add("srv", "Road", listOf("s1", "s3"))
            rig.phonePlaylist(row(stampOf(rig)), listOf("t1" to "s1", "t3" to "s3", "t4" to null), base = listOf("s1", "s3"))
            rig.server.conflictNext = true
            rig.server.onConflict = { held ->
                held.entries += "s8"
                held.changes += 50
            }
            rig.steps.run(rig.serverOf(false), PlaylistStep.Push("p", "srv"))
            assertEquals(listOf("s1", "s3", "s8", "s4"), rig.server.playlists.getValue("srv").entries)
            assertEquals(listOf("s4"), rig.server.changeCalls().filter { it.endpoint == "updatePlaylist" }.flatMap { values(it, "songIdToAdd") })
            assertEquals(listOf("t1", "t3", "t8", "t4"), rig.rows())
        }
    }

    @Test
    fun changedOnBothSidesAPlainPlaylistKeepsThePhonesAddsAndRemovalsWithFamilyOff() = timed(60) {
        SyncRig(PlaylistWorld(33, 10), playlistSongs = true).use { rig ->
            rig.library()
            // Another device added s9 since both sides last agreed.
            rig.server.add("srv", "Road", listOf("s1", "s2", "s3", "s9"))
            rig.phonePlaylist(row("2026-01-01T00:00:00Z|3"), listOf("t1" to "s1", "t3" to "s3", "t4" to null), base = listOf("s1", "s2", "s3"))
            rig.steps.run(rig.serverOf(false), PlaylistStep.Push("p", "srv"))
            assertEquals(listOf("s1", "s3", "s9", "s4"), rig.server.playlists.getValue("srv").entries)
            val update = rig.server.changeCalls().single { it.endpoint == "updatePlaylist" }
            assertEquals(listOf("s2@1"), values(update, "octoSongToRemove"))
            assertTrue("removals go by song", values(update, "songIndexToRemove").isEmpty())
            assertTrue("never a whole replace over the other device's add", rig.server.changeCalls().none { it.endpoint == "createPlaylist" })
            assertEquals(listOf("t1", "t3", "t9", "t4"), rig.rows())
            assertEquals(mapOf("s1" to 1, "s3" to 1, "s9" to 1, "s4" to 1), rig.base())
        }
    }

    @Test
    fun changedOnBothSidesAPlaylistHoldingAStreamedSongKeepsIt() = timed(60) {
        SyncRig(PlaylistWorld(34, 10), playlistSongs = true).use { rig ->
            rig.library()
            rig.server.add("srv", "Road", listOf("s1", "x1", "s2", "s9"))
            rig.phonePlaylist(row("2026-01-01T00:00:00Z|3"), listOf("t1" to "s1", "t7" to "x1", "find:f0" to null), base = listOf("s1", "x1", "s2"))
            rig.steps.run(rig.serverOf(false), PlaylistStep.Push("p", "srv"))
            assertEquals(listOf("s1", "x1", "s9", "f0"), rig.server.playlists.getValue("srv").entries)
            assertEquals(listOf("s2@1"), rig.server.changeCalls().flatMap { values(it, "octoSongToRemove") })
            assertEquals(listOf("f0"), rig.server.changeCalls().flatMap { values(it, "songIdToAdd") })
        }
    }

    @Test
    fun anotherDevicesAddBetweenThePushAndTheReadSurvives() = timed(60) {
        SyncRig(PlaylistWorld(35, 10), playlistSongs = true).use { rig ->
            rig.library()
            rig.server.add("srv", "Road", listOf("s1"))
            rig.phonePlaylist(row(stampOf(rig)), listOf("t1" to "s1", "t2" to null), base = listOf("s1"))
            var once = true
            rig.server.afterChange = { held ->
                if (once) {
                    once = false
                    held.entries += "s9"
                    held.changes += 50
                }
            }
            rig.steps.run(rig.serverOf(false), PlaylistStep.Push("p", "srv"))
            assertEquals(listOf("t1", "t2", "t9"), rig.rows())
            assertEquals(mapOf("s1" to 1, "s2" to 1, "s9" to 1), rig.base())
            val now = rig.db.userDao().playlistRow("p")!!
            assertEquals(stampOf(rig), now.serverStamp)
            assertEquals(now.updatedAt, now.syncedAt)
            // The next push finds nothing to send.
            rig.server.calls.clear()
            rig.steps.run(rig.serverOf(false), PlaylistStep.Push("p", "srv"))
            assertTrue(rig.server.changeCalls().isEmpty())
        }
    }

    @Test
    fun aPlaylistEditedDuringThePushTakesThePushedSongsLessTheSkippedAsItsBase() = timed(60) {
        SyncRig(PlaylistWorld(36, 10), playlistSongs = true).use { rig ->
            rig.library()
            rig.server.add("srv", "Road", listOf("s1"))
            rig.server.skipping += "f0"
            val words = ArrayList<String>()
            val steps = rig.noticing(words)
            steps.hooks = object : StepHooks() {
                override suspend fun pushed(playlistId: String) {
                    rig.db.exec("UPDATE playlist SET updatedAt = updatedAt + 1 WHERE id = ?", playlistId)
                }
            }
            rig.phonePlaylist(row(stampOf(rig)), listOf("t1" to "s1", "t2" to null, "find:f0" to null), base = listOf("s1"))
            val before = rig.db.userDao().playlistRow("p")!!.serverStamp
            steps.run(rig.serverOf(false), PlaylistStep.Push("p", "srv"))
            assertEquals(listOf("s1", "s2"), rig.server.playlists.getValue("srv").entries)
            assertEquals(mapOf("s1" to 1, "s2" to 1), rig.base())
            assertEquals("the server's stamp stays, so the change made meanwhile goes on top next time", before, rig.db.userDao().playlistRow("p")!!.serverStamp)
            assertEquals("the skipped find left", listOf("t1", "t2"), rig.rows())
            assertEquals(listOf(PlaylistFolderWords.SKIPPED_SONGS), words)
        }
    }

    @Test
    fun skippedSongsLeaveWithOneWordAfterTheWriteBack() = timed(60) {
        SyncRig(PlaylistWorld(37, 10), playlistSongs = true).use { rig ->
            rig.library()
            rig.server.add("srv", "Road", listOf("s1"))
            rig.server.skipping += setOf("f0", "f1")
            val words = ArrayList<String>()
            rig.phonePlaylist(row(stampOf(rig)), listOf("t1" to "s1", "find:f0" to null, "find:f1" to null), base = listOf("s1"))
            rig.noticing(words).run(rig.serverOf(false), PlaylistStep.Push("p", "srv"))
            assertEquals(listOf("t1"), rig.rows())
            assertEquals(listOf(PlaylistFolderWords.SKIPPED_SONGS), words)
        }
    }

    @Test
    fun aRowWithNoBaseAndAnUnchangedServerPushesAnExactReplace() = timed(60) {
        SyncRig(PlaylistWorld(38, 10), playlistSongs = true).use { rig ->
            rig.library()
            rig.server.add("srv", "Road", listOf("s1"))
            val stamp = stampOf(rig)
            rig.phonePlaylist(row(stamp), listOf("t1" to "s1", "t2" to null), base = null)
            rig.steps.run(rig.serverOf(false), PlaylistStep.Push("p", "srv"))
            val replace = rig.server.changeCalls().single { it.endpoint == "createPlaylist" }
            assertEquals(changedOf(stamp), one(replace, IF_STAMP_PARAM))
            assertEquals(listOf("s1", "s2"), rig.server.playlists.getValue("srv").entries)
            assertEquals("the write-back keeps a base from now on", mapOf("s1" to 1, "s2" to 1), rig.base())
        }
    }

    @Test
    fun aRowWithNoBaseChangedOnBothSidesIsKeptAsideFromThisPhone() = timed(60) {
        SyncRig(PlaylistWorld(39, 10), playlistSongs = true).use { rig ->
            rig.library()
            rig.server.add("srv", "Road", listOf("s1", "s9"))
            val words = ArrayList<String>()
            rig.phonePlaylist(row("2026-01-01T00:00:00Z|1"), listOf("t1" to "s1", "t2" to null, "find:f0" to null), base = null)
            rig.noticing(words).run(rig.serverOf(false), PlaylistStep.Push("p", "srv"))
            assertEquals("the linked playlist takes the server's list", listOf("t1", "t9"), rig.rows())
            assertEquals(listOf("s1", "s9"), rig.server.playlists.getValue("srv").entries)
            assertEquals(listOf(PlaylistFolderWords.bothChanged("Road")), words)
            // The phone's version, holding a find, went to the server at once.
            val copy = rig.server.playlists.values.single { it.name == "Road (from this phone)" }
            assertEquals(listOf("s1", "s2", "f0"), copy.entries)
            val copyId = rig.db.strings("SELECT id FROM playlist WHERE name = 'Road (from this phone)'").single()
            assertEquals(copy.id, rig.db.userDao().playlistRow(copyId)!!.serverId)
        }
    }

    @Test
    fun anExactReplaceWithNoBaseAnsweredStaleIsKeptAsideFromThisPhone() = timed(60) {
        SyncRig(PlaylistWorld(40, 10), playlistSongs = true).use { rig ->
            rig.library()
            rig.server.add("srv", "Road", listOf("s1"))
            rig.server.conflictNext = true
            rig.server.onConflict = { held ->
                held.entries += "s9"
                held.changes += 50
            }
            val words = ArrayList<String>()
            rig.phonePlaylist(row(stampOf(rig)), listOf("t1" to "s1", "t2" to null), base = null)
            rig.noticing(words).run(rig.serverOf(false), PlaylistStep.Push("p", "srv"))
            assertEquals(listOf("t1", "t9"), rig.rows())
            assertEquals(listOf(PlaylistFolderWords.bothChanged("Road")), words)
            val copyId = rig.db.strings("SELECT id FROM playlist WHERE name = 'Road (from this phone)'").single()
            assertEquals(listOf("t1", "t2"), rig.rows(copyId))
            assertNull("no find in it: it stays on the phone", rig.db.userDao().playlistRow(copyId)!!.sourceId)
        }
    }

    @Test
    fun aPhoneOnlyPlaylistHoldingAFindGoesToTheServerWithItsFolderPlacement() = timed(60) {
        assertTrue(savesForFinds(null, holdsFind = true, findsOpen = true))
        assertFalse("already on a server", savesForFinds("server:h", holdsFind = true, findsOpen = true))
        assertFalse("no find", savesForFinds(null, holdsFind = false, findsOpen = true))
        assertFalse("a server that can't keep it", savesForFinds(null, holdsFind = true, findsOpen = false))
        SyncRig(PlaylistWorld(41, 10), playlistSongs = true).use { rig ->
            rig.library()
            val folders = rig.db.playlistFolderDao()
            folders.putFolder(PlaylistFolderEntity("account", "pfAAAAAAAAAAAAAAAAAAAA", "Road trips", null, "2026-11-14T10:00:00Z"))
            folders.putPlace(PlaylistPlaceEntity("account", PlaylistFolderTree.localKey("m"), "pfAAAAAAAAAAAAAAAAAAAA", localOnly = true))
            rig.phonePlaylist(row("", id = "m", name = "Mix", serverId = null), listOf("t1" to null, "find:f0" to null), base = null)
            rig.steps.create(rig.serverOf(false), rig.db.userDao().playlistRow("m")!!)
            val made = rig.server.changeCalls().single { it.endpoint == "createPlaylist" }
            assertEquals(listOf("s1", "f0"), values(made, "songId"))
            val serverId = rig.db.userDao().playlistRow("m")!!.serverId!!
            assertEquals(listOf("s1", "f0"), rig.server.playlists.getValue(serverId).entries)
            val place = folders.place("account", serverId)!!
            assertEquals("pfAAAAAAAAAAAAAAAAAAAA", place.folderId)
            assertFalse(place.localOnly)
            assertNull(folders.place("account", PlaylistFolderTree.localKey("m")))
            val op = folders.ops("account").single()
            assertEquals(FOLDER_OP_PLACE, op.kind)
            assertEquals(serverId, op.playlistKeys)
            assertEquals(mapOf("s1" to 1, "f0" to 1), rig.base("m"))
        }
    }
}

package app.winters.octo.playlists

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.listening.timed
import app.winters.octo.subsonic.FamilyAbilities
import app.winters.octo.subsonic.FamilyMe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Labeled pin: every step of the streamed sync against today's steps
// (PlaylistOracle) on generated libraries and playlists, N from 0 to 1,500
// with pages of 500 and of 7: import with and without a twin, pull with and
// without keeping the name and with kept keys, push equal and different,
// every branch of a family push, create, and remove. After each step both
// sides hold the same playlists (every column but the id), the same rows in
// play order (key, song, relink key, server song), the same base, the same
// finds, and their servers the same playlists after the same calls. The
// streamed side also leaves no work rows, pins, old sets or drain files.
@RunWith(RobolectricTestRunner::class)
class PlaylistSyncParityTest {
    private class Case(val seed: Long, val size: Int, val page: Int)

    private val cases = listOf(Case(1, 0, 500), Case(2, 1, 7), Case(3, 7, 7), Case(4, 40, 7), Case(5, 120, 500), Case(6, 600, 7), Case(7, 1_500, 500))

    private val stamp0 get() = "2026-11-01T00:00:00Z"

    private fun linkedRow(stamp: String, family: Boolean = false, edited: Boolean = false, add: Boolean = true, remove: Boolean = true, name: String = "Mix") =
        PlaylistEntity(
            id = "p", name = name, createdAt = 1, updatedAt = 5, serverId = "srv", sourceId = SOURCE,
            syncedAt = if (edited) 3 else 5, syncedName = "Mix", serverStamp = stamp, family = family,
            familyMayAdd = family && add, familyMayRemove = family && remove,
        )

    private suspend fun each(
        sizes: (Case) -> Boolean = { true },
        formPost: Boolean = false,
        familyRules: Boolean = false,
        me: FamilyMe? = null,
        body: suspend SyncRig.(Case) -> Unit,
    ) {
        for (case in cases.filter(sizes)) {
            SyncRig(PlaylistWorld(case.seed, songs = 30 + case.size / 4), case.page, formPost, familyRules, me).use { rig ->
                rig.library()
                rig.body(case)
                assertSame(rig, "seed ${case.seed}, ${case.size} songs, page ${case.page}")
            }
        }
    }

    @Test
    fun importsAsToday() = timed(120) {
        each { case ->
            val entries = world.entries(case.size)
            serverPlaylist("srv", "Mix", entries)
            run(PlaylistStep.Import("srv", stampOf(stamp0, entries.size)))
            assertEquals(entries.size.toLong(), db.count("SELECT COUNT(*) FROM playlist_item"))
            if (case.size >= 120) {
                // Every kind came through: linked, matched by title only, found.
                assertTrue(db.count("SELECT COUNT(*) FROM playlist_item WHERE serverSongId LIKE 's%' AND trackId NOT LIKE 'find:%'") > 0)
                assertTrue(db.count("SELECT COUNT(*) FROM playlist_item WHERE serverSongId LIKE 'u%' AND trackId NOT LIKE 'find:%'") > 0)
                assertTrue(db.count("SELECT COUNT(*) FROM playlist_item WHERE serverSongId LIKE 'x%' AND trackId NOT LIKE 'find:%'") > 0)
                assertTrue(db.count("SELECT COUNT(*) FROM playlist_item WHERE trackId LIKE 'find:%'") > 0)
                assertTrue(db.count("SELECT COUNT(*) FROM online_song") > 0)
            }
        }
    }

    @Test
    fun importsOfAFamilyPlaylistAsToday() = timed(120) {
        each(sizes = { it.size <= 600 }, familyRules = true) { case ->
            val entries = world.entries(case.size)
            serverPlaylist("srv", "Ours", entries, owner = "Family", octoFamily = true)
            run(PlaylistStep.Import("srv", stampOf(stamp0, entries.size)))
        }
    }

    @Test
    fun aTwinIsLinkedAsToday() = timed(120) {
        each(sizes = { it.size in 1..600 }) { case ->
            val entries = world.entries(case.size)
            // The twin's rows are what an import of the same songs writes.
            val twin = SyncRig(world, page).use { scratch ->
                scratch.library()
                scratch.serverPlaylist("srv", "Mix", entries)
                scratch.oracle.run(scratch.serverOf(true), PlaylistStep.Import("srv", "x"))
                scratch.oracleDb.strings("SELECT trackId FROM playlist_item ORDER BY ord, itemKey")
            }
            phonePlaylist(PlaylistEntity("phone", "Mix", 1, 2), twin.map { it to null }, null)
            phonePlaylist(PlaylistEntity("other", "Mix", 1, 2), twin.drop(1).map { it to null }, null)
            serverPlaylist("srv", "Mix", entries)
            run(PlaylistStep.Import("srv", stampOf(stamp0, entries.size)))
            assertEquals("the twin is linked", "srv", db.strings("SELECT serverId FROM playlist WHERE id = 'phone'").single())
        }
    }

    @Test
    fun pullsAsToday() = timed(120) {
        each { case ->
            val entries = world.entries(case.size)
            serverPlaylist("srv", "Server name", entries)
            phonePlaylist(linkedRow("old|0"), world.phoneRows(case.size / 2 + 3), null)
            run(PlaylistStep.Pull("p", "srv", stampOf(stamp0, entries.size), keepName = false))
        }
    }

    @Test
    fun pullsKeepingTheNameAsToday() = timed(120) {
        each(sizes = { it.size <= 600 }) { case ->
            val entries = world.entries(case.size)
            serverPlaylist("srv", "Server name", entries)
            phonePlaylist(linkedRow("old|0", edited = true, name = "Phone name"), world.phoneRows(case.size / 3 + 2), null)
            run(PlaylistStep.Pull("p", "srv", stampOf(stamp0, entries.size), keepName = true))
        }
    }

    @Test
    fun aFamilyPullKeepsKeysAsToday() = timed(120) {
        each { case ->
            val entries = world.entries(case.size)
            serverPlaylist("srv", "Ours", entries, owner = "Family", octoFamily = true)
            phonePlaylist(linkedRow("old|0", family = true, add = false, remove = false), world.phoneRows(case.size + 2), world.base(entries))
            val before = db.count("SELECT MAX(itemKey) FROM playlist_item")
            run(PlaylistStep.Pull("p", "srv", stampOf(stamp0, entries.size), keepName = false))
            // Rows of songs the phone already had keep their keys.
            if (case.size >= 40) assertTrue(db.count("SELECT COUNT(*) FROM playlist_item WHERE itemKey <= ?", before) > 0)
        }
    }

    @Test
    fun pushesAsToday() = timed(120) {
        // The same songs: nothing is sent but the name.
        each(sizes = { it.size <= 600 }) { case ->
            val natives = world.linked.take(case.size.coerceAtMost(world.linked.size))
            val rows = natives.map { native -> world.copies.first { it.nativeId == native }.mergedId to native }
            serverPlaylist("srv", "Mix", natives)
            phonePlaylist(linkedRow(stampOf(stamp0, natives.size), edited = true, name = "Renamed"), rows, null)
            run(PlaylistStep.Push("p", "srv"))
        }
        // Other songs: they replace the server's, in batches without a form body.
        each { case ->
            val entries = world.entries(case.size)
            serverPlaylist("srv", "Mix", entries)
            phonePlaylist(linkedRow(stampOf(stamp0, entries.size), edited = true), world.phoneRows(case.size + 3), null)
            run(PlaylistStep.Push("p", "srv"))
            assertTrue(server.changeCalls().any { it.endpoint == "createPlaylist" })
        }
        each(sizes = { it.size >= 40 }, formPost = true) { case ->
            val entries = world.entries(case.size)
            serverPlaylist("srv", "Mix", entries)
            phonePlaylist(linkedRow(stampOf(stamp0, entries.size), edited = true), world.phoneRows(case.size + 3), null)
            run(PlaylistStep.Push("p", "srv"))
        }
        // The server changed too: settled like a pull.
        each(sizes = { it.size <= 120 }) { case ->
            val entries = world.entries(case.size)
            serverPlaylist("srv", "Mix", entries)
            phonePlaylist(linkedRow("old|0", edited = true), world.phoneRows(case.size + 3), null)
            run(PlaylistStep.Push("p", "srv"))
        }
    }

    // Every branch of familySend: removals by song, by place with the
    // listener's details known and not, clean only, with a form body, no
    // base kept, and pull only.
    @Test
    fun familyPushesAsToday() = timed(240) {
        val small = { case: Case -> case.size in 1..120 }
        val branches = listOf(
            Triple(true, false, null),
            Triple(false, false, FamilyMe(abilities = FamilyAbilities(cleanOnly = false))),
            Triple(false, false, FamilyMe(abilities = FamilyAbilities(cleanOnly = true))),
            Triple(false, false, null),
            Triple(true, true, null),
            Triple(false, true, FamilyMe(abilities = FamilyAbilities(cleanOnly = false))),
        )
        var removals = 0
        var adds = 0
        var held = 0
        for ((familyRules, formPost, me) in branches) {
            each(sizes = if (formPost) { _ -> true } else small, formPost = formPost, familyRules = familyRules, me = me) { case ->
                val entries = world.entries(case.size)
                serverPlaylist("srv", "Ours", entries, owner = "Family", octoFamily = true)
                val base = world.base(entries)
                phonePlaylist(linkedRow(stampOf(stamp0, entries.size), family = true, edited = true, name = "Ours 2"), world.phoneRows(case.size + 2), base)
                run(PlaylistStep.Push("p", "srv"))
                for (call in server.changeCalls()) {
                    removals += call.params.count { it.first == "songIndexToRemove" || it.first == "octoSongToRemove" }
                    adds += call.params.count { it.first == "songIdToAdd" }
                }
                if (db.count("SELECT COUNT(*) FROM playlist WHERE syncedAt = 3") > 0) held++
            }
        }
        assertTrue("removals were sent ($removals), songs added ($adds), removals held ($held)", removals > 0 && adds > 0 && held > 0)
        each(sizes = small) { case ->
            val entries = world.entries(case.size)
            serverPlaylist("srv", "Ours", entries, owner = "Family", octoFamily = true)
            phonePlaylist(linkedRow(stampOf(stamp0, entries.size), family = true, edited = true), world.phoneRows(case.size + 2), null)
            run(PlaylistStep.Push("p", "srv"))
        }
        each(sizes = small) { case ->
            val entries = world.entries(case.size)
            serverPlaylist("srv", "Ours", entries, owner = "Family", octoFamily = true)
            phonePlaylist(linkedRow(stampOf(stamp0, entries.size), family = true, edited = true, add = false, remove = false), world.phoneRows(case.size + 2), world.base(entries))
            run(PlaylistStep.Push("p", "srv"))
        }
    }

    @Test
    fun createsAsToday() = timed(120) {
        each { case ->
            phonePlaylist(PlaylistEntity("p", "New", 1, 2, sourceId = SOURCE), world.phoneRows(case.size), null)
            run(PlaylistStep.Create("p"))
        }
        each(sizes = { it.size >= 40 }, formPost = true) { case ->
            phonePlaylist(PlaylistEntity("p", "New", 1, 2, sourceId = SOURCE), world.phoneRows(case.size), null)
            run(PlaylistStep.Create("p"))
        }
    }

    @Test
    fun removesAsToday() = timed(120) {
        each(sizes = { it.size <= 600 }) { case ->
            phonePlaylist(linkedRow("s"), world.phoneRows(case.size), world.base(world.entries(case.size)))
            phonePlaylist(linkedRow("s", edited = true).copy(id = "q", serverId = "srv2"), world.phoneRows(3), world.base(world.entries(3)))
            run(PlaylistStep.Remove("p"))
            run(PlaylistStep.Remove("q"))
        }
    }

    // Both sides after a step, and the streamed side's leftovers.
    private fun assertSame(rig: SyncRig, case: String) {
        assertEquals("$case: playlists", stateOf(rig.oracleDb, oracleSide = true), stateOf(rig.db, oracleSide = false))
        assertEquals("$case: finds", finds(rig.oracleDb), finds(rig.db))
        assertEquals("$case: server playlists", rig.oracleServer.playlists.values.map { "${it.id} ${it.name} ${it.entries}" }, rig.server.playlists.values.map { "${it.id} ${it.name} ${it.entries}" })
        assertEquals("$case: calls", rig.oracleServer.calls.map { "${it.method} ${it.endpoint} ${it.params}" }, rig.server.calls.map { "${it.method} ${it.endpoint} ${it.params}" })
        for (table in PLAYLIST_WORK_TABLES + "playlist_set_pin" + "playlist_edit_log") assertEquals("$case: $table", 0L, rig.db.count("SELECT COUNT(*) FROM $table"))
        assertEquals("$case: old sets", 0L, rig.db.count("SELECT COUNT(*) FROM playlist_item i JOIN playlist p ON p.id = i.playlistId WHERE i.itemSet != p.itemSet"))
        assertEquals("$case: old bases", 0L, rig.db.count("SELECT COUNT(*) FROM playlist_synced_song b JOIN playlist p ON p.id = b.playlistId WHERE b.baseSet != p.baseSet"))
        assertTrue("$case: drain files", rig.drains.folder.listFiles().isNullOrEmpty())
        assertEquals("$case: no cell written", 0L, rig.db.count("SELECT COUNT(*) FROM playlist WHERE syncedSongs IS NOT NULL"))
    }

    private fun stateOf(db: CatalogDatabase, oracleSide: Boolean): List<String> {
        val heads = db.query(
            "SELECT id, name, createdAt, updatedAt, serverId, sourceId, syncedAt, syncedName, serverStamp, octoNotice, family, " +
                "familyMayAdd, familyMayRemove, familyMayRename, familyMayDelete, itemSet, baseSet, syncedSongs, syncedSongsKept FROM playlist " +
                "WHERE itemSet >= 0",
            arrayOf<Any?>(),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val head = (1..14).joinToString("|") { c.getString(it) ?: "null" }
                    add(arrayOf<Any?>(c.getString(0), head, c.getLong(15), c.getLong(16), c.getString(17), c.getInt(18)))
                }
            }
        }
        return heads.map { row ->
            val id = row[0] as String
            val rows = db.strings(
                "SELECT itemKey || '|' || trackId || '|' || relinkKey || '|' || IFNULL(serverSongId, 'null') FROM playlist_item " +
                    "WHERE playlistId = ? AND itemSet = ? ORDER BY ord, itemKey",
                id, row[2],
            )
            val base = if (oracleSide) {
                syncedSongsOf(row[4] as String?)?.groupingBy { it }?.eachCount()?.toSortedMap()
            } else if (row[5] == 1) {
                db.strings("SELECT songId || '=' || copies FROM playlist_synced_song WHERE playlistId = ? AND baseSet = ?", id, row[3])
                    .associate { it.substringBeforeLast('=') to it.substringAfterLast('=').toInt() }.toSortedMap()
            } else {
                null
            }
            "${row[1]}\n$rows\nbase $base"
        }.sorted()
    }

    private fun finds(db: CatalogDatabase) = db.strings(
        "SELECT id || '|' || sourceId || '|' || nativeId || '|' || title || '|' || artist || '|' || album || '|' || IFNULL(albumId, '') || '|' || " +
            "durationMs || '|' || IFNULL(coverId, '') || '|' || IFNULL(mimeType, '') || '|' || seenAt FROM online_song ORDER BY id",
    )
}

internal fun CatalogDatabase.strings(sql: String, vararg args: Any?): List<String> =
    query(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

internal fun CatalogDatabase.count(sql: String, vararg args: Any?): Long = query(sql, args).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }

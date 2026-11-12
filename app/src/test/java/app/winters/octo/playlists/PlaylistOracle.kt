package app.winters.octo.playlists

import androidx.room.withTransaction
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistItemEntity
import app.winters.octo.catalog.isFind
import app.winters.octo.catalog.ordAt
import app.winters.octo.discovery.Resolved
import app.winters.octo.discovery.id
import app.winters.octo.discovery.resolveSongs
import app.winters.octo.discovery.titleKeys
import app.winters.octo.listening.parseServerTime
import app.winters.octo.playback.tracksByIds
import app.winters.octo.subsonic.FamilyMe
import app.winters.octo.subsonic.PlaylistWithSongs
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.access
import app.winters.octo.subsonic.isFamilyPlaylist
import app.winters.octo.subsonic.songsToRemove
import java.util.UUID

// Labeled pin: today's playlist sync steps (PlaylistSync.kt before round
// 1b, its rows on ord and itemKey as round 1b's schema keeps them), copied
// here as the oracle the streamed steps are compared with. Every list is
// held whole, as today: the server's playlist from client.playlist, the
// phone's rows from playlistItems, the base as the text cell syncedSongs
// (which the streamed steps no longer write), and today's resolution
// (Discovery.idsFromServer's resolveEach). Only the database writes today's
// UserDao made in one transaction are written here the same way.
internal class PlaylistOracle(
    private val db: CatalogDatabase,
    // The signed-in server's source, as Discovery.server() reads it.
    private val serverInUse: () -> String?,
    private val familyPlan: suspend () -> FamilyMe?,
    private val now: () -> Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val user = db.userDao()
    private val sources = db.sourceDao()
    private val catalog = db.dao()
    private val online = db.onlineDao()

    suspend fun run(server: SyncServer, step: PlaylistStep, me: FamilyMe? = null) {
        when (step) {
            is PlaylistStep.Import -> import(server, step.serverId, step.stamp, me)
            is PlaylistStep.Pull -> pull(server, step)
            is PlaylistStep.Push -> user.playlistRow(step.localId)?.let { push(server, it) }
            is PlaylistStep.Create -> user.playlistRow(step.localId)?.let { create(server, it) }
            is PlaylistStep.Remove -> {
                val playlist = user.playlistRow(step.localId) ?: return
                if (playlist.edited) unlinkPlaylist(playlist.id) else deleteUnchangedPlaylist(playlist.id, playlist.updatedAt)
            }
            is PlaylistStep.Unlink -> unlinkPlaylist(step.localId)
            else -> error("not a database step: $step")
        }
    }

    private suspend fun import(server: SyncServer, serverId: String, stamp: String, me: FamilyMe?) {
        val full = server.client.playlist(serverId)
        val rows = rowsOf(full) ?: return
        val isFamily = isFamilyPlaylist(full.owner, server.client.username, full.octoFamily)
        val access = full.access(server.client.username, me, server.familyRules)
        val songs = syncedSongsText(full.entry.map { it.id })
        val twin = user.phonePlaylists().filter { it.name == full.name }.firstOrNull { playlist ->
            user.playlistItems(playlist.id).map { it.trackId } == rows.map { it.trackId }
        }
        if (twin != null) {
            user.linkPlaylist(twin.id, serverId, server.sourceId, twin.updatedAt, twin.name, stamp)
            user.markFamily(twin.id, isFamily)
            if (isFamily) user.markFamilyRights(twin.id, access.add, access.remove, access.rename, access.delete)
            markFamilyBase(twin.id, songs)
            return
        }
        val at0 = now()
        val at = parseServerTime(full.changed)?.coerceAtMost(at0) ?: at0
        val id = newId()
        val row = PlaylistEntity(
            id = id,
            name = full.name,
            createdAt = parseServerTime(full.created)?.coerceAtMost(at) ?: at,
            updatedAt = at,
            serverId = serverId,
            sourceId = server.sourceId,
            syncedAt = at,
            syncedName = full.name,
            serverStamp = stamp,
            family = isFamily,
            retiredSyncedSongs = songs,
            familyMayAdd = isFamily && access.add,
            familyMayRemove = isFamily && access.remove,
            familyMayRename = isFamily && access.rename,
            familyMayDelete = isFamily && access.delete,
        )
        insertServerPlaylist(row, items(id, rows))
    }

    private suspend fun pull(server: SyncServer, step: PlaylistStep.Pull) {
        val before = user.playlistRow(step.localId) ?: return
        val full = server.client.playlist(step.serverId)
        val fromServer = rowsOf(full) ?: return
        val localItems = user.playlistItems(before.id)
        val local = localItems.map { Row(it.trackId, it.serverSongId) }
        val copies = serverCopies(server.sourceId, local.map { it.trackId })
        val rows = withPhoneOnly(fromServer, local) { serverSongFor(it, copies[it.trackId].orEmpty()) == null }
        val updated = if (step.keepName) {
            before.copy(serverStamp = step.stamp, retiredSyncedSongs = syncedSongsText(full.entry.map { it.id }))
        } else {
            val at = maxOf(now(), before.updatedAt + 1)
            before.copy(
                name = full.name,
                updatedAt = at,
                syncedAt = at,
                syncedName = full.name,
                serverStamp = step.stamp,
                retiredSyncedSongs = syncedSongsText(full.entry.map { it.id }),
            )
        }
        val kept = if (before.family) keptRowIds(localItems.map { it.id to it.trackId }, rows) else emptyList()
        if (!takeServerSongs(before, items(before.id, rows, kept, localItems), updated)) return
        if (step.keepName) {
            server.client.updatePlaylist(step.serverId, name = before.name)
            val stamp = listingStamp(server, step.serverId) ?: return
            user.markSynced(before.id, before.updatedAt, before.name, stamp)
        }
    }

    private suspend fun push(server: SyncServer, playlist: PlaylistEntity) {
        val serverId = playlist.serverId ?: return
        if (playlist.family) return pushFamily(server, playlist, serverId)
        val linked = playlist.linked() ?: return
        val client = server.client
        val listed = client.playlists().firstOrNull { it.id == serverId }
            ?.let { ServerPlaylist(it.id, it.name, stampOf(it.changed, it.songCount)) }
        if (listed == null || listed.stamp != playlist.serverStamp) {
            planLinked(listed, linked)?.let { run(server, it) }
            return
        }
        val stamp = listed.stamp
        val there = client.playlist(serverId)
        val songs = songsToSend(server, playlist.id)
        var sent = false
        if (there.entry.map { it.id } != songs) {
            client.replacePlaylistSongs(serverId, songs, server.formPost)
            sent = true
        }
        if (there.name != playlist.name) {
            client.updatePlaylist(serverId, name = playlist.name)
            sent = true
        }
        val after = if (sent) listingStamp(server, serverId) ?: return else stamp
        user.markSynced(playlist.id, playlist.updatedAt, playlist.name, after)
    }

    private suspend fun pushFamily(server: SyncServer, playlist: PlaylistEntity, serverId: String) {
        val client = server.client
        val there = client.playlist(serverId)
        val current = there.entry.map { it.id }
        val phone = familySongsToSend(server, playlist.id)
        val base = syncedSongsOf(playlist.retiredSyncedSongs) ?: phone
        val pullOnly = !playlist.familyMayAdd && !playlist.familyMayRemove
        var send = FamilySend(FamilyChanges(emptyList(), emptyList()), phone, emptyList())
        if (!pullOnly) {
            val wanted = familyChanges(base, phone, current)
            send = if (server.familyRules) {
                familySend(base, phone, current, listedCount = current.size, cleanOnly = false)
            } else {
                val cleanOnly = if (wanted.removeAt.isEmpty()) false else familyPlan()?.abilities?.cleanOnly
                val listedCount = if (wanted.removeAt.isEmpty() || cleanOnly != false) null else client.playlists().firstOrNull { it.id == serverId }?.songCount
                familySend(base, phone, current, listedCount, cleanOnly)
            }
            if (!send.changes.none) {
                try {
                    if (server.familyRules) {
                        client.updatePlaylist(
                            serverId,
                            songIdsToAdd = send.changes.add,
                            songsToRemove = songsToRemove(current, send.changes.removeAt),
                            formPost = server.formPost,
                        )
                    } else {
                        client.updatePlaylist(serverId, songIdsToAdd = send.changes.add, songIndexesToRemove = send.changes.removeAt, formPost = server.formPost)
                    }
                } catch (e: SubsonicException.Unreachable) {
                    val after = client.playlist(serverId).entry.map { it.id }
                    if (!familySendLanded(current, after, send.changes)) throw e
                }
                markFamilyBase(playlist.id, syncedSongsText(send.baseAfterSend))
            }
            if (playlist.name != playlist.syncedName && playlist.name != there.name) {
                try {
                    client.updatePlaylist(serverId, name = playlist.name)
                } catch (e: SubsonicException.Server) {
                    if (e.code != 50) throw e
                }
            }
        }
        val stamp = listingStamp(server, serverId) ?: return
        val full = client.playlist(serverId)
        val fromServer = rowsOf(full) ?: return
        val localItems = user.playlistItems(playlist.id)
        val local = localItems.map { Row(it.trackId, it.serverSongId) }
        val copies = serverCopies(server.sourceId, local.map { it.trackId })
        val rows = withoutHeld(withPhoneOnly(fromServer, local) { serverSongFor(it, copies[it.trackId].orEmpty()) == null }, send.held)
        val at = maxOf(now(), playlist.updatedAt + 1)
        val updated = playlist.copy(
            name = full.name,
            updatedAt = at,
            syncedAt = if (send.held.isEmpty()) at else playlist.syncedAt,
            syncedName = full.name,
            serverStamp = stamp,
            retiredSyncedSongs = syncedSongsText(full.entry.map { it.id }),
        )
        takeServerSongs(playlist, items(playlist.id, rows, keptRowIds(localItems.map { it.id to it.trackId }, rows), localItems), updated)
    }

    private suspend fun create(server: SyncServer, playlist: PlaylistEntity) {
        if (playlist.serverId != null || playlist.sourceId != server.sourceId) return
        val songs = songsToSend(server, playlist.id)
        val made = server.client.createPlaylist(playlist.name, songs, server.formPost)
        val serverId = made?.id ?: newestNamed(server, playlist.name) ?: return
        val first = made?.let { stampOf(it.changed, it.songCount) }.orEmpty()
        user.linkPlaylist(playlist.id, serverId, server.sourceId, playlist.updatedAt, playlist.name, first)
        listingStamp(server, serverId)?.let { user.markSynced(playlist.id, playlist.updatedAt, playlist.name, it) }
    }

    private suspend fun newestNamed(server: SyncServer, name: String): String? {
        val linked = user.serverPlaylists(server.sourceId).mapNotNullTo(HashSet()) { it.serverId }
        return newestNamedCandidates(server.client.playlists(), name, server.client.username, linked)
            .maxByOrNull { it.created.orEmpty() }?.id
    }

    private suspend fun songsToSend(server: SyncServer, localId: String): List<String> {
        val rows = user.playlistItems(localId).map { Row(it.trackId, it.serverSongId) }
        val copies = serverCopies(server.sourceId, rows.map { it.trackId })
        return rows.mapNotNull { serverSongFor(it, copies[it.trackId].orEmpty()) }
    }

    private suspend fun familySongsToSend(server: SyncServer, localId: String): List<String> {
        val rows = user.playlistItems(localId).map { Row(it.trackId, it.serverSongId) }
        val copies = serverCopies(server.sourceId, rows.map { it.trackId })
        return rows.mapNotNull { it.serverSongId ?: serverSongFor(it, copies[it.trackId].orEmpty()) }
    }

    private suspend fun serverCopies(sourceId: String, trackIds: List<String>): Map<String, List<String>> =
        trackIds.filterNot(::isFind).distinct().chunked(900)
            .flatMap { sources.copiesOf(it) }
            .filter { it.sourceId == sourceId }
            .groupBy({ it.mergedId }, { it.nativeId })

    private suspend fun rowsOf(playlist: PlaylistWithSongs): List<Row>? {
        val ids = idsFromServer(playlist.entry)
        if (ids.size != playlist.entry.size) return null
        return serverRows(playlist.entry.map { it.id }, ids)
    }

    // Discovery.idsFromServer and resolveEach as they are.
    private suspend fun idsFromServer(songs: List<Song>): List<String> {
        val sourceId = serverInUse() ?: return emptyList()
        if (songs.isEmpty()) return emptyList()
        val links = songs.map { it.id }.distinct().chunked(900)
            .flatMap { sources.libraryLinks(sourceId, it) }
            .associate { it.serverId to it.trackId }
        val unlinked = songs.filter { it.id !in links }
        val candidates = unlinked.flatMap { titleKeys(it.title) }.distinct().sorted().chunked(900).flatMap { catalog.tracksWithKeys(it) }
        val resolved = resolveSongs(songs, sourceId, links, candidates, now())
        val kept = online.keep(resolved.filterIsInstance<Resolved.Found>().map { it.song }.distinctBy { it.id }).associateBy { it.id }
        return resolved.map { r -> if (r is Resolved.Found) kept[r.song.id]?.let(Resolved::Found) ?: r else r }.map { it.id }
    }

    private suspend fun items(
        playlistId: String,
        rows: List<Row>,
        ids: List<Long?> = emptyList(),
        kept: List<PlaylistItemEntity> = emptyList(),
    ): List<PlaylistItemEntity> {
        val keys = catalog.tracksByIds(rows.map { it.trackId }.filterNot(::isFind)).associate { it.id to it.relinkKey }
        val itemKeys = kept.associate { it.id to it.itemKey }
        return rows.mapIndexed { position, row ->
            val id = ids.getOrNull(position) ?: 0
            PlaylistItemEntity(
                id = id,
                playlistId = playlistId,
                trackId = row.trackId,
                relinkKey = keys[row.trackId].orEmpty(),
                ord = ordAt(position),
                itemKey = itemKeys[id] ?: 0,
                serverSongId = row.serverSongId,
            )
        }
    }

    private suspend fun listingStamp(server: SyncServer, serverId: String): String? =
        server.client.playlists().firstOrNull { it.id == serverId }?.let { stampOf(it.changed, it.songCount) }

    // Today's UserDao writes, each as one transaction.

    private suspend fun insertServerPlaylist(row: PlaylistEntity, items: List<PlaylistItemEntity>) = db.withTransaction {
        user.insertPlaylist(row)
        insertRows(row.itemSet, items)
    }

    private suspend fun takeServerSongs(expected: PlaylistEntity, items: List<PlaylistItemEntity>, updated: PlaylistEntity): Boolean = db.withTransaction {
        val current = user.playlistRow(expected.id) ?: return@withTransaction false
        if (current.updatedAt != expected.updatedAt) return@withTransaction false
        exec("DELETE FROM playlist_item WHERE playlistId = ?", expected.id)
        insertRows(current.itemSet, items)
        exec(
            "UPDATE playlist SET name = ?, createdAt = ?, updatedAt = ?, serverId = ?, sourceId = ?, syncedAt = ?, syncedName = ?, " +
                "serverStamp = ?, octoNotice = ?, family = ?, syncedSongs = ?, familyMayAdd = ?, familyMayRemove = ?, " +
                "familyMayRename = ?, familyMayDelete = ? WHERE id = ?",
            updated.name, updated.createdAt, updated.updatedAt, updated.serverId, updated.sourceId, updated.syncedAt, updated.syncedName,
            updated.serverStamp, updated.octoNotice, updated.family, updated.retiredSyncedSongs, updated.familyMayAdd,
            updated.familyMayRemove, updated.familyMayRename, updated.familyMayDelete, updated.id,
        )
        true
    }

    private suspend fun insertRows(itemSet: Long, rows: List<PlaylistItemEntity>) {
        val fresh = rows.count { it.itemKey == 0L }
        var next = 0L
        if (fresh > 0) {
            user.raiseNextItemKey(fresh)
            next = user.nextItemKey() - fresh
        }
        val keyed = rows.map { it.copy(id = 0, itemSet = itemSet, itemKey = if (it.itemKey != 0L) it.itemKey else next++) }
        keyed.chunked(500).forEach { user.insertPlaylistItems(it) }
    }

    private fun markFamilyBase(id: String, songs: String) = exec("UPDATE playlist SET syncedSongs = ? WHERE id = ?", songs, id)

    private fun unlinkPlaylist(id: String) = exec(
        "UPDATE playlist SET serverId = NULL, sourceId = NULL, syncedAt = NULL, syncedName = NULL, serverStamp = NULL, " +
            "octoNotice = NULL, syncedSongs = NULL, family = 0, familyMayAdd = 0, familyMayRemove = 0, familyMayRename = 0, " +
            "familyMayDelete = 0 WHERE id = ?",
        id,
    )

    private fun deleteUnchangedPlaylist(id: String, updatedAt: Long) = exec("DELETE FROM playlist WHERE id = ? AND updatedAt = ?", id, updatedAt)

    private fun exec(sql: String, vararg args: Any?) =
        db.openHelper.writableDatabase.execSQL(sql, args.map { if (it is Boolean) (if (it) 1 else 0) else it }.toTypedArray())
}

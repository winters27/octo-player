package app.winters.octo.playlists

import app.winters.octo.catalog.FIND_PREFIX
import app.winters.octo.catalog.isFind
import app.winters.octo.subsonic.Playlist

// Keeps playlists on the phone and on the server the same, both ways.
//
// Each linked playlist is compared with what both sides last agreed on:
// the phone's side changed when it was edited after that (updatedAt past
// syncedAt), the server's when its changed time or song count differ from
// the ones noted then. Only one side changed: that side's playlist is
// copied to the other. Both changed: the server wins for the songs, and a
// name changed on the phone wins over the server's and is sent there.
// A playlist gone from the server goes from the phone too, unless it was
// changed here since; then it stays as a playlist only on the phone.

// Octo's stations are also read-only playlists with ids starting "or".
private const val STATION_PREFIX = "or"

// A server playlist as the list of them shows it. The stamp is its changed
// time and song count together, which move whenever it changes.
data class ServerPlaylist(val id: String, val name: String, val stamp: String)

// A playlist on the phone linked to a server one, and what both sides last
// agreed on.
data class LinkedPlaylist(
    val localId: String,
    val serverId: String,
    val name: String,
    // Changed on the phone since both sides last agreed.
    val edited: Boolean,
    val syncedName: String?,
    val syncedStamp: String?,
)

sealed interface PlaylistStep {
    // New on the server: make a copy on the phone.
    data class Import(val serverId: String, val stamp: String) : PlaylistStep

    // Take the server's songs. With `keepName` the phone's name stays and
    // is sent to the server; otherwise the server's name is taken too.
    data class Pull(val localId: String, val serverId: String, val stamp: String, val keepName: Boolean) : PlaylistStep

    // Send the phone's songs and name.
    data class Push(val localId: String, val serverId: String) : PlaylistStep

    // Make a playlist from the phone on the server, and link the two.
    data class Create(val localId: String) : PlaylistStep

    // Gone from the server and not changed here: delete the phone's copy.
    data class Remove(val localId: String) : PlaylistStep

    // Gone from the server, or no longer one to keep in step, but changed
    // here: keep it as a playlist only on the phone.
    data class Unlink(val localId: String) : PlaylistStep

    // Deleted on the phone: delete it on the server.
    data class DeleteOnServer(val serverId: String) : PlaylistStep

    // A deletion still waiting for a playlist the server no longer has.
    data class ForgetDeletion(val serverId: String) : PlaylistStep
}

fun stampOf(changed: String?, songCount: Int) = "${changed.orEmpty()}|$songCount"

// Server playlists worth a copy on the phone: the signed-in user's own,
// leaving out read-only ones and stations, which the stations shelf shows.
fun importable(playlists: List<Playlist>, stationIds: Set<String>, username: String): List<Playlist> =
    playlists.filter { !it.readonly && it.id !in stationIds && ownedBy(it.owner, username) }

// Playlists that could be stations the server did not mark read-only, so
// the list of stations is worth asking for.
fun stationSuspects(playlists: List<Playlist>): List<Playlist> =
    playlists.filter { !it.readonly && it.id.startsWith(STATION_PREFIX) }

// A playlist with no owner named, or when the user is not known (an API
// key the server did not name), counts as the user's own.
private fun ownedBy(owner: String?, username: String): Boolean =
    owner.isNullOrBlank() || username.isBlank() || owner.equals(username, ignoreCase = true)

// Everything that brings both sides together. `others` are server
// playlists that exist but are not to be kept in step; `waiting` are phone
// playlists to be made on the server; `deleted` are server playlists
// deleted on the phone and not yet on the server.
fun planPlaylists(
    server: List<ServerPlaylist>,
    others: Set<String>,
    linked: List<LinkedPlaylist>,
    waiting: List<String>,
    deleted: Set<String>,
): List<PlaylistStep> {
    val byId = server.associateBy { it.id }
    val steps = ArrayList<PlaylistStep>()
    deleted.forEach { steps += if (it in byId) PlaylistStep.DeleteOnServer(it) else PlaylistStep.ForgetDeletion(it) }
    linked.forEach { playlist -> planLinked(byId[playlist.serverId], playlist, playlist.serverId in others)?.let(steps::add) }
    val known = linked.mapTo(HashSet()) { it.serverId }
    server.filter { it.id !in known && it.id !in deleted }.forEach { steps += PlaylistStep.Import(it.id, it.stamp) }
    waiting.forEach { steps += PlaylistStep.Create(it) }
    return steps
}

// What one linked playlist needs, given the server's playlist (null when
// the server no longer lists it as one to keep). Nothing when both agree.
fun planLinked(server: ServerPlaylist?, playlist: LinkedPlaylist, stillThere: Boolean = false): PlaylistStep? {
    if (server == null) {
        return if (playlist.edited || stillThere) PlaylistStep.Unlink(playlist.localId) else PlaylistStep.Remove(playlist.localId)
    }
    val serverChanged = server.stamp != playlist.syncedStamp
    return when {
        serverChanged && playlist.edited -> {
            // Both changed: the server's songs, and the phone's name if it was renamed here.
            val renamedHere = playlist.name != playlist.syncedName && playlist.name != server.name
            PlaylistStep.Pull(playlist.localId, server.id, server.stamp, keepName = renamedHere)
        }
        serverChanged -> PlaylistStep.Pull(playlist.localId, server.id, server.stamp, keepName = false)
        playlist.edited -> PlaylistStep.Push(playlist.localId, server.id)
        else -> null
    }
}

// One song of a phone playlist as syncing sees it.
data class Row(val trackId: String, val serverSongId: String? = null)

// The server song to send for a row: the copy it came from while the song
// still has it, otherwise the song's copy on the server (the same one every
// time), otherwise the server song it came from, like a find. Null for a
// song only on the phone, which the server's copy goes without.
fun serverSongFor(row: Row, copies: List<String>): String? = when {
    row.serverSongId != null && row.serverSongId in copies -> row.serverSongId
    copies.isNotEmpty() -> copies.min()
    isFind(row.trackId) -> row.trackId.removePrefix(FIND_PREFIX)
    else -> row.serverSongId
}

// A server playlist's songs as phone rows: each server song with the
// library song or find it is. `trackIds` line up with `serverSongIds`.
fun serverRows(serverSongIds: List<String>, trackIds: List<String>): List<Row> =
    serverSongIds.zip(trackIds) { serverId, trackId -> Row(trackId, serverId) }

// The rows after taking the server's songs: the server's in its order, with
// each song only on the phone kept after the song it followed here, or at
// the start when none came before it.
fun withPhoneOnly(server: List<Row>, local: List<Row>, phoneOnly: (Row) -> Boolean): List<Row> {
    val firstAt = HashMap<String, Int>()
    server.forEachIndexed { index, row -> firstAt.putIfAbsent(row.trackId, index) }
    val after = HashMap<Int, MutableList<Row>>()
    var anchor = -1
    for (row in local) {
        if (phoneOnly(row)) {
            after.getOrPut(anchor) { ArrayList() } += row
        } else {
            firstAt[row.trackId]?.let { anchor = it }
        }
    }
    return buildList {
        after[-1]?.let(::addAll)
        server.forEachIndexed { index, row ->
            add(row)
            after[index]?.let(::addAll)
        }
    }
}

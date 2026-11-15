package app.winters.octo.playlists

import app.winters.octo.catalog.FIND_PREFIX
import app.winters.octo.catalog.isFind
import app.winters.octo.subsonic.Playlist
import app.winters.octo.subsonic.isFamilyPlaylist

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
    // A family playlist, whose own changes are sent on top of everyone else's.
    val family: Boolean = false,
    // A family playlist the listener may not change here, or an imported
    // playlist's copy: it only follows the server.
    val pullOnly: Boolean = false,
    // The listener's own playlist on a server listing octoPlaylistSongs:
    // changed on both sides, it sends its own changes on top of the
    // server's, as a family playlist does.
    val ownRules: Boolean = false,
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

// Server playlists worth a copy on the phone: the signed-in user's own and
// the family's, leaving out other read-only ones and stations, which the
// stations shelf shows. A family playlist a listener without editing may not
// change is kept as a copy that only follows the server.
// An imported playlist's copy (octoImport) comes too, as a copy that only
// follows the server.
fun importable(playlists: List<Playlist>, stationIds: Set<String>, username: String): List<Playlist> =
    playlists.filter {
        it.id !in stationIds &&
            (isFamilyPlaylist(it.owner, username, it.octoFamily) || it.octoImport || (!it.readonly && ownedBy(it.owner, username)))
    }

// The playlists a new one made on a server too old to answer with it
// can be: the user's own by that name not linked yet, never the family's.
fun newestNamedCandidates(playlists: List<Playlist>, name: String, username: String, linked: Set<String>): List<Playlist> =
    importable(playlists, emptySet(), username).filter {
        it.name == name && it.id !in linked && !it.octoImport && !isFamilyPlaylist(it.owner, username, it.octoFamily)
    }

// How long the phone's copy of the server's playlists counts as fresh when
// the playlists come on screen.
const val PLAYLISTS_FRESH_MS = 60_000L

// Whether the server's playlists are worth bringing in again now.
fun playlistsStale(now: Long, lastMs: Long): Boolean = now - lastMs >= PLAYLISTS_FRESH_MS

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
    if (playlist.pullOnly) {
        return if (serverChanged || playlist.edited) {
            PlaylistStep.Pull(playlist.localId, server.id, server.stamp, keepName = false)
        } else {
            null
        }
    }
    return when {
        serverChanged && playlist.edited -> if (playlist.family || playlist.ownRules) {
            // Both changed: a family playlist, and the listener's own on a
            // server listing octoPlaylistSongs, sends the phone's own changes
            // on top of everyone else's.
            PlaylistStep.Push(playlist.localId, server.id)
        } else {
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

// The server song to send for a row on a server listing octoPlaylistSongs:
// the server's own id for a row that came from it as a song found online
// (an id that is none of the song's copies and that the library links to
// no song: `libraryIds` are the row page's server ids the library links),
// never a library song matched to it by title; otherwise serverSongFor's.
// A library row whose copy moved keeps the id the server's list still holds.
fun outsideSongFor(row: Row, copies: List<String>, libraryIds: Set<String>): String? {
    val own = row.serverSongId
    return if (own != null && own !in copies && own !in libraryIds) own else serverSongFor(row, copies)
}

// The changed time a stamp (stampOf) holds, as the server sent it; null
// when it holds none.
fun changedOf(stamp: String?): String? = stamp?.substringBeforeLast('|', "")?.takeIf { it.isNotEmpty() }

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

// What the phone changed in a family playlist, as Octo takes it: songs
// taken out by their place in the server's list now, and songs added at
// the end. `base` is the server's songs when both last agreed, `phone`
// the songs the phone holds now, `current` the server's songs now. Only
// counts matter (a family playlist keeps the server's order): a song the
// phone has fewer of than `base` comes out of `current` that many times,
// last places first, unless someone else took it out already; a song the
// phone has more of goes on the end.
data class FamilyChanges(val removeAt: List<Int>, val add: List<String>) {
    val none: Boolean get() = removeAt.isEmpty() && add.isEmpty()
}

fun familyChanges(base: List<String>, phone: List<String>, current: List<String>): FamilyChanges {
    val had = base.groupingBy { it }.eachCount()
    val has = phone.groupingBy { it }.eachCount()
    val removeAt = had.flatMap { (id, count) ->
        val out = count - (has[id] ?: 0)
        if (out <= 0) emptyList() else current.indices.filter { current[it] == id }.takeLast(out)
    }.sorted()
    val seen = HashMap<String, Int>()
    val add = phone.filter { id ->
        val n = (seen[id] ?: 0) + 1
        seen[id] = n
        n > (had[id] ?: 0)
    }
    return FamilyChanges(removeAt, add)
}

// The songs both sides last agreed on, as the phone keeps them, or null
// when none were kept.
fun syncedSongsOf(stored: String?): List<String>? = stored?.split('\n')?.filter { it.isNotEmpty() }

fun syncedSongsText(ids: List<String>): String = ids.joinToString("\n")

// The ids rows keep when a playlist's songs are swapped for the
// server's: each new row takes the id of the first unused phone row of
// the same song, in order, so a row that is picked, or that Undo is
// about to take back out, is still there. Null for a row new to the phone.
fun keptRowIds(local: List<Pair<Long, String>>, rows: List<Row>): List<Long?> {
    val free = HashMap<String, ArrayDeque<Long>>()
    local.forEach { (id, trackId) -> free.getOrPut(trackId) { ArrayDeque() }.addLast(id) }
    return rows.map { free[it.trackId]?.removeFirstOrNull() }
}

// What a family push sends and keeps. `changes` go to the server;
// `baseAfterSend` is kept as the songs both sides agree on once they are
// there; `held` are the songs (one entry per copy) whose removal waits.
data class FamilySend(val changes: FamilyChanges, val baseAfterSend: List<String>, val held: List<String>)

// Decides a family push. Removals count places over the list Octo shows,
// so they wait while Octo lists fewer songs than it holds (`listedCount`,
// the playlist's count in the list of playlists, null when not read) and
// while it is not known whether the listener hears only clean songs
// (`cleanOnly` null). A listener who hears only clean songs is shown too
// few songs to count from, so their removals are dropped and the songs
// come back with the server's list.
fun familySend(base: List<String>, phone: List<String>, current: List<String>, listedCount: Int?, cleanOnly: Boolean?): FamilySend {
    val wanted = familyChanges(base, phone, current)
    if (wanted.removeAt.isEmpty()) return FamilySend(wanted, phone, emptyList())
    val adds = wanted.copy(removeAt = emptyList())
    if (cleanOnly == true) return FamilySend(adds, phone, emptyList())
    if (cleanOnly == null || listedCount != current.size) {
        return FamilySend(adds, base + wanted.add, wanted.removeAt.map { current[it] })
    }
    return FamilySend(wanted, phone, emptyList())
}

// Whether a family send reached the server although its answer was lost:
// every song it added is there as many more times as it was added, and
// every song it took out as many fewer. Changes by others meanwhile do not
// hide it.
fun familySendLanded(current: List<String>, after: List<String>, changes: FamilyChanges): Boolean {
    val before = current.groupingBy { it }.eachCount()
    val now = after.groupingBy { it }.eachCount()
    val added = changes.add.groupingBy { it }.eachCount()
    val removed = changes.removeAt.map { current[it] }.groupingBy { it }.eachCount()
    return added.all { (id, n) -> (now[id] ?: 0) >= (before[id] ?: 0) + n } &&
        removed.all { (id, n) -> (now[id] ?: 0) <= (before[id] ?: 0) - n }
}

// The rows after taking the server's songs, less the songs whose removal
// waits: for each held song, its last row from the server goes.
fun withoutHeld(rows: List<Row>, held: List<String>): List<Row> {
    val out = rows.toMutableList()
    for (id in held) {
        val at = out.indexOfLast { it.serverSongId == id }
        if (at >= 0) out.removeAt(at)
    }
    return out
}

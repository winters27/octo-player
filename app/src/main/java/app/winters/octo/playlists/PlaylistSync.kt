package app.winters.octo.playlists

import android.util.Log
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistItemEntity
import app.winters.octo.catalog.SourceDao
import app.winters.octo.catalog.UserDao
import app.winters.octo.catalog.isFind
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.discovery.Discovery
import app.winters.octo.listening.parseServerTime
import app.winters.octo.playback.tracksByIds
import app.winters.octo.subsonic.FORM_POST_EXTENSION
import app.winters.octo.subsonic.PlaylistWithSongs
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// How long after an edit it is sent, so quick changes go as one.
private const val SEND_DELAY_MS = 1_500L

// A playlist changed on the phone since both sides last agreed.
val PlaylistEntity.edited: Boolean get() = syncedAt == null || updatedAt > syncedAt

// Keeps playlists in step with the signed-in server (the rules are in
// PlaylistPlan.kt). The server's playlists come to the phone after each copy
// of its library; a linked playlist edited on the phone goes to the server
// shortly after, and again after the next copy if that failed. Playlists
// only on the phone stay there unless the listener saves one to the server
// or turns on saving new ones. Nothing here ever shows an error.
@Singleton
class PlaylistSync @Inject constructor(
    private val sessions: SessionRepository,
    private val user: UserDao,
    private val sources: SourceDao,
    private val catalog: CatalogDao,
    private val discovery: Discovery,
    private val store: PlaylistSyncStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // One change to the server's playlists at a time.
    private val lock = Mutex()

    // Edits waiting to be sent, by playlist.
    private val waits = HashMap<String, Job>()

    // Whether a server is signed in, so a playlist can be saved to it.
    val available: Flow<Boolean> = sessions.state.map { it is SessionState.SignedIn }

    val newOnServer: Flow<Boolean> = store.newOnServer

    fun setNewOnServer(on: Boolean) {
        scope.launch { store.setNewOnServer(on) }
    }

    // Runs after each copy of the server's library.
    suspend fun afterSync() {
        val server = signedIn() ?: return
        reconciledAt = System.currentTimeMillis()
        lock.withLock { reconcile(server) }
    }

    // When the server's playlists were last brought together with the
    // phone's.
    @Volatile private var reconciledAt = 0L

    // The playlists are on screen: one deleted or made on another device
    // since the last copy of the library is taken out or brought in now,
    // at most once a minute.
    fun freshen(now: Long = System.currentTimeMillis()) {
        if (!playlistsStale(now, reconciledAt)) return
        reconciledAt = now
        scope.launch {
            val server = signedIn() ?: return@launch
            lock.withLock { failureOf { reconcile(server) } }
        }
    }

    // A playlist was made on the phone. With saving new playlists turned
    // on, it is made on the server too.
    fun created(localId: String) {
        scope.launch {
            if (!store.newOnServer.first()) return@launch
            val server = signedIn() ?: return@launch
            user.keepOnServer(localId, server.sourceId)
            send(localId)
        }
    }

    // Makes this playlist on the server too, and keeps the two in step.
    fun saveToServer(localId: String) {
        scope.launch {
            val server = signedIn() ?: return@launch
            user.keepOnServer(localId, server.sourceId)
            send(localId)
        }
    }

    // A playlist changed on the phone. One kept with the server is sent
    // shortly; one only on the phone is left alone.
    fun changed(localId: String) {
        synchronized(waits) {
            waits.remove(localId)?.cancel()
            waits[localId] = scope.launch {
                delay(SEND_DELAY_MS)
                withContext(NonCancellable) { send(localId) }
            }
        }
    }

    // A playlist was deleted on the phone. One kept with the server is
    // deleted there too, now or after the next copy.
    fun deleted(playlist: PlaylistEntity) {
        val serverId = playlist.serverId ?: return
        scope.launch {
            val server = signedIn() ?: return@launch
            if (playlist.sourceId != server.sourceId) return@launch
            store.addDeleted(serverId)
            lock.withLock { failureOf { deleteOnServer(server, serverId) } }
        }
    }

    // After signing in (to this server) or out (null): playlists kept with
    // any other server become playlists only on the phone. None is deleted.
    suspend fun keepOnly(sourceId: String?) {
        user.unlinkOtherServers(sourceId)
        if (sourceId == null) store.forgetServer()
    }

    private suspend fun send(localId: String) {
        val server = signedIn() ?: return
        lock.withLock {
            val playlist = user.playlistRow(localId) ?: return@withLock
            if (playlist.sourceId != server.sourceId) return@withLock
            failureOf {
                when {
                    playlist.serverId == null -> create(server, playlist)
                    playlist.edited -> push(server, playlist)
                }
            }
        }
    }

    private suspend fun reconcile(server: Server) {
        val client = server.client
        val all = answerOf { client.playlists() } ?: return
        val suspects = stationSuspects(all)
        // Without the list of stations, a playlist that might be one is left out.
        val stations = if (suspects.isEmpty()) {
            emptySet()
        } else {
            answerOf { client.radioStations() }?.mapTo(HashSet()) { it.id } ?: suspects.mapTo(HashSet()) { it.id }
        }
        val keep = importable(all, stations, client.username)
        val kept = user.serverPlaylists(server.sourceId)
        val steps = planPlaylists(
            server = keep.map { ServerPlaylist(it.id, it.name, stampOf(it.changed, it.songCount)) },
            others = all.mapTo(HashSet()) { it.id } - keep.mapTo(HashSet()) { it.id },
            linked = kept.mapNotNull { it.linked() },
            waiting = kept.filter { it.serverId == null }.map { it.id },
            deleted = store.deleted(),
        )
        val failed = steps.count { failureOf { run(server, it) } != null }
        if (steps.isNotEmpty()) {
            // Counts only, like the library sync's line.
            val kinds = steps.groupingBy { it::class.simpleName }.eachCount().entries.joinToString { "${it.value} ${it.key}" }
            Log.i("Octo", "playlist sync: $kinds; $failed failed")
        }
    }

    private suspend fun run(server: Server, step: PlaylistStep) {
        when (step) {
            is PlaylistStep.Import -> import(server, step.serverId, step.stamp)
            is PlaylistStep.Pull -> pull(server, step)
            is PlaylistStep.Push -> user.playlistRow(step.localId)?.let { push(server, it) }
            is PlaylistStep.Create -> user.playlistRow(step.localId)?.let { create(server, it) }
            is PlaylistStep.Remove -> {
                val playlist = user.playlistRow(step.localId) ?: return
                // Changed here after all: it stays, only on the phone.
                if (playlist.edited) user.unlinkPlaylist(playlist.id) else user.deleteUnchangedPlaylist(playlist.id, playlist.updatedAt)
            }
            is PlaylistStep.Unlink -> user.unlinkPlaylist(step.localId)
            is PlaylistStep.DeleteOnServer -> deleteOnServer(server, step.serverId)
            is PlaylistStep.ForgetDeletion -> store.removeDeleted(step.serverId)
        }
    }

    // Makes a phone copy of a server playlist. A copy left on the phone by an
    // earlier sign-in, with the same name and songs, is linked again instead
    // of made twice.
    private suspend fun import(server: Server, serverId: String, stamp: String) {
        val full = server.client.playlist(serverId)
        val rows = rowsOf(full) ?: return
        val twin = user.phonePlaylists().filter { it.name == full.name }.firstOrNull { playlist ->
            user.playlistItems(playlist.id).map { it.trackId } == rows.map { it.trackId }
        }
        if (twin != null) {
            user.linkPlaylist(twin.id, serverId, server.sourceId, twin.updatedAt, twin.name, stamp)
            return
        }
        val now = System.currentTimeMillis()
        val at = parseServerTime(full.changed)?.coerceAtMost(now) ?: now
        val id = UUID.randomUUID().toString()
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
        )
        user.insertServerPlaylist(row, items(id, rows))
    }

    // Takes the server's songs into the phone's copy, keeping songs only on
    // the phone where they were. Skipped if the playlist is edited meanwhile;
    // the next sync settles it.
    private suspend fun pull(server: Server, step: PlaylistStep.Pull) {
        val before = user.playlistRow(step.localId) ?: return
        val full = server.client.playlist(step.serverId)
        val fromServer = rowsOf(full) ?: return
        val local = user.playlistItems(before.id).map { Row(it.trackId, it.serverSongId) }
        val copies = serverCopies(server.sourceId, local.map { it.trackId })
        val rows = withPhoneOnly(fromServer, local) { serverSongFor(it, copies[it.trackId].orEmpty()) == null }
        val updated = if (step.keepName) {
            // Still counts as changed here until the name reaches the server.
            before.copy(serverStamp = step.stamp)
        } else {
            val at = maxOf(System.currentTimeMillis(), before.updatedAt + 1)
            before.copy(name = full.name, updatedAt = at, syncedAt = at, syncedName = full.name, serverStamp = step.stamp)
        }
        if (!user.takeServerSongs(before, items(before.id, rows), updated)) return
        if (step.keepName) {
            server.client.updatePlaylist(step.serverId, name = before.name)
            val stamp = listingStamp(server, step.serverId) ?: return
            user.markSynced(before.id, before.updatedAt, before.name, stamp)
        }
    }

    // Sends the phone's songs and name. When the server changed too since
    // both last agreed, it is settled like a sync would instead.
    private suspend fun push(server: Server, playlist: PlaylistEntity) {
        val serverId = playlist.serverId ?: return
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

    // Makes a phone playlist on the server and links the two.
    private suspend fun create(server: Server, playlist: PlaylistEntity) {
        if (playlist.serverId != null || playlist.sourceId != server.sourceId) return
        val songs = songsToSend(server, playlist.id)
        val made = server.client.createPlaylist(playlist.name, songs, server.formPost)
        val serverId = made?.id ?: newestNamed(server, playlist.name) ?: return
        // Linked at once, so a failure from here on can never make it twice.
        val first = made?.let { stampOf(it.changed, it.songCount) }.orEmpty()
        user.linkPlaylist(playlist.id, serverId, server.sourceId, playlist.updatedAt, playlist.name, first)
        listingStamp(server, serverId)?.let { user.markSynced(playlist.id, playlist.updatedAt, playlist.name, it) }
    }

    private suspend fun deleteOnServer(server: Server, serverId: String) {
        try {
            server.client.deletePlaylist(serverId)
        } catch (_: SubsonicException.NotFound) {
            // Already gone.
        }
        store.removeDeleted(serverId)
    }

    // The server songs a phone playlist goes to the server as, in order,
    // without the songs only on the phone.
    private suspend fun songsToSend(server: Server, localId: String): List<String> {
        val rows = user.playlistItems(localId).map { Row(it.trackId, it.serverSongId) }
        val copies = serverCopies(server.sourceId, rows.map { it.trackId })
        return rows.mapNotNull { serverSongFor(it, copies[it.trackId].orEmpty()) }
    }

    // Each library song's copies on this server, by song.
    private suspend fun serverCopies(sourceId: String, trackIds: List<String>): Map<String, List<String>> =
        trackIds.filterNot(::isFind).distinct().chunked(900)
            .flatMap { sources.copiesOf(it) }
            .filter { it.sourceId == sourceId }
            .groupBy({ it.mergedId }, { it.nativeId })

    // A server playlist's songs as phone rows, or null when they could not
    // all be placed (the server was signed out meanwhile).
    private suspend fun rowsOf(playlist: PlaylistWithSongs): List<Row>? {
        val ids = discovery.idsFromServer(playlist.entry)
        if (ids.size != playlist.entry.size) return null
        return serverRows(playlist.entry.map { it.id }, ids)
    }

    private suspend fun items(playlistId: String, rows: List<Row>): List<PlaylistItemEntity> {
        val keys = catalog.tracksByIds(rows.map { it.trackId }.filterNot(::isFind)).associate { it.id to it.relinkKey }
        return rows.mapIndexed { position, row ->
            PlaylistItemEntity(
                playlistId = playlistId,
                trackId = row.trackId,
                relinkKey = keys[row.trackId].orEmpty(),
                position = position,
                serverSongId = row.serverSongId,
            )
        }
    }

    // The playlist's stamp as the server lists it now, or null when the
    // server no longer lists it. Always from the list of playlists, the
    // same place a sync compares with.
    private suspend fun listingStamp(server: Server, serverId: String): String? =
        server.client.playlists().firstOrNull { it.id == serverId }?.let { stampOf(it.changed, it.songCount) }

    // For a server too old to answer with the playlist it made: the newest
    // one of the user's by that name that no phone playlist is linked to.
    private suspend fun newestNamed(server: Server, name: String): String? {
        val linked = user.serverPlaylists(server.sourceId).mapNotNullTo(HashSet()) { it.serverId }
        return importable(server.client.playlists(), emptySet(), server.client.username)
            .filter { it.name == name && it.id !in linked }
            .maxByOrNull { it.created.orEmpty() }?.id
    }

    private fun PlaylistEntity.linked(): LinkedPlaylist? =
        serverId?.let { LinkedPlaylist(id, it, name, edited, syncedName, serverStamp) }

    private class Server(val client: SubsonicClient, val sourceId: String, val formPost: Boolean)

    // The signed-in server, if there is one. Playlists linked for another
    // user of the same server are let go first.
    private suspend fun signedIn(): Server? {
        val session = (sessions.state.value as? SessionState.SignedIn)?.session ?: return null
        val client = session.client
        if (store.useServer("${client.username}@${client.primaryUrl}")) user.unlinkOtherServers(null)
        return Server(client, session.sourceId, session.extensions.any { it.startsWith("$FORM_POST_EXTENSION:") })
    }

    // Runs a server call, handing back what went wrong, if anything.
    private suspend fun failureOf(call: suspend () -> Unit): SubsonicException? =
        try {
            call()
            null
        } catch (e: SubsonicException) {
            e
        }

    private suspend fun <T> answerOf(call: suspend () -> T): T? =
        try {
            call()
        } catch (e: SubsonicException) {
            null
        }
}

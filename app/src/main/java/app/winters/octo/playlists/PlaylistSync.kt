package app.winters.octo.playlists

import android.database.sqlite.SQLiteFullException
import android.util.Log
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.UserDao
import app.winters.octo.data.Drains
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.family.FamilyHub
import app.winters.octo.server.serverSourceId
import app.winters.octo.subsonic.FAMILY_PLAYLISTS_EXTENSION
import app.winters.octo.subsonic.FORM_POST_EXTENSION
import app.winters.octo.subsonic.PLAYLIST_SONGS_EXTENSION
import app.winters.octo.ui.common.Feedback
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.access
import app.winters.octo.subsonic.isFamilyPlaylist
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
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// How long after an edit it is sent, so quick changes go as one.
private const val SEND_DELAY_MS = 1_500L

// A playlist changed on the phone since both sides last agreed.
val PlaylistEntity.edited: Boolean get() = syncedAt == null || updatedAt > syncedAt

// Keeps playlists in step with the signed-in server (the rules are in
// PlaylistPlan.kt, the steps in PlaylistSteps.kt). The server's playlists
// come to the phone after each copy of its library; a linked playlist
// edited on the phone goes to the server shortly after, and again after
// the next copy if that failed. Playlists only on the phone stay there
// unless the listener saves one to the server or turns on saving new ones.
// Nothing here ever shows an error.
@Singleton
class PlaylistSync @Inject constructor(
    private val sessions: SessionRepository,
    private val user: UserDao,
    private val store: PlaylistSyncStore,
    private val family: FamilyHub,
    private val sets: PlaylistSets,
    private val drains: Drains,
    db: CatalogDatabase,
    merge: CatalogMerge,
    freeSpace: FreeSpace,
    private val folders: PlaylistFolderStore,
    feedback: Feedback,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val steps = PlaylistSteps(
        db,
        sets,
        merge,
        PlaylistReads(db, drains, freeSpace, {
            (sessions.state.value as? SessionState.SignedIn)?.session?.client?.let { serverSourceId(it.primaryUrl) }
        }),
        PlaylistWalks(db, sets),
        familyPlan = { family.plan() },
        forgetDeleted = { account, serverId -> store.removeDeleted(account, serverId) },
        notice = { feedback.show(it) },
        foldersChanged = { folders.later() },
    )

    // One change to the server's playlists at a time, each holder starting
    // with the one-time base step for its server.
    private val lock = SyncLock { server -> steps.ensureBase(server) }

    // Edits waiting to be sent, by playlist.
    private val waits = HashMap<String, Job>()

    // Whether a server is signed in, so a playlist can be saved to it.
    val available: Flow<Boolean> = sessions.state.map { it is SessionState.SignedIn }

    val newOnServer: Flow<Boolean> = store.newOnServer

    // At launch: what a sync or a playlist write cut off by the app's end
    // left behind goes, in the background.
    fun start() {
        drains.sweep()
        scope.launch { sets.sweep() }
    }

    fun setNewOnServer(on: Boolean) {
        scope.launch { store.setNewOnServer(on) }
    }

    // Runs after each copy of the server's library.
    suspend fun afterSync() {
        val server = signedIn() ?: return
        reconciledAt = System.currentTimeMillis()
        lock.withLock(server) { reconcile(server) }
        folders.freshen()
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
            lock.withLock(server) { failureOf { reconcile(server) } }
            folders.freshen()
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
            store.addDeleted(server.account, serverId)
            lock.withLock(server) { failureOf { steps.deleteOnServer(server, serverId) } }
        }
    }

    // Playlists kept with a server no longer kept (by its source) become
    // playlists only on the phone. None is deleted.
    suspend fun keepOnly(kept: Set<String>) {
        sets.unlinkServersExcept(kept.toList())
    }

    // Forgets a server taken off the list: its deletions still waiting.
    suspend fun forget(account: String, sourceId: String?) = store.forget(account, sourceId)

    private suspend fun send(localId: String) {
        val server = signedIn() ?: return
        lock.withLock(server) {
            val playlist = user.playlistRow(localId) ?: return@withLock
            if (playlist.sourceId != server.sourceId) return@withLock
            failureOf {
                when {
                    playlist.serverId == null -> steps.create(server, playlist)
                    playlist.edited -> steps.push(server, playlist)
                }
            }
        }
    }

    private suspend fun reconcile(server: SyncServer) {
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
        // The listener's family details, read once, and only when a family playlist is there.
        val me = if (keep.any { isFamilyPlaylist(it.owner, client.username, it.octoFamily) }) family.plan() else null
        val kept = user.serverPlaylists(server.sourceId)
        val steps = planPlaylists(
            server = keep.map { ServerPlaylist(it.id, it.name, stampOf(it.changed, it.songCount)) },
            others = all.mapTo(HashSet()) { it.id } - keep.mapTo(HashSet()) { it.id },
            linked = kept.mapNotNull { it.linked(ownRules = server.playlistSongs) },
            waiting = kept.filter { it.serverId == null }.map { it.id },
            deleted = store.deleted(server.account),
        )
        val counts = all.associate { it.id to it.songCount }
        val failed = steps.count { failureOf { this.steps.run(server, it, me, counts) } != null }
        val notices = keep.associate { it.id to it.octoNotice }
        for (row in user.serverPlaylists(server.sourceId)) {
            val notice = row.serverId?.let(notices::get)
            if (row.serverId != null && notice != row.octoNotice) user.markNotice(row.id, notice)
        }
        // Octo's library upkeep lists and imported playlists' copies, as the server marks them.
        val marked = keep.associateBy { it.id }
        for (row in user.serverPlaylists(server.sourceId)) {
            val playlist = row.serverId?.let(marked::get) ?: continue
            if (playlist.octoUpkeep != row.upkeep || playlist.octoUpkeepKind != row.upkeepKind) {
                user.markUpkeep(row.id, playlist.octoUpkeep, playlist.octoUpkeepKind)
            }
            if (playlist.octoImport != row.octoImport) user.markImport(row.id, playlist.octoImport)
        }
        // Which of them the server lists as the family's, and what the
        // listener may do with each.
        val listed = keep.associateBy { it.id }
        for (row in user.serverPlaylists(server.sourceId)) {
            val playlist = row.serverId?.let(listed::get) ?: continue
            val isFamily = isFamilyPlaylist(playlist.owner, client.username, playlist.octoFamily)
            if (isFamily != row.family) user.markFamily(row.id, isFamily)
            if (!isFamily) continue
            val access = playlist.access(client.username, me, server.familyRules)
            if (access.add != row.familyMayAdd || access.remove != row.familyMayRemove ||
                access.rename != row.familyMayRename || access.delete != row.familyMayDelete
            ) {
                user.markFamilyRights(row.id, access.add, access.remove, access.rename, access.delete)
            }
        }
        if (steps.isNotEmpty()) {
            // Counts only, like the library sync's line.
            val kinds = steps.groupingBy { it::class.simpleName }.eachCount().entries.joinToString { "${it.value} ${it.key}" }
            Log.i("Octo", "playlist sync: $kinds; $failed failed")
        }
    }

    // The signed-in server, if there is one. Playlists linked for another
    // account on the same address are let go first; other servers' links
    // stay for when they are in use again.
    private suspend fun signedIn(): SyncServer? {
        val session = (sessions.state.value as? SessionState.SignedIn)?.session ?: return null
        if (store.claim(session.sourceId, session.id)) sets.unlinkServer(session.sourceId)
        return SyncServer(
            session.client,
            session.sourceId,
            session.id,
            session.extensions.any { it.startsWith("$FORM_POST_EXTENSION:") },
            session.extensions.any { it.startsWith("$FAMILY_PLAYLISTS_EXTENSION:") },
            session.extensions.any { it.startsWith("$PLAYLIST_SONGS_EXTENSION:") },
        )
    }

    // Runs a step, handing back what went wrong, if anything: a server
    // call that failed, or too little room on the phone for the step,
    // which leaves the playlist as it was.
    private suspend fun failureOf(call: suspend () -> Unit): Exception? =
        try {
            call()
            null
        } catch (e: SubsonicException) {
            e
        } catch (e: PlaylistNoRoom) {
            Log.w("Octo", "playlist sync: not enough room, ${e.neededBytes} bytes asked")
            e
        } catch (e: SQLiteFullException) {
            Log.w("Octo", "playlist sync: the disk is full")
            e
        }

    private suspend fun <T> answerOf(call: suspend () -> T): T? =
        try {
            call()
        } catch (e: SubsonicException) {
            null
        }
}

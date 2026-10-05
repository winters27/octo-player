package app.winters.octo.playback

import android.util.Log
import app.winters.octo.admin.OctoAdmin
import app.winters.octo.data.Session
import app.winters.octo.data.SessionHandoff
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.data.SignInError
import app.winters.octo.data.SignInRequest
import app.winters.octo.data.StoredServer
import app.winters.octo.data.SwitchResult
import app.winters.octo.data.accountId
import app.winters.octo.listening.FavouriteSyncStore
import app.winters.octo.listening.ListeningStore
import app.winters.octo.listening.ListeningSync
import app.winters.octo.livelists.LiveListStore
import app.winters.octo.offline.OfflineDownloads
import app.winters.octo.playlists.PlaylistSync
import app.winters.octo.playlists.RecentPlaylists
import app.winters.octo.server.PHONE_LIBRARY
import app.winters.octo.server.QueueSync
import app.winters.octo.server.ServerSync
import app.winters.octo.server.sourceId
import app.winters.octo.server.switchNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

// How long the queue waits for the new server's library before coming back
// anyway.
private const val LIBRARY_WAIT_MS = 30_000L

// What the playback service does as the server in use changes.
interface PlayerSide {
    // Stops what plays for the server being left: the last song counts,
    // casting ends and the player is emptied. Answers the queue as it was,
    // and whether music was playing.
    suspend fun leave(): LeftQueue

    // Brings the queue in use back into the player, paused where it was,
    // stopping whatever plays first.
    suspend fun restore()

    // The queue in use did not change: the player carries on as it is.
    suspend fun keep()

    // The library changed under a car's browse tree.
    fun libraryChanged()
}

class LeftQueue(val queue: QueueSnapshot?, val wasPlaying: Boolean)

// How a switch asked for from the Servers list went.
sealed interface SwitchOutcome {
    // In use now; `notice` says so, and plainly when music was stopped.
    class Done(val notice: String?) : SwitchOutcome

    // Its password is not kept here, or the server no longer takes it.
    class NeedsPassword(val server: StoredServer, val note: String?) : SwitchOutcome

    class Failed(val message: String) : SwitchOutcome
}

// Moves the phone from one server to another, as the desktop's arrive()
// does: before the server in use changes, what was playing stops and counts
// for the server it played from, its queue goes to that server and waits on
// the phone for when it comes back; once the new server's library is in
// place, its own queue comes back, paused, and a car's browse tree is read
// again. Signing in, switching, signing out and removing all go through it.
@Singleton
class ServerSwitch @Inject constructor(
    private val sessions: SessionRepository,
    private val handoff: SessionHandoff,
    private val queue: QueueStore,
    private val sync: ServerSync,
    private val serverQueue: QueueSync,
    private val listening: ListeningSync,
    private val listeningStore: ListeningStore,
    private val favouriteStore: FavouriteSyncStore,
    private val playlists: PlaylistSync,
    private val recent: RecentPlaylists,
    private val liveLists: LiveListStore,
    private val offline: OfflineDownloads,
    private val admin: OctoAdmin,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    @Volatile private var player: PlayerSide? = null

    // Set by a leave, for the change of server it came before.
    @Volatile private var handedOver = false
    @Volatile private var stoppedMusic = false

    // The id of the server being switched to, while it is asked.
    private val _switching = MutableStateFlow<String?>(null)
    val switching: StateFlow<String?> = _switching

    fun start() {
        if (started) return
        started = true
        handoff.leaving = SessionHandoff.Leaving { from, to -> leave(from, to) }
        scope.launch {
            var before: String? = null
            sessions.state
                .filter { it !is SessionState.Loading }
                .map { it.accountId ?: PHONE_LIBRARY }
                .distinctUntilChanged()
                .collect { now ->
                    val from = before
                    before = now
                    // At start the service brings the saved queue back itself.
                    if (from != null) arrived(from, now)
                }
        }
    }

    // The playback service, while it runs.
    fun attach(side: PlayerSide) {
        player = side
    }

    fun detach(side: PlayerSide) {
        if (player === side) player = null
    }

    // Makes another kept server the one in use, and says how it went. It
    // runs on its own, so a page closed meanwhile never cuts it in half.
    suspend fun switchTo(id: String): SwitchOutcome = scope.async {
        if (_switching.value != null) return@async SwitchOutcome.Failed("Still switching. Try again in a moment.")
        _switching.value = id
        try {
            stoppedMusic = false
            when (val result = sessions.switchTo(id)) {
                is SwitchResult.Done -> SwitchOutcome.Done(switchNotice(result.session.name, result.from?.name, stoppedMusic && result.from != null))
                is SwitchResult.NeedsPassword -> SwitchOutcome.NeedsPassword(result.server, result.note)
                is SwitchResult.Failed -> SwitchOutcome.Failed(result.message)
            }
        } finally {
            _switching.value = null
        }
    }.await()

    // Signs in and makes the server the one in use. Answers what went wrong,
    // or the notice to show: the switch's, when another was in use before.
    suspend fun signIn(request: SignInRequest): Pair<SignInError?, String?> = scope.async {
        val before = (sessions.state.value as? SessionState.SignedIn)?.session
        stoppedMusic = false
        val failed = sessions.signIn(request)
        if (failed != null) return@async failed to null
        val now = (sessions.state.value as? SessionState.SignedIn)?.session ?: return@async null to null
        val from = before?.takeIf { it.id != now.id }
        null to switchNotice(now.name, from?.name, stoppedMusic && from != null)
    }.await()

    // Runs a change to the kept servers on its own, so a page closed
    // meanwhile never cuts it in half.
    suspend fun <T> onItsOwn(change: suspend () -> T): T = scope.async { change() }.await()

    // Signs out of a kept server, the one in use included. It stays listed.
    fun signOut(id: String) {
        scope.launch {
            if (sessions.state.value.accountId == id) sync.disconnect() else sessions.signOut(id)
        }
    }

    // Takes a server off the list, with its copy of the library. With
    // `forgetHere`, what the phone keeps for it goes too: its queue, plays
    // waiting to be sent, live lists, downloads, where its Octo admin pages
    // answer and the rest; otherwise it stays in case the server is added
    // again.
    fun remove(id: String, forgetHere: Boolean) {
        scope.launch {
            val gone = sync.remove(id) ?: return@launch
            if (!forgetHere) return@launch
            queue.forget(id)
            listeningStore.forget(id)
            favouriteStore.forget(id)
            playlists.forget(id, gone.sourceId)
            recent.forget(id)
            liveLists.forget(id)
            serverQueue.forget(id)
            admin.forget(id)
            // Another kept account on the same address keeps the downloads.
            val shared = sessions.servers.value.servers.any { it.sourceId == gone.sourceId }
            if (!shared) gone.sourceId?.let { offline.forgetSource(it) }
        }
    }

    // Before the server in use changes: what plays stops and counts for it,
    // its queue goes to it and waits here, and the next one's comes in.
    private suspend fun leave(from: Session, to: String?) {
        val side = player
        val left = side?.leave()
        stoppedMusic = left?.wasPlaying == true
        // The last song's play is noted for the server it played from.
        listening.noted()
        left?.queue?.let { serverQueue.leaving(it) }
        val next = to ?: PHONE_LIBRARY
        queue.handOver(from.id, left?.queue, next, keepWhenNone = false)
        handedOver = true
        // The player waits for the next server's library on its own, even
        // should another switch follow at once.
        scope.launch { bringBack(next, changed = true) }
    }

    // After the server in use changed without a leave (none was in use):
    // the phone's queue keeps playing, unless the server signed in to left
    // one here.
    private suspend fun arrived(from: String, to: String) {
        if (handedOver) {
            handedOver = false
            return
        }
        bringBack(to, queue.handOver(from, null, to, keepWhenNone = true))
    }

    // Once the library of `to` is in place, its queue comes back into the
    // player, and a car reads its tabs again.
    private suspend fun bringBack(to: String, changed: Boolean) {
        serverQueue.arrived()
        val side = player ?: return
        if (withTimeoutOrNull(LIBRARY_WAIT_MS) { sync.ready.first { it == to } } == null) {
            Log.w("Octo", "switch: the library was not ready in time")
        }
        if (changed) side.restore() else side.keep()
        side.libraryChanged()
    }
}

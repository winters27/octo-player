package app.winters.octo.server

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.catalog.SourceDao
import app.winters.octo.data.Drains
import app.winters.octo.data.Session
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.playback.QueueSnapshot
import app.winters.octo.subsonic.INDEX_BASED_QUEUE
import app.winters.octo.subsonic.PlayQueue
import app.winters.octo.subsonic.PlayQueueByIndex
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

// The name the app gives servers as its client (SubsonicClient's `c`). A
// queue saved under any other name came from another app or device.
const val OUR_CLIENT_NAME = "Octo"

// How long the queue has to stay put before it is saved on the server.
private const val SAVE_AFTER_MS = 10_000L

// Coming back to the front more often than this asks the server only once.
private const val CHECK_EVERY_MS = 30_000L

// How long the queue has to reach a server being left.
private const val LEAVE_SAVE_MS = 3_000L

// The phone's queue as a queue for the server. Songs the server does not
// have are left out. With shuffle on, the songs go in the order they will
// play, so the other device carries on with the same songs next. When the
// current song is not on the server, the next one that is takes its place,
// from its start. Null when no song is on the server.
fun serverQueueOf(snapshot: QueueSnapshot, serverIdOf: (String) -> String?): ServerQueue? {
    val ids = snapshot.trackIds
    val shuffled = snapshot.shuffle && snapshot.shuffleOrder.size == ids.size && snapshot.shuffleOrder.toSet() == ids.indices.toSet()
    val order = if (shuffled) snapshot.shuffleOrder else ids.indices.toList()
    // Each song on the server: its place in play order, and its server id.
    val kept = order.mapIndexedNotNull { rank, position -> serverIdOf(ids[position])?.let { Triple(rank, position, it) } }
    if (kept.isEmpty()) return null
    val serverIds = kept.map { it.third }
    val exact = kept.indexOfFirst { it.second == snapshot.index }
    if (exact >= 0) return ServerQueue(serverIds, exact, snapshot.positionMs.coerceAtLeast(0))
    val currentRank = order.indexOf(snapshot.index)
    val next = kept.indexOfFirst { it.first > currentRank }
    return ServerQueue(serverIds, if (next >= 0) next else kept.lastIndex, 0)
}

// The queue saved on the server as an offer reads it, never holding its
// songs: the answer drained to a file, read once for its head and how many
// songs it has, then once more for the song it was on (by place, or the
// first with the current song's id, else the first), as remoteQueueOf has
// it. Its songs are that one song. Null when the server keeps no queue.
internal suspend fun offeredQueue(client: SubsonicClient, byIndex: Boolean, drains: Drains): RemoteQueue? =
    drains.use("server-offer-${System.nanoTime()}.json") { file ->
        client.drainPlayQueue(byIndex, file)
        var count = 0
        val head = client.readPlayQueue(file, byIndex) { songs -> count += songs.size }
        if (count == 0) return@use null
        val wanted = if (byIndex) (head.currentIndex ?: 0).coerceIn(0, count - 1) else -1
        var at = 0
        var byId: Song? = null
        var first: Song? = null
        var indexed: Song? = null
        client.readPlayQueue(file, byIndex) { songs ->
            for (song in songs) {
                if (at == 0) first = song
                if (at == wanted) indexed = song
                if (!byIndex && byId == null && song.id == head.currentId) byId = song
                at++
            }
        }
        val current = if (byIndex) indexed else byId ?: first
        current?.let {
            RemoteQueue(
                songs = listOf(it),
                index = 0,
                // A position only means something in the song it was saved for.
                positionMs = if (byIndex || byId != null) head.positionMs.coerceAtLeast(0) else 0,
                changedAt = serverTime(head.changed),
                changedBy = head.changedBy,
            )
        }
    }

// An offer to carry on from another device.
class ResumeOffer(val device: String, val title: String, val artist: String?, internal val remote: RemoteQueue)

private val Context.queueData by preferencesDataStore("server_queue")

// Keeps the play queue on the server, so another device (or another app)
// can carry on from it, and offers to carry on from a queue saved elsewhere.
// Saves a short while after the queue or the song changes, and at a pause.
@Singleton
class QueueSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionRepository,
    private val sources: SourceDao,
    private val drains: Drains,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saving = Mutex()
    private var pending: Job? = null
    // What the server was last sent, by account, so the same queue is not
    // sent twice.
    @Volatile private var lastSent: Pair<String, ServerQueue>? = null
    @Volatile private var lastCheck = 0L
    // The restored queue's songs and current place, until something changes.
    @Volatile private var baseline: Pair<List<String>, Int>? = null

    // "Sync play queue with the server", on unless switched off.
    val enabled: Flow<Boolean> = context.queueData.data.map { it[SYNC_ON] ?: true }

    private val _offer = MutableStateFlow<ResumeOffer?>(null)
    val offer: StateFlow<ResumeOffer?> = _offer

    fun setEnabled(on: Boolean) {
        scope.launch {
            context.queueData.edit { it[SYNC_ON] = on }
            if (!on) {
                pending?.cancel()
                _offer.value = null
            }
        }
    }

    // The queue the phone brought back from last time. Bringing it back is
    // not a change, so it is not saved over a newer queue from elsewhere.
    fun restored(snapshot: QueueSnapshot) {
        baseline = snapshot.trackIds to snapshot.index
    }

    // The queue or the current song changed: save once it has settled, on
    // the server in use now.
    fun changed(snapshot: QueueSnapshot) {
        val session = inUse()
        synchronized(this) {
            if (baseline == snapshot.trackIds to snapshot.index) return
            baseline = null
            pending?.cancel()
            pending = scope.launch {
                delay(SAVE_AFTER_MS)
                save(session ?: return@launch, snapshot, exact = false)
            }
        }
    }

    // Playback paused: save now, with the exact position.
    fun paused(snapshot: QueueSnapshot) {
        val session = inUse()
        synchronized(this) {
            baseline = null
            pending?.cancel()
            pending = scope.launch { save(session ?: return@launch, snapshot, exact = true) }
        }
    }

    // Leaving a server for another: its queue goes to it now, exactly, and
    // is done before the next server is in use.
    suspend fun leaving(snapshot: QueueSnapshot) {
        val session = inUse() ?: return
        synchronized(this) {
            pending?.cancel()
            pending = null
            baseline = null
        }
        _offer.value = null
        // A server out of reach does not hold the switch up for long.
        withTimeoutOrNull(LEAVE_SAVE_MS) { save(session, snapshot, exact = true) }
    }

    // Forgets what was noted for a server taken off the list.
    suspend fun forget(account: String) {
        context.queueData.edit { p -> listOf(savedKey(account), answeredKey(account), stampKey(account)).forEach { p.remove(it) } }
    }

    // Another server is in use: the queue it offers is looked for at once.
    fun arrived() {
        lastCheck = 0L
        _offer.value = null
        check()
    }

    // The app came to the front: look for a queue saved elsewhere.
    fun check() {
        scope.launch {
            val now = System.currentTimeMillis()
            if (now - lastCheck < CHECK_EVERY_MS) return@launch
            lastCheck = now
            try {
                lookForOffer()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Quietly nothing: the offer is a nicety.
                Log.w("Octo", "queue check failed: ${e.javaClass.simpleName}")
            }
        }
    }

    // Looks for a queue saved elsewhere now, however recently it last
    // looked: a car's driver cannot ask again by coming back to the app.
    suspend fun checkNow() {
        lastCheck = System.currentTimeMillis()
        try {
            lookForOffer()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Octo", "queue check failed: ${e.javaClass.simpleName}")
        }
    }

    // An offer taken up: put away, so the same queue is not offered again.
    // The queue itself is read again when it plays (ListSpec.ServerQueue).
    suspend fun taken(offer: ResumeOffer) = answered(offer)

    private suspend fun offered(session: Session): RemoteQueue? = offeredQueue(session.client, session.hasExtension(INDEX_BASED_QUEUE), drains)

    // Not now: the same queue is not offered again.
    fun dismiss(offer: ResumeOffer) {
        scope.launch { answered(offer) }
    }

    private suspend fun answered(offer: ResumeOffer) {
        if (_offer.value === offer) _offer.value = null
        val session = signedIn() ?: return
        val at = offer.remote.changedAt ?: return
        context.queueData.edit { it[answeredKey(session.id)] = at }
    }

    private suspend fun lookForOffer() {
        if (!enabled.first()) {
            _offer.value = null
            return
        }
        val session = signedIn()
        if (session == null) {
            _offer.value = null
            return
        }
        val remote = try {
            offered(session)
        } catch (e: SubsonicException) {
            return
        }
        val prefs = context.queueData.data.first()
        // An older version kept these by the server's address.
        val savedAt = prefs[savedKey(session.id)] ?: prefs[oldSavedKey(session.sourceId)] ?: 0L
        val answeredAt = prefs[answeredKey(session.id)] ?: prefs[oldAnsweredKey(session.sourceId)] ?: 0L
        val ownStamp = prefs[stampKey(session.id)] ?: prefs[oldStampKey(session.sourceId)]
        val current = remote?.current
        _offer.value = if (current != null && shouldOfferResume(remote, savedAt, answeredAt, ownStamp)) {
            ResumeOffer(remote.changedBy.orEmpty().trim().ifEmpty { "another device" }, current.title, current.displayArtist ?: current.artist, remote)
        } else {
            null
        }
    }

    // A save after a change is skipped when only the position moved; a
    // save at a pause is skipped only when nothing did.
    private suspend fun save(session: Session, snapshot: QueueSnapshot, exact: Boolean) = saving.withLock {
        if (!enabled.first()) return@withLock
        val serverIds = sources.serverIdsOf(session.sourceId, snapshot.trackIds)
        val queue = serverQueueOf(snapshot) { serverIds[it] }?.trimmed() ?: return@withLock
        val sent = lastSent?.takeIf { it.first == session.id }?.second
        if (sent == queue || (!exact && sent?.ids == queue.ids && sent.index == queue.index)) return@withLock
        try {
            if (session.hasExtension(INDEX_BASED_QUEUE)) {
                session.client.savePlayQueueByIndex(queue.ids, queue.index, queue.positionMs)
            } else {
                session.client.savePlayQueue(queue.ids, queue.index, queue.positionMs)
            }
            lastSent = session.id to queue
            // The server's time for this save is how this phone knows its own
            // queue later, whatever name other phones running it give.
            val stamp = runCatching { offered(session) }.getOrNull()?.changedAt
            context.queueData.edit {
                it[savedKey(session.id)] = System.currentTimeMillis()
                if (stamp != null) it[stampKey(session.id)] = stamp
            }
        } catch (e: SubsonicException) {
            Log.w("Octo", "queue save failed: ${e.javaClass.simpleName}")
        }
    }

    // The signed-in session, once the saved sign-in is back, or null.
    private suspend fun signedIn(): Session? {
        val state = withTimeoutOrNull(3_000) { sessions.state.first { it !is SessionState.Loading } }
        return (state as? SessionState.SignedIn)?.session
    }

    // The server in use right now, if any.
    private fun inUse(): Session? = (sessions.state.value as? SessionState.SignedIn)?.session

    private fun Session.hasExtension(name: String) = extensions.any { it.startsWith("$name:") }

    private companion object {
        val SYNC_ON = booleanPreferencesKey("sync_on")

        // When this phone last saved its queue on a server, the last offer
        // answered there, and the server's own time for that save, for each
        // kept server by its id.
        fun savedKey(account: String) = longPreferencesKey("saved_at@$account")
        fun answeredKey(account: String) = longPreferencesKey("answered_at@$account")
        fun stampKey(account: String) = longPreferencesKey("own_stamp@$account")

        // The same, as an older version kept them by the server's address.
        fun oldSavedKey(sourceId: String) = longPreferencesKey("saved_at:$sourceId")
        fun oldAnsweredKey(sourceId: String) = longPreferencesKey("answered_at:$sourceId")
        fun oldStampKey(sourceId: String) = longPreferencesKey("own_stamp:$sourceId")
    }
}

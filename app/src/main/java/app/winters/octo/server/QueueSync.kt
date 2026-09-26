package app.winters.octo.server

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.catalog.FIND_PREFIX
import app.winters.octo.catalog.SourceDao
import app.winters.octo.catalog.isFind
import app.winters.octo.data.Session
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.discovery.Discovery
import app.winters.octo.playback.QueueSnapshot
import app.winters.octo.playback.isRadio
import app.winters.octo.subsonic.INDEX_BASED_QUEUE
import app.winters.octo.subsonic.PlayQueue
import app.winters.octo.subsonic.PlayQueueByIndex
import app.winters.octo.subsonic.Song
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

// The most songs saved on the server. A save is one address with every id
// in it, and proxies in front of a server refuse addresses much longer than
// this makes (about 11 KB).
const val SERVER_QUEUE_MAX = 400

// How many songs before the current one a trimmed queue keeps.
private const val KEEP_BEFORE = 50

// A queue as the server keeps it: server song ids in the order they will
// play, the current one's place, and how far into it playback is.
data class ServerQueue(val ids: List<String>, val index: Int, val positionMs: Long)

// A long queue cut down around the current song: a few before it, the rest after.
fun ServerQueue.trimmed(max: Int = SERVER_QUEUE_MAX): ServerQueue {
    if (ids.size <= max) return this
    val start = (index - KEEP_BEFORE).coerceIn(0, ids.size - max)
    return copy(ids = ids.subList(start, start + max).toList(), index = index - start)
}

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

// A queue another device saved: its songs, the current one's place, the
// position in it, when it was saved and by which app.
data class RemoteQueue(
    val songs: List<Song>,
    val index: Int,
    val positionMs: Long,
    val changedAt: Long?,
    val changedBy: String?,
) {
    val current: Song? get() = songs.getOrNull(index)
}

fun remoteQueueOf(queue: PlayQueueByIndex): RemoteQueue = RemoteQueue(
    songs = queue.entry,
    index = (queue.currentIndex ?: 0).coerceIn(0, (queue.entry.size - 1).coerceAtLeast(0)),
    positionMs = queue.position.coerceAtLeast(0),
    changedAt = serverTime(queue.changed),
    changedBy = queue.changedBy,
)

fun remoteQueueOf(queue: PlayQueue): RemoteQueue {
    val index = queue.entry.indexOfFirst { it.id == queue.current }
    return RemoteQueue(
        songs = queue.entry,
        index = index.coerceAtLeast(0),
        // A position only means something in the song it was saved for.
        positionMs = if (index >= 0) queue.position.coerceAtLeast(0) else 0,
        changedAt = serverTime(queue.changed),
        changedBy = queue.changedBy,
    )
}

// A server's time as milliseconds since 1970, or null when it cannot be read.
fun serverTime(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
}

// Whether to offer picking up a queue from the server: it has songs, it was
// saved after this phone last saved (or last answered an offer), and by an
// app that says who it is and is not this one.
fun shouldOfferResume(remote: RemoteQueue?, lastSavedAt: Long, answeredUpTo: Long, ourClient: String): Boolean {
    if (remote == null || remote.current == null) return false
    val changedAt = remote.changedAt ?: return false
    val by = remote.changedBy?.trim().orEmpty()
    if (by.isEmpty() || by.equals(ourClient, ignoreCase = true)) return false
    return changedAt > maxOf(lastSavedAt, answeredUpTo)
}

// An offer to carry on from another device.
class ResumeOffer(val device: String, val title: String, val artist: String?, internal val remote: RemoteQueue)

// A queue ready to load: library songs or finds, the current one, and where in it.
class ResumeQueue(val trackIds: List<String>, val index: Int, val positionMs: Long)

private val Context.queueData by preferencesDataStore("server_queue")

// Keeps the play queue on the server, so another device (or another app)
// can carry on from it, and offers to carry on from a queue saved elsewhere.
// Saves a short while after the queue or the song changes, and at a pause.
@Singleton
class QueueSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionRepository,
    private val sources: SourceDao,
    private val discovery: Discovery,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saving = Mutex()
    private var pending: Job? = null
    // What the server was last sent, so the same queue is not sent twice.
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

    // The queue or the current song changed: save once it has settled.
    fun changed(snapshot: QueueSnapshot) {
        synchronized(this) {
            if (baseline == snapshot.trackIds to snapshot.index) return
            baseline = null
            pending?.cancel()
            pending = scope.launch {
                delay(SAVE_AFTER_MS)
                save(snapshot, exact = false)
            }
        }
    }

    // Playback paused: save now, with the exact position.
    fun paused(snapshot: QueueSnapshot) {
        synchronized(this) {
            baseline = null
            pending?.cancel()
            pending = scope.launch { save(snapshot, exact = true) }
        }
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

    // The songs to load for an offer taken up, and the offer is put away.
    suspend fun take(offer: ResumeOffer): ResumeQueue? {
        answered(offer)
        val remote = offer.remote
        val tracks = discovery.resolveFromServer(remote.songs)
        if (tracks.isEmpty()) return null
        val current = remote.current?.let { discovery.resolveFromServer(listOf(it)).firstOrNull() }
        val index = current?.let { song -> tracks.indexOfFirst { it.id == song.id } }?.takeIf { it >= 0 }
        return ResumeQueue(tracks.map { it.id }, index ?: 0, if (index != null) remote.positionMs else 0)
    }

    // Not now: the same queue is not offered again.
    fun dismiss(offer: ResumeOffer) {
        scope.launch { answered(offer) }
    }

    private suspend fun answered(offer: ResumeOffer) {
        if (_offer.value === offer) _offer.value = null
        val session = signedIn() ?: return
        val at = offer.remote.changedAt ?: return
        context.queueData.edit { it[answeredKey(session.sourceId)] = at }
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
        val client = session.client
        val remote = try {
            if (session.hasExtension(INDEX_BASED_QUEUE)) client.playQueueByIndex()?.let(::remoteQueueOf)
            else client.playQueue()?.let(::remoteQueueOf)
        } catch (e: SubsonicException) {
            return
        }
        val prefs = context.queueData.data.first()
        val savedAt = prefs[savedKey(session.sourceId)] ?: 0L
        val answeredAt = prefs[answeredKey(session.sourceId)] ?: 0L
        val current = remote?.current
        _offer.value = if (current != null && shouldOfferResume(remote, savedAt, answeredAt, OUR_CLIENT_NAME)) {
            ResumeOffer(remote.changedBy.orEmpty().trim(), current.title, current.displayArtist ?: current.artist, remote)
        } else {
            null
        }
    }

    // A save after a change is skipped when only the position moved; a
    // save at a pause is skipped only when nothing did.
    private suspend fun save(snapshot: QueueSnapshot, exact: Boolean) = saving.withLock {
        if (!enabled.first()) return@withLock
        val session = signedIn() ?: return@withLock
        val serverIds = serverIdsOf(session.sourceId, snapshot.trackIds)
        val queue = serverQueueOf(snapshot) { serverIds[it] }?.trimmed() ?: return@withLock
        val sent = lastSent?.takeIf { it.first == session.sourceId }?.second
        if (sent == queue || (!exact && sent?.ids == queue.ids && sent.index == queue.index)) return@withLock
        try {
            if (session.hasExtension(INDEX_BASED_QUEUE)) {
                session.client.savePlayQueueByIndex(queue.ids, queue.index, queue.positionMs)
            } else {
                session.client.savePlayQueue(queue.ids, queue.index, queue.positionMs)
            }
            lastSent = session.sourceId to queue
            context.queueData.edit { it[savedKey(session.sourceId)] = System.currentTimeMillis() }
        } catch (e: SubsonicException) {
            Log.w("Octo", "queue save failed: ${e.javaClass.simpleName}")
        }
    }

    // The server's id for each song that has one: a library song's copy on
    // this server, or a find's own id. Radio stations have none.
    private suspend fun serverIdsOf(sourceId: String, trackIds: List<String>): Map<String, String> {
        val library = trackIds.filterNot { isFind(it) || isRadio(it) }.distinct()
        val copies = library.chunked(900).flatMap { sources.copiesOf(it) }
            .filter { it.sourceId == sourceId }
            .groupBy { it.mergedId }
            .mapValues { (_, group) -> group.map { it.nativeId }.min() }
        val finds = trackIds.filter(::isFind).associateWith { it.removePrefix(FIND_PREFIX) }
        return copies + finds
    }

    // The signed-in session, once the saved sign-in is back, or null.
    private suspend fun signedIn(): Session? {
        val state = withTimeoutOrNull(3_000) { sessions.state.first { it !is SessionState.Loading } }
        return (state as? SessionState.SignedIn)?.session
    }

    private fun Session.hasExtension(name: String) = extensions.any { it.startsWith("$name:") }

    private companion object {
        val SYNC_ON = booleanPreferencesKey("sync_on")
        fun savedKey(sourceId: String) = longPreferencesKey("saved_at:$sourceId")
        fun answeredKey(sourceId: String) = longPreferencesKey("answered_at:$sourceId")
    }
}

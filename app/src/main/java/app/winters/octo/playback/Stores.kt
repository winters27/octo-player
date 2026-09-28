package app.winters.octo.playback

import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.LikedTrackEntity
import app.winters.octo.catalog.PlayEventEntity
import app.winters.octo.catalog.QueueItemEntity
import app.winters.octo.catalog.QueueStateEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.UserDao
import app.winters.octo.listening.ListenBrainzSync
import app.winters.octo.listening.ListeningSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// Looks up songs by id, keeping the order asked for and dropping any the
// catalog no longer has. SQLite limits how many ids fit in one query.
suspend fun CatalogDao.tracksByIds(ids: List<String>): List<TrackEntity> {
    val found = ids.distinct().chunked(900).flatMap { tracksByIdsUnordered(it) }.associateBy { it.id }
    return ids.mapNotNull { found[it] }
}

@Singleton
class LikeStore @Inject constructor(
    private val userDao: UserDao,
    private val catalog: CatalogDao,
    private val listening: ListeningSync,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val liked: StateFlow<Set<String>> =
        userDao.likedIds().map { it.toSet() }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun isLiked(trackId: String) = trackId in liked.value

    fun toggle(trackId: String) {
        scope.launch {
            if (userDao.isLiked(trackId)) {
                userDao.unlike(trackId)
                listening.likeChanged(trackId, liked = false)
            } else {
                val track = catalog.track(trackId) ?: return@launch
                userDao.like(LikedTrackEntity(trackId, track.relinkKey, System.currentTimeMillis()))
                listening.likeChanged(trackId, liked = true)
            }
        }
    }

    // Takes a like away. `removed` hears the like as it was, to put it back.
    fun unlike(trackId: String, removed: (LikedTrackEntity) -> Unit) {
        scope.launch {
            val row = userDao.likedRow(trackId) ?: return@launch
            userDao.unlike(trackId)
            listening.likeChanged(trackId, liked = false)
            removed(row)
        }
    }

    // Puts a like back as it was, with its own time, so it keeps its place.
    fun restore(row: LikedTrackEntity) {
        scope.launch {
            userDao.like(row)
            listening.likeChanged(row.trackId, liked = true)
        }
    }
}

// The queue as saved: songs in play order, where playback was, and the
// shuffle order so shuffle picks up exactly where it left off.
data class QueueSnapshot(
    val trackIds: List<String>,
    // Play-order positions visited in shuffle order; empty when unknown.
    val shuffleOrder: List<Int>,
    val index: Int,
    val positionMs: Long,
    val repeatMode: Int,
    val shuffle: Boolean,
)

fun QueueSnapshot.toRows(now: Long): Pair<List<QueueItemEntity>, QueueStateEntity> {
    val shuffledAt = IntArray(trackIds.size) { it }
    if (shuffleOrder.size == trackIds.size) shuffleOrder.forEachIndexed { rank, position -> shuffledAt[position] = rank }
    val items = trackIds.mapIndexed { position, id -> QueueItemEntity(position, id, shuffledAt[position]) }
    return items to QueueStateEntity(0, index, positionMs, repeatMode, shuffle, now)
}

fun queueFromRows(items: List<QueueItemEntity>, state: QueueStateEntity?): QueueSnapshot? {
    if (items.isEmpty() || state == null) return null
    val ordered = items.sortedBy { it.position }
    return QueueSnapshot(
        trackIds = ordered.map { it.trackId },
        shuffleOrder = ordered.sortedBy { it.shuffledPosition }.map { it.position },
        index = state.currentIndex.coerceIn(0, ordered.lastIndex),
        positionMs = state.positionMs.coerceAtLeast(0),
        repeatMode = state.repeatMode,
        shuffle = state.shuffle,
    )
}

// Where a saved queue picks up once the songs gone from the library are
// dropped: `found` says which saved songs are still here, in saved order.
// The saved song at the place it was, or when that song is gone, the first
// song from its start.
fun restorePoint(found: List<Boolean>, savedIndex: Int, positionMs: Long): Pair<Int, Long> {
    if (found.getOrNull(savedIndex) != true) return 0 to 0L
    return found.take(savedIndex).count { it } to positionMs.coerceAtLeast(0)
}

// The saved shuffle order with the songs that are gone left out, so the
// rest still play in the order they would have.
fun restoredShuffle(order: List<Int>, found: List<Boolean>): IntArray? {
    if (order.size != found.size || order.sorted() != found.indices.toList()) return null
    val newIndex = IntArray(found.size)
    var kept = 0
    found.forEachIndexed { i, here -> newIndex[i] = if (here) kept++ else -1 }
    return order.filter { found[it] }.map { newIndex[it] }.toIntArray()
}

@Singleton
class QueueStore @Inject constructor(private val userDao: UserDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Saving runs on its own scope so it finishes even while the service stops.
    fun save(snapshot: QueueSnapshot) {
        scope.launch {
            val (items, state) = snapshot.toRows(System.currentTimeMillis())
            userDao.saveQueue(items, state)
        }
    }

    suspend fun load(): QueueSnapshot? = queueFromRows(userDao.queueItems(), userDao.queueState())
}

@Singleton
class PlayStore @Inject constructor(
    private val userDao: UserDao,
    private val catalog: CatalogDao,
    private val listening: ListeningSync,
    private val listenBrainz: ListenBrainzSync,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // A song began to play.
    fun started(trackId: String) {
        listening.nowPlaying(trackId)
        listenBrainz.nowPlaying(trackId)
    }

    fun record(trackId: String, startedAt: Long, playedMs: Long, durationMs: Long) {
        if (!countsAsPlay(playedMs, durationMs)) return
        scope.launch {
            val key = catalog.track(trackId)?.relinkKey ?: ""
            userDao.addPlay(PlayEventEntity(trackId = trackId, relinkKey = key, startedAt = startedAt, playedMs = playedMs, durationMs = durationMs))
        }
        listening.played(trackId, startedAt)
        listenBrainz.played(trackId, startedAt)
    }
}

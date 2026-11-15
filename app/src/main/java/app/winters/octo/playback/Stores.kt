package app.winters.octo.playback

import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.LikedTrackEntity
import app.winters.octo.catalog.MosaicPicker
import app.winters.octo.catalog.PlayEventEntity
import app.winters.octo.catalog.RENAME_SONG
import app.winters.octo.catalog.SongIds
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.UserDao
import app.winters.octo.catalog.findId
import app.winters.octo.catalog.isFind
import app.winters.octo.discovery.OwnedFinds
import app.winters.octo.discovery.heartOwned
import app.winters.octo.listening.ListenBrainzSync
import app.winters.octo.listening.ListeningSync
import app.winters.octo.listening.PlayTotals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File

// Looks up songs by id, keeping the order asked for and dropping any the
// catalog no longer has. SQLite limits how many ids fit in one query.
suspend fun CatalogDao.tracksByIds(ids: List<String>): List<TrackEntity> {
    val found = ids.distinct().chunked(900).flatMap { tracksByIdsUnordered(it) }.associateBy { it.id }
    return ids.mapNotNull { found[it] }
}

// How many songs one likes lookup asks the database about, under its limit.
private const val LIKED_CHUNK = 900

// How many liked songs one page of the covers walk reads.
private const val LIKED_COVER_PAGE = 100

// Song hearts. A heart reads and writes a song as it is now (SongIds): a
// tap reads the song's id now, whether it is liked and its key, and writes,
// all between groups of a save, and the server's copy follows that id. While
// a save is part way a like may be on a song's old id or its new one, and
// either shows the heart. A song the server owns that the phone's copy has
// not brought in yet (OwnedFinds) has its heart there, as the server's star
// of the song; once the song is in, its heart moves onto it (ownedArrived).
@Singleton
class LikeStore @Inject constructor(
    private val userDao: UserDao,
    private val catalog: CatalogDao,
    private val listening: ListeningSync,
    private val db: CatalogDatabase,
    private val ids: SongIds,
    private val owned: OwnedFinds = OwnedFinds(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Whether one song is liked, as it changes.
    fun likedFlow(trackId: String): Flow<Boolean> =
        if (isFind(trackId)) owned.liked.map { trackId in it }.distinctUntilChanged() else userDao.likedFlow(trackId).distinctUntilChanged()

    // Which of these songs are liked, as that changes, asked about 900 at a
    // time.
    // Read again on each change of the likes or the renames in force: a
    // song shows as liked through either of its ids (UserDao.shownLikedAmong).
    fun likedAmong(trackIds: Collection<String>): Flow<Set<String>> {
        val (finds, library) = trackIds.distinct().partition(::isFind)
        val chunks = library.chunked(LIKED_CHUNK)
        val shown: Flow<Set<String>> = if (chunks.isEmpty()) {
            flowOf(emptySet())
        } else {
            db.invalidationTracker.createFlow("liked_track", "save_rename")
                .map { chunks.flatMapTo(HashSet()) { userDao.shownLikedAmong(it) } }
        }
        if (finds.isEmpty()) return shown.distinctUntilChanged()
        return combine(shown, owned.liked) { mine, hearts -> mine + finds.filter { it in hearts } }.distinctUntilChanged()
    }

    suspend fun isLiked(trackId: String): Boolean = if (isFind(trackId)) trackId in owned.liked.value else userDao.isLiked(trackId)

    // An owned song (`findId`) is in the library now as `trackId`: a heart it
    // had is put on the library song, which the server already stars.
    suspend fun ownedArrived(findId: String, trackId: String) {
        if (findId !in owned.liked.value) return
        ids.write {
            val id = ids.song(trackId)
            if (userDao.isLiked(id)) return@write
            val track = catalog.track(id) ?: return@write
            userDao.like(LikedTrackEntity(id, track.relinkKey, System.currentTimeMillis()))
        }
    }

    // A count that moves on every change of the likes, and once at first.
    val changes: Flow<Long> = flow {
        var n = 0L
        db.invalidationTracker.createFlow("liked_track").collect { emit(++n) }
    }

    // Up to four covers of the liked songs, newest like first, read a page
    // at a time until it has them.
    suspend fun likedCovers(): List<String> {
        val picker = MosaicPicker()
        var beforeAt = Long.MAX_VALUE
        var beforeId = ""
        while (!picker.full) {
            val page = userDao.likedCoverPage(beforeAt, beforeId, LIKED_COVER_PAGE)
            page.forEach { picker.offer(it.albumId, it.artwork) }
            if (page.size < LIKED_COVER_PAGE) break
            beforeAt = page.last().likedAt
            beforeId = page.last().trackId
        }
        return picker.covers
    }

    // A find has a heart only when the server owns it (heartOwned); any
    // other has none, and this does nothing.
    fun toggle(trackId: String) {
        if (isFind(trackId)) {
            heartOwned(owned, trackId) { serverId, on -> listening.likeChanged(findId(serverId), on) }
            return
        }
        scope.launch {
            val (id, liked) = ids.write {
                val id = ids.song(trackId)
                if (userDao.isLiked(id)) {
                    userDao.unlikeEither(id)
                    id to false
                } else {
                    val track = catalog.track(id) ?: return@write null
                    userDao.like(LikedTrackEntity(id, track.relinkKey, System.currentTimeMillis()))
                    id to true
                }
            } ?: return@launch
            listening.likeChanged(id, liked)
        }
    }

    // Takes a like away. `removed` hears the like as it was, to put it back.
    fun unlike(trackId: String, removed: (LikedTrackEntity) -> Unit) {
        scope.launch {
            val (id, row) = ids.write {
                val id = ids.song(trackId)
                val row = userDao.likedRowEither(id) ?: return@write null
                userDao.unlikeEither(id)
                id to row
            } ?: return@launch
            listening.likeChanged(id, liked = false)
            removed(row)
        }
    }

    // Songs a restore liked, written already: the server is told, as a tap
    // tells it.
    fun restored(trackIds: List<String>) {
        trackIds.forEach { listening.likeChanged(it, liked = true) }
    }

    // Puts a like back as it was, with its own time, so it keeps its place,
    // on the song its id and key land on now.
    fun restore(row: LikedTrackEntity) {
        scope.launch {
            val id = ids.write {
                val id = ids.row(RENAME_SONG, row.trackId, row.relinkKey)
                userDao.like(row.copy(trackId = id))
                id
            }
            listening.likeChanged(id, liked = true)
        }
    }
}

// The queue as a server keeps it: songs in play order, where playback is,
// and how it plays. A window of the queue, never the whole of a long one.
data class QueueSnapshot(
    val trackIds: List<String>,
    // Play-order positions visited in shuffle order; empty when unknown.
    val shuffleOrder: List<Int>,
    val index: Int,
    val positionMs: Long,
    val repeatMode: Int,
    val shuffle: Boolean,
    // Where each song came from (QueueSource.encoded), one per song; empty
    // when unknown.
    val sources: List<String> = emptyList(),
)

// Plays. A play is written on the song its id names now (SongIds), and
// its count and ListenBrainz follow that id. The server's play is noted at
// once for the server in use then (ListeningSync), which finds the song's
// copies as they are now itself.
@Singleton
class PlayStore internal constructor(
    private val userDao: UserDao,
    private val catalog: CatalogDao,
    private val listening: ListeningSync,
    private val follows: PlayFollows,
    private val ids: SongIds,
) {
    @Inject constructor(
        userDao: UserDao,
        catalog: CatalogDao,
        listening: ListeningSync,
        listenBrainz: ListenBrainzSync,
        totals: dagger.Lazy<PlayTotals>,
        ids: SongIds,
    ) : this(
        userDao,
        catalog,
        listening,
        object : PlayFollows {
            override fun nowPlaying(trackId: String) = listenBrainz.nowPlaying(trackId)

            override fun played(trackId: String, startedAt: Long) = listenBrainz.played(trackId, startedAt)

            override fun counted(trackIds: List<String>) = totals.get().refresh(trackIds)
        },
        ids,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // A song began to play.
    fun started(trackId: String) {
        listening.nowPlaying(trackId)
        follows.nowPlaying(trackId)
    }

    fun record(trackId: String, startedAt: Long, playedMs: Long, durationMs: Long) {
        if (!countsAsPlay(playedMs, durationMs)) return
        listening.played(trackId, startedAt)
        scope.launch {
            val id = ids.write {
                val id = ids.song(trackId)
                val key = catalog.track(id)?.relinkKey ?: ""
                userDao.addPlay(PlayEventEntity(trackId = id, relinkKey = key, startedAt = startedAt, playedMs = playedMs, durationMs = durationMs))
                id
            }
            // The song's play count follows.
            follows.counted(listOf(id))
            follows.played(id, startedAt)
        }
    }
}

// What follows a play once it is written: ListenBrainz and the song's
// play count.
internal interface PlayFollows {
    fun nowPlaying(trackId: String)

    fun played(trackId: String, startedAt: Long)

    fun counted(trackIds: List<String>)
}

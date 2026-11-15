package app.winters.octo.playback

import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.findId
import app.winters.octo.lyrics.LyricsTiming
import app.winters.octo.offline.SongCopies
import app.winters.octo.offline.StreamCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// What the change feed and a pick ask of the phone's saved copies, by the
// songs' ids on the server `sourceId` names.
interface VersionDropper {
    // Lets the saved copies of these outside songs go. `now`: even of a song
    // playing, as for a pick's song, which plays again from its start.
    suspend fun drop(sourceId: String, ids: Collection<String>, now: Boolean = false)

    // Lets the saved copies of every outside song of the server go.
    suspend fun dropOutside(sourceId: String)
}

// Lets an outside song's saved copies go when the video it streams from
// changed: every copy of each song (its writers fenced first, see
// StreamCache.dropSong), its kept transition profile (`dropped`, which the
// playback service hears) and its lyrics timing. A song the phone's player
// is on, or blending out of, keeps its copies while it plays, since its
// play keeps its video: its writers are fenced at once, and its copies go
// once it stops playing, or once a new play of it is about to read.
//
// A song found online is told by the app's own find marks, never by its
// id's shape: a saved copy made from a named video (X-Octo-Video), or a
// find in the catalog (`finds`, the server ids of a server's finds).
@Singleton
class VersionDrops internal constructor(
    private val saved: SongCopies,
    private val finds: suspend (String) -> List<String>,
    private val resetTiming: suspend (String) -> Unit,
    // The track ids whose lyrics timing was moved.
    private val movedTimings: suspend () -> Set<String>,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
) : VersionDropper {
    @Inject constructor(saved: StreamCache, online: OnlineDao, timing: LyricsTiming) :
        this(saved, online::nativeIds, timing::reset, { timing.offsets().keys }, Dispatchers.IO, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    // The songs the phone's player plays now (see songKey).
    @Volatile private var playing: Set<String> = emptySet()

    // Songs fenced while they played, whose copies go once they stop.
    private val waiting: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val _dropped = MutableSharedFlow<Set<String>?>(extraBufferCapacity = 16)

    // The ids on the server whose saved copies went, for the player's kept
    // transition profiles; null for every outside song's.
    val dropped: SharedFlow<Set<String>?> = _dropped

    // What the phone's player plays now, told on its thread at each change.
    // A song no longer among them has its waiting drop made.
    fun nowPlaying(songs: Set<String>) {
        playing = songs
        val ready = waiting.filter { it !in songs }
        if (ready.isEmpty()) return
        waiting.removeAll(ready.toSet())
        scope.launch { ready.forEach(saved::dropSong) }
    }

    // A new play of `song` is about to read it, nothing read yet: a drop
    // that waited for the song goes now. On the loading thread.
    fun opening(song: String) {
        if (waiting.remove(song)) saved.dropSong(song)
    }

    override suspend fun drop(sourceId: String, ids: Collection<String>, now: Boolean) = withContext(io) {
        val on = if (now) emptySet() else playing
        val songs = ids.distinct()
        songs.forEach { id ->
            val song = songKey(sourceId, id)
            if (song in on) {
                saved.fence(song)
                waiting += song
            } else {
                waiting -= song
                saved.dropSong(song)
            }
            resetTiming(findId(id))
        }
        if (songs.isNotEmpty()) _dropped.emit(songs.toSet())
    }

    // Every saved copy made from a named video, and every find of the
    // server's in the catalog (those playing fenced and dropped once they
    // stop), and the lyrics timing of each that had one moved.
    override suspend fun dropOutside(sourceId: String) = withContext(io) {
        val on = playing
        val songs = (saved.videoSongs(sourceId) + finds(sourceId).map { songKey(sourceId, it) }).toHashSet()
        val playingNow = songs.filter { it in on }
        playingNow.forEach { song ->
            saved.fence(song)
            waiting += song
        }
        saved.dropSongs(songs - playingNow.toSet())
        val moved = movedTimings()
        songs.map { findId(it.substringAfter('|')) }.filter { it in moved }.forEach { resetTiming(it) }
        _dropped.emit(null)
    }
}

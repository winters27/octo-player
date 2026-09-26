package app.winters.octo.discovery

import app.winters.octo.admin.AdminUnavailable
import app.winters.octo.admin.DownloadRecord
import app.winters.octo.admin.OctoAdmin
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.FIND_PREFIX
import app.winters.octo.catalog.LikedTrackEntity
import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.UserDao
import app.winters.octo.catalog.isFind
import app.winters.octo.catalog.matchKey
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.listening.ListeningSync
import app.winters.octo.server.ServerSync
import app.winters.octo.server.serverSourceId
import app.winters.octo.subsonic.SubsonicException
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import javax.inject.Inject
import javax.inject.Singleton

// When to look for downloads in the library after asking for one, away
// from home: the server does not say when a download is done, so the
// library is copied again a little later, and once more for slow ones.
private val CHECK_AFTER_MS = listOf(2 * 60_000L, 8 * 60_000L)

// After this long a download that never arrived can be asked for again.
private const val GIVE_UP_MS = 24 * 60 * 60_000L

// On the home network, how often to look at what Octo has finished, for how
// long, and how many times to copy the library for one finished song while
// the server takes it in.
private const val WATCH_EVERY_MS = 20_000L
private const val WATCH_FOR_MS = 30 * 60_000L
private const val SYNCS_PER_SONG = 3

// Finds that have not come up for this long are let go.
private const val FORGET_MS = 30L * 24 * 60 * 60_000L

enum class DownloadState { None, Requested, Done }

// Downloading songs found online into the library. On Octo, starring a find
// makes the server download it. Once a later copy of the library shows the
// song, it is liked, which stars the downloaded file too.
@Singleton
class Downloads @Inject constructor(
    private val sessions: SessionRepository,
    private val online: OnlineDao,
    private val catalog: CatalogDao,
    private val user: UserDao,
    private val listening: ListeningSync,
    private val sync: Lazy<ServerSync>,
    private val admin: OctoAdmin,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var checks: Job? = null

    // Finds asked for, by id, and whether each has arrived.
    val states: StateFlow<Map<String, DownloadState>> = online.requestedFlow()
        .map { rows -> rows.associate { it.id to stateOf(it, System.currentTimeMillis()) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun state(trackId: String): DownloadState = states.value[trackId] ?: DownloadState.None

    // Asks the server to download one find. Answers whether it was asked.
    suspend fun request(track: TrackEntity): Boolean {
        if (!isFind(track.id)) return false
        val client = client() ?: return false
        return try {
            client.star(listOf(track.id.removePrefix(FIND_PREFIX)))
            online.setRequested(track.id, System.currentTimeMillis())
            checkLater()
            true
        } catch (e: SubsonicException) {
            false
        }
    }

    // Asks the server to download a whole album found online, and follows
    // the songs from it that are listed.
    suspend fun requestAlbum(albumId: String, songs: List<TrackEntity>): Boolean {
        val client = client() ?: return false
        return try {
            client.starAlbums(listOf(albumId))
            val now = System.currentTimeMillis()
            songs.filter { isFind(it.id) }.forEach { online.setRequested(it.id, now) }
            checkLater()
            true
        } catch (e: SubsonicException) {
            false
        }
    }

    // Runs after each copy of the server's library: a download that has
    // arrived becomes a liked library song, and old finds are let go.
    suspend fun afterSync() {
        val client = client() ?: return
        val now = System.currentTimeMillis()
        online.prune(serverSourceId(client.baseUrl), now - FORGET_MS)
        val waiting = online.waiting().ifEmpty { return }
        val candidates = waiting.flatMap { titleKeys(it.title) }.distinct().chunked(900).flatMap { catalog.tracksWithKeys(it) }
        val byTitle = candidates.filter { !isFind(it.id) }.groupBy { matchKey(it.title) }
        for (find in waiting) {
            val track = byTitle[matchKey(find.title)]?.firstOrNull { sameSong(find.title, find.artist, find.durationMs, it) } ?: continue
            if (!user.isLiked(track.id)) {
                user.like(LikedTrackEntity(track.id, track.relinkKey, now))
                listening.likeChanged(track.id, liked = true)
            }
            online.adopt(find.id, track.id)
        }
    }

    // Copies the library again once downloads are done: as soon as Octo
    // lists one as finished when its admin pages can be reached, otherwise a
    // little later, and once more after that for slow ones.
    private fun checkLater() {
        synchronized(this) {
            checks?.cancel()
            checks = scope.launch {
                val base = admin.locate()
                if (base != null) watch(base) else checkOnTimer()
            }
        }
    }

    private suspend fun checkOnTimer() {
        var waited = 0L
        for (at in CHECK_AFTER_MS) {
            delay(at - waited)
            waited = at
            sync.get().syncNow()
        }
    }

    // Until every download asked for has arrived, or for half an hour.
    private suspend fun watch(base: HttpUrl) {
        val syncs = HashMap<String, Int>()
        val until = System.currentTimeMillis() + WATCH_FOR_MS
        while (System.currentTimeMillis() < until) {
            delay(WATCH_EVERY_MS)
            val waiting = online.waiting().ifEmpty { return }
            val finished = try {
                admin.downloads(base)
            } catch (e: AdminUnavailable) {
                continue
            }
            val due = waiting.filter { find -> finished.any { downloadMatches(find, it) } }
                .filter { (syncs[it.id] ?: 0) < SYNCS_PER_SONG }
            if (due.isNotEmpty()) {
                due.forEach { syncs[it.id] = (syncs[it.id] ?: 0) + 1 }
                sync.get().syncNow()
            }
        }
    }

    private fun client() = (sessions.state.value as? SessionState.SignedIn)?.session?.client
}

// Whether a song Octo finished downloading is the one asked for.
fun downloadMatches(find: OnlineSongEntity, record: DownloadRecord): Boolean =
    matchKey(record.title) == matchKey(find.title) && versionOf(record.title) == versionOf(find.title) &&
        sameArtist(record.artist, find.artist)

fun stateOf(row: OnlineSongEntity, now: Long): DownloadState = when {
    row.adoptedId.isNotEmpty() -> DownloadState.Done
    row.requestedAt > 0 && now - row.requestedAt < GIVE_UP_MS -> DownloadState.Requested
    else -> DownloadState.None
}

package app.winters.octo.playback

import android.util.Log
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.searchKey
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.data.accountId
import app.winters.octo.discovery.Discovered
import app.winters.octo.discovery.Discovery
import app.winters.octo.discovery.Downloads
import app.winters.octo.discovery.OnlineAlbum
import app.winters.octo.discovery.OnlineArtist
import app.winters.octo.discovery.RankedTracks
import app.winters.octo.discovery.SearchTopSongs
import app.winters.octo.discovery.inLibraryOnly
import app.winters.octo.player.PlayerSettings
import app.winters.octo.server.ServerSync
import app.winters.octo.ui.search.NamedArtist
import app.winters.octo.ui.search.listedFinds
import app.winters.octo.ui.search.namedArtist
import app.winters.octo.ui.search.without
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

// How much of each kind a car's search reads from the library: what the
// phone's All shows, so "See all" lists the same.
private const val LIBRARY_ARTISTS = 10
private const val LIBRARY_ALBUMS = 20
private const val LIBRARY_SONGS = 50
private const val LIBRARY_PLAYLISTS = 10

// The searches a car's answers are kept for, so a song chosen in one plays
// its list and its "See all" rows open.
private const val ANSWERS_KEPT = 8

// What the library has for a search.
data class LibraryFound(
    val artists: List<ArtistEntity> = emptyList(),
    val albums: List<AlbumEntity> = emptyList(),
    val songs: List<TrackEntity> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
)

// One search as a car shows it: the library's finds, what the server found
// outside it (null when it was not asked: no server, or Library songs
// only), whether asking it failed, and the top songs of an artist it names.
data class CarSearchAnswer(
    val token: String,
    val query: String,
    val account: String?,
    val library: LibraryFound,
    val outside: Discovered?,
    val outsideFailed: Boolean,
    val top: RankedTracks?,
)

// The answer as Library songs only shows it, when that is on.
fun CarSearchAnswer.shown(libraryOnly: Boolean): CarSearchAnswer =
    if (!libraryOnly) this else copy(outside = null, outsideFailed = false, top = top?.inLibraryOnly())

// Where a car's search reads from.
internal interface CarSearchSource {
    fun account(): String?
    fun signedIn(): Boolean
    suspend fun libraryOnly(): Boolean
    suspend fun library(key: String): LibraryFound
    suspend fun outside(query: String): Discovered?

    // Takes in the server's own songs it found; null when a copy of the
    // library is running and they come in after it.
    suspend fun takeIn(found: Discovered): Boolean?
    fun adoptions(): Map<String, String>
    suspend fun top(named: NamedArtist): RankedTracks?
}

// A car's search: the library first, then what the server finds outside
// it, the server's own songs it had not copied yet taken in, and the top
// songs of an artist it names. One search runs per words and account; a
// second ask for one on its way joins it, and any other runs anew, so a
// failed one is never answered again. A car gets one answer per search, so
// this always answers, even when the server does not.
@Singleton
class CarSearch internal constructor(
    private val source: CarSearchSource,
    private val scope: CoroutineScope,
) {
    @Inject constructor(
        sessions: SessionRepository,
        catalog: CatalogDao,
        playlists: PlaylistStore,
        discovery: Discovery,
        sync: ServerSync,
        downloads: Downloads,
        tops: SearchTopSongs,
        player: PlayerSettings,
    ) : this(
        AppSearchSource(sessions, catalog, playlists, discovery, sync, downloads, tops, player),
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val lock = Mutex()
    private val running = HashMap<Pair<String, String?>, Deferred<Result<CarSearchAnswer>>>()
    private val answers = object : LinkedHashMap<String, CarSearchAnswer>(ANSWERS_KEPT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CarSearchAnswer>?): Boolean = size > ANSWERS_KEPT
    }

    // The last search for each words and account, by its token.
    private val latest = HashMap<Pair<String, String?>, String>()

    // The answer for `query`, joining the search for it on its way. A search
    // that throws holds what it threw rather than failing in its scope, and
    // every caller waiting on it gets that thrown.
    suspend fun answer(query: String): CarSearchAnswer {
        val account = source.account()
        val key = query to account
        val run = lock.withLock {
            running[key] ?: scope.async {
                try {
                    val found = run(query, account, newToken())
                    lock.withLock {
                        answers[found.token] = found
                        latest[key] = found.token
                        latest.values.retainAll(answers.keys)
                    }
                    Result.success(found)
                } catch (e: Throwable) {
                    // An older answer for the same words is not this one.
                    lock.withLock { latest.remove(key) }
                    Result.failure(e)
                } finally {
                    lock.withLock { running.remove(key) }
                }
            }.also { running[key] = it }
        }
        return run.await().getOrThrow().shown(source.libraryOnly())
    }

    // The answer the last search for `query` gave, for the car to read
    // after it was told the search ended; null when there is none kept.
    suspend fun latest(query: String): CarSearchAnswer? {
        val token = lock.withLock { latest[query to source.account()] } ?: return null
        return kept(token)
    }

    // A kept answer, for the account in use, as Library songs only shows
    // it now; null when it is gone or from another account.
    suspend fun kept(token: String): CarSearchAnswer? {
        val found = lock.withLock { answers[token] } ?: return null
        if (found.account != source.account()) return null
        return found.shown(source.libraryOnly())
    }

    // Asks the server again for a kept answer whose outside search failed.
    suspend fun retry(token: String): CarSearchAnswer? {
        val before = kept(token) ?: return null
        val again = run(before.query, before.account, token)
        lock.withLock { answers[token] = again }
        return again.shown(source.libraryOnly())
    }

    private suspend fun run(query: String, account: String?, token: String): CarSearchAnswer {
        val words = query.trim()
        val key = searchKey(words)
        var library = source.library(key)
        var outside: Discovered? = null
        var failed = false
        if (source.signedIn() && !source.libraryOnly()) {
            try {
                outside = source.outside(words)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Octo", "car search outside failed: ${e.javaClass.simpleName}")
                failed = true
            }
            val found = outside
            if (found != null) {
                val tookIn = try {
                    source.takeIn(found)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (tookIn == true) library = source.library(key)
            }
        }
        val listed = listedFinds(source.adoptions(), library.songs)
        val shownOutside = outside?.without(listed)
        val named = namedArtist(words, library.artists, shownOutside?.artists.orEmpty())
        val top = named?.let {
            try {
                source.top(it)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        return CarSearchAnswer(token, query, account, library, shownOutside, failed, top)
    }

    private fun newToken(): String = java.lang.Long.toUnsignedString(Random.nextLong(), 36)
}

private class AppSearchSource(
    private val sessions: SessionRepository,
    private val catalog: CatalogDao,
    private val playlists: PlaylistStore,
    private val discovery: Discovery,
    private val sync: ServerSync,
    private val downloads: Downloads,
    private val tops: SearchTopSongs,
    private val player: PlayerSettings,
) : CarSearchSource {
    override fun account(): String? = sessions.state.value.accountId

    override fun signedIn(): Boolean = sessions.state.value is SessionState.SignedIn

    override suspend fun libraryOnly(): Boolean = player.prefs.first().libraryOnly

    override suspend fun library(key: String): LibraryFound {
        if (key.length < 2) return LibraryFound()
        val named = playlists.playlists.first().filter { !isToolPlaylist(it.name) && searchKey(it.name).contains(key) }
        return LibraryFound(
            catalog.searchArtists(key, LIBRARY_ARTISTS),
            catalog.searchAlbums(key, LIBRARY_ALBUMS),
            catalog.searchTracks(key, LIBRARY_SONGS),
            named.take(LIBRARY_PLAYLISTS),
        )
    }

    override suspend fun outside(query: String): Discovered? = discovery.search(query)

    override suspend fun takeIn(found: Discovered): Boolean? =
        if (found.arriving.isEmpty) false else sync.takeInIfFree(found.arriving)

    override fun adoptions(): Map<String, String> = downloads.adoptions.value.orEmpty()

    override suspend fun top(named: NamedArtist): RankedTracks? = tops.forArtist(named.name, named.libraryId, named.serverId)
}

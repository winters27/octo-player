package app.winters.octo.server

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.listening.FavouriteSync
import app.winters.octo.listening.ListeningSync
import app.winters.octo.catalog.SourceDao
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.data.userMessage
import app.winters.octo.discovery.Downloads
import app.winters.octo.offline.OfflineDownloads
import app.winters.octo.playlists.PlaylistSync
import app.winters.octo.subsonic.readLibrary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

// When a server's library was last copied, and how much came.
data class LastSync(val sourceId: String, val at: Long, val songs: Int, val albums: Int)

private val Context.syncData by preferencesDataStore("server_sync")

// A copy older than this is made again when the app starts.
private const val STALE_MS = 6 * 60 * 60 * 1000L

// Raised whenever a copy learns to keep something new from the server, so
// the next start copies the library again whatever its age. Copies made
// before this was kept count as version 1; version 2 keeps genres, credits,
// original years, MusicBrainz ids and the other details.
private const val ROWS_VERSION = 2

// Keeps a copy of the signed-in server's library beside the phone's music:
// copied after signing in, at app start when the last copy is old, and when
// asked. Disconnecting, or losing the sign-in, takes the server's music out.
@Singleton
class ServerSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionRepository,
    private val sources: SourceDao,
    private val merge: CatalogMerge,
    private val listening: ListeningSync,
    private val favourites: FavouriteSync,
    private val downloads: Downloads,
    private val playlists: PlaylistSync,
    private val offline: OfflineDownloads,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dropLock = Mutex()
    private var job: Job? = null
    private var started = false

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    // Why the last copy failed, in words; null once one works.
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem

    val last: Flow<LastSync?> = context.syncData.data.map { p ->
        LastSync(
            sourceId = p[SOURCE_ID] ?: return@map null,
            at = p[SYNCED_AT] ?: return@map null,
            songs = p[SONGS] ?: 0,
            albums = p[ALBUMS] ?: 0,
        )
    }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            sessions.state.collect { state ->
                when (state) {
                    is SessionState.SignedIn -> {
                        val sourceId = state.session.sourceId
                        dropServers(keep = sourceId)
                        val done = last.first()
                        val older = context.syncData.data.first()[ROWS] != ROWS_VERSION
                        val stale = done == null || done.sourceId != sourceId || older ||
                            System.currentTimeMillis() - done.at > STALE_MS
                        if (stale) syncNow()
                    }
                    SessionState.SignedOut -> dropServers(keep = null)
                    SessionState.Loading -> Unit
                }
            }
        }
    }

    // Starts a copy unless one is already running.
    fun syncNow() {
        synchronized(this) {
            if (job?.isActive == true) return
            job = scope.launch { sync() }
        }
    }

    // Starts a copy unless one is already running, and waits for it to end.
    // Answers why it failed, in words, or null when it worked.
    suspend fun syncNowAndWait(): String? {
        syncNow()
        synchronized(this) { job }?.join()
        return _problem.value
    }

    // Copies the library afresh, stopping a copy already running: for when
    // what the copy should hold has changed, like the chosen music folder.
    fun syncAgain() {
        scope.launch {
            synchronized(this@ServerSync) { job }?.cancelAndJoin()
            syncNow()
        }
    }

    // Signs out and takes the server's music out of the library. Nothing
    // on the server changes.
    fun disconnect() {
        scope.launch {
            synchronized(this@ServerSync) { job }?.cancelAndJoin()
            sessions.signOut()
            dropServers(keep = null)
        }
    }

    private suspend fun sync() {
        val session = (sessions.state.value as? SessionState.SignedIn)?.session ?: return
        val client = session.client
        val sourceId = session.sourceId
        _syncing.value = true
        try {
            val started = SystemClock.elapsedRealtime()
            val catalog = buildServerCatalog(sourceId, client.readLibrary())
            sources.replaceSource(sourceId, catalog.tracks, catalog.albums, catalog.artists)
            val library = merge.rebuild()
            // Brings likes and stars together, and sends any plays still waiting.
            listening.afterSync()
            // The same for favourite albums and artists.
            favourites.afterSync()
            // Songs downloaded since the last copy join the library as liked.
            downloads.afterSync()
            // Brings the server's playlists and the phone's copies together.
            playlists.afterSync()
            // Downloads follow their songs, and songs kept downloaded that just arrived start.
            offline.afterSync()
            context.syncData.edit { p ->
                p[SOURCE_ID] = sourceId
                p[SYNCED_AT] = System.currentTimeMillis()
                p[SONGS] = catalog.tracks.size
                p[ALBUMS] = catalog.albums.size
                p[ROWS] = ROWS_VERSION
            }
            _problem.value = null
            // Counts only, like the phone scan's line.
            Log.i(
                "Octo",
                "server sync: ${catalog.tracks.size} tracks, ${catalog.albums.size} albums, " +
                    "${catalog.artists.size} artists, ${SystemClock.elapsedRealtime() - started} ms; " +
                    "library ${library.tracks.size} tracks, ${library.albums.size} albums, ${library.artists.size} artists",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _problem.value = e.userMessage()
            Log.w("Octo", "server sync failed: ${e.javaClass.simpleName}")
        } finally {
            _syncing.value = false
        }
    }

    // Removes every server's music but the one to keep, and forgets the
    // last copy when no server is kept.
    private suspend fun dropServers(keep: String?) = dropLock.withLock {
        val gone = sources.sourceIds().filter { isServerSource(it) && it != keep }
        if (gone.isNotEmpty()) {
            gone.forEach { sources.deleteSource(it) }
            merge.rebuild()
        }
        // Playlists kept with a server that is gone stay, only on the phone.
        playlists.keepOnly(keep)
        if (keep == null) {
            if (last.first() != null) context.syncData.edit { it.clear() }
            _problem.value = null
        }
    }

    private companion object {
        val SOURCE_ID = stringPreferencesKey("source_id")
        val SYNCED_AT = longPreferencesKey("synced_at")
        val SONGS = intPreferencesKey("songs")
        val ALBUMS = intPreferencesKey("albums")
        val ROWS = intPreferencesKey("rows_version")
    }
}

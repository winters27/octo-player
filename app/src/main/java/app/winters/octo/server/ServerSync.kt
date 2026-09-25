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
import app.winters.octo.catalog.SourceDao
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.data.userMessage
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

// Keeps a copy of the signed-in server's library beside the phone's music:
// copied after signing in, at app start when the last copy is old, and when
// asked. Disconnecting, or losing the sign-in, takes the server's music out.
@Singleton
class ServerSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionRepository,
    private val sources: SourceDao,
    private val merge: CatalogMerge,
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
                        val sourceId = serverSourceId(state.session.client.baseUrl)
                        dropServers(keep = sourceId)
                        val done = last.first()
                        val stale = done == null || done.sourceId != sourceId ||
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
        val client = (sessions.state.value as? SessionState.SignedIn)?.session?.client ?: return
        val sourceId = serverSourceId(client.baseUrl)
        _syncing.value = true
        try {
            val started = SystemClock.elapsedRealtime()
            val catalog = buildServerCatalog(sourceId, client.readLibrary())
            sources.replaceSource(sourceId, catalog.tracks, catalog.albums, catalog.artists)
            val library = merge.rebuild()
            context.syncData.edit { p ->
                p[SOURCE_ID] = sourceId
                p[SYNCED_AT] = System.currentTimeMillis()
                p[SONGS] = catalog.tracks.size
                p[ALBUMS] = catalog.albums.size
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
    }
}

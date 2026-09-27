package app.winters.octo.listening

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import app.winters.octo.BuildConfig
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.SourceDao
import app.winters.octo.catalog.isFind
import app.winters.octo.catalog.songDetails
import app.winters.octo.playback.isOpenedFile
import app.winters.octo.playback.isRadio
import app.winters.octo.playback.openedFileOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

// Sends what is played on this phone to the listener's ListenBrainz
// profile, with or without a server. Each counted play waits in a queue on
// the phone until ListenBrainz has it; sending never holds up playback or
// shows a message, and what fails is tried again later.
@Singleton
class ListenBrainzSync @Inject constructor(
    @ApplicationContext private val context: Context,
    http: OkHttpClient,
    private val store: ListenBrainzStore,
    private val catalog: CatalogDao,
    private val sources: SourceDao,
    private val online: OnlineDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api = ListenBrainzApi(listenBrainzClient(http, "Octo/${BuildConfig.VERSION_NAME} (Android)"))
    private val sending = Mutex()
    private val version = BuildConfig.VERSION_NAME
    private var started = false

    // Failures in a row, and when the next try is due after them.
    @Volatile private var failures = 0
    @Volatile private var nextTryAt = 0L
    private var retry: Job? = null

    val prefs: Flow<ListenBrainzPrefs> = store.prefs
    val queued: StateFlow<Int> = store.queued

    // At app start: sends what waited from last time, and again whenever a
    // connection comes back.
    fun start() {
        if (started) return
        started = true
        scope.launch {
            store.loadQueue()
            send(now = true)
        }
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { send(now = true) }
                }
            },
        )
    }

    // A play counted. It waits in the queue until ListenBrainz has it.
    fun played(trackId: String, startedAt: Long) {
        scope.launch {
            val prefs = store.current()
            if (!prefs.keepsPlays) return@launch
            val song = songOf(trackId) ?: return@launch
            if (!shouldSend(prefs.sendPlays, song.reachesServer)) return@launch
            val listen = song.listenAt(startedAt) ?: return@launch
            store.addListen(listen)
            send(now = false)
        }
    }

    // A song started. ListenBrainz shows it as playing now; a failure here
    // does not matter, as the next song replaces it.
    fun nowPlaying(trackId: String) {
        scope.launch {
            val prefs = store.current()
            if (!prefs.sends || !prefs.nowPlaying) return@launch
            val song = songOf(trackId) ?: return@launch
            if (!shouldSend(prefs.sendPlays, song.reachesServer)) return@launch
            val listen = song.listenAt(System.currentTimeMillis()) ?: return@launch
            val token = store.token() ?: return@launch
            if (api.playingNow(token, listen, version) == SubmitResult.Unauthorized) store.setNeedsAttention(true)
        }
    }

    // Checks a pasted token and, when ListenBrainz accepts it, keeps it and
    // sends what is waiting.
    suspend fun connect(token: String): TokenCheck {
        val clean = token.trim()
        val check = api.validate(clean)
        if (check is TokenCheck.Valid) {
            store.connect(clean, check.user)
            failures = 0
            nextTryAt = 0
            scope.launch { send(now = true) }
        }
        return check
    }

    fun disconnect() {
        scope.launch { sending.withLock { store.disconnect() } }
    }

    fun setEnabled(on: Boolean) {
        scope.launch {
            store.setEnabled(on)
            if (on) send(now = true)
        }
    }

    fun setSendPlays(mode: SendPlays) {
        scope.launch { store.setSendPlays(mode) }
    }

    fun setNowPlaying(on: Boolean) {
        scope.launch { store.setNowPlaying(on) }
    }

    // Sends the waiting plays, oldest first, in batches. `now` tries even
    // while waiting after failures; a new play waits its turn.
    private suspend fun send(now: Boolean) = sending.withLock {
        if (!now && System.currentTimeMillis() < nextTryAt) return@withLock
        if (!store.current().sends) return@withLock
        val token = store.token()
        if (token == null) {
            // The saved token can no longer be opened, so it needs pasting again.
            store.setNeedsAttention(true)
            return@withLock
        }
        while (true) {
            val batch = nextBatch(store.queue())
            if (batch.isEmpty()) break
            when (val result = deliver(token, batch)) {
                SubmitResult.Sent -> {
                    failures = 0
                    nextTryAt = 0
                }
                SubmitResult.Unauthorized -> {
                    store.setNeedsAttention(true)
                    return@withLock
                }
                is SubmitResult.RateLimited -> {
                    retryIn(result.retryInMs)
                    return@withLock
                }
                SubmitResult.Failed, SubmitResult.Rejected -> {
                    failures++
                    retryIn(retryDelayMs(failures))
                    return@withLock
                }
            }
        }
    }

    // Sends one batch. When ListenBrainz refuses a batch, its halves go on
    // their own, so one bad play does not hold back the rest; a single play
    // refused is let go.
    private suspend fun deliver(token: String, batch: List<Listen>): SubmitResult {
        val result = api.submit(token, batch, version)
        return when {
            result == SubmitResult.Sent || (result == SubmitResult.Rejected && batch.size == 1) -> {
                store.removeListens(batch)
                SubmitResult.Sent
            }
            result == SubmitResult.Rejected -> {
                val half = batch.size / 2
                val first = deliver(token, batch.subList(0, half))
                if (first != SubmitResult.Sent) first else deliver(token, batch.subList(half, batch.size))
            }
            else -> result
        }
    }

    private fun retryIn(delayMs: Long) {
        nextTryAt = System.currentTimeMillis() + delayMs
        retry?.cancel()
        retry = scope.launch {
            delay(delayMs)
            send(now = true)
        }
    }

    // What the app knows about a song that played. Radio streams are left
    // out; an opened file goes only when it has a title and artist.
    private suspend fun songOf(trackId: String): PlayedSong? = when {
        isRadio(trackId) -> null
        isOpenedFile(trackId) -> openedFileOf(trackId)?.let { PlayedSong(it.title, it.artist, "", 0, null, reachesServer = false) }
        isFind(trackId) -> online.song(trackId)?.let { PlayedSong(it.title, it.artist, it.album, it.durationMs, null, reachesServer = true) }
        else -> catalog.track(trackId)?.let { track ->
            val copies = sources.copies(trackId)
            val details = songDetails(copies)
            PlayedSong(
                track.title, track.artist, track.album, track.durationMs, track.trackNo,
                playReachesServer(trackId, copies.map { it.sourceId }),
                recordingMbid = details.mbRecordingId,
                releaseMbid = details.mbAlbumId,
                releaseGroupMbid = details.mbReleaseGroupId,
                artistMbids = details.mbArtistIds,
            )
        }
    }
}

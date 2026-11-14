package app.winters.octo.offline

import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import app.winters.octo.catalog.isFind
import app.winters.octo.playback.Streams
import app.winters.octo.playback.parseStreamUri
import app.winters.octo.playback.streamUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// How long a new song plays before the next ones are fetched, so the song
// itself gets the connection first.
private const val START_AFTER_MS = 5_000L

// While music plays, fetches the next few server songs of the queue into
// the saved songs, so they start at once and still play if the connection
// drops. How many depends on the connection: Wi-Fi and mobile data have
// their own counts. Any change to what comes next starts it over. Songs
// found online are never fetched ahead, only saved as they play.
@OptIn(UnstableApi::class)
@Singleton
class Prefetcher @Inject constructor(
    private val streams: Streams,
    private val settings: OfflineSettings,
    private val saved: StreamCache,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var player: Player? = null
    private var settingsJob: Job? = null
    private var job: Job? = null
    private var prefs = OfflinePrefs()

    // The songs being fetched, so the same list is not started twice.
    private var ahead: List<String> = emptyList()

    // The fetch running now, so a stop can end it mid-song.
    private val gate = FetchGate<CacheWriter> { it.cancel() }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                )
            ) {
                refresh()
            }
        }
    }

    // Follows this player until detached. Called on its thread.
    fun attach(player: Player) {
        this.player = player
        player.addListener(listener)
        settingsJob = scope.launch {
            settings.prefs.collect {
                withContext(Dispatchers.Main) {
                    prefs = it
                    refresh()
                }
            }
        }
    }

    fun detach() {
        player?.removeListener(listener)
        player = null
        settingsJob?.cancel()
        stop()
    }

    private fun refresh() {
        val player = player ?: return
        val next = if (player.isPlaying) upcoming(player, count()) else emptyList()
        if (next == ahead && job?.isActive == true) return
        stop()
        ahead = next
        if (next.isEmpty()) return
        job = scope.launch {
            delay(START_AFTER_MS)
            for (uri in next) {
                ensureActive()
                if (!fetch(uri) { isActive }) break
            }
        }
    }

    private fun stop() {
        gate.stop {
            job?.cancel()
            job = null
        }
        ahead = emptyList()
    }

    private fun count(): Int = when {
        !saved.enabled.value -> 0
        streams.onWifi() -> prefs.prefetchWifi
        streams.online() -> prefs.prefetchMobile
        else -> 0
    }

    // The addresses of the server songs among the next `count` in play
    // order, songs found online left out.
    private fun upcoming(player: Player, count: Int): List<String> {
        if (count <= 0) return emptyList()
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        // Repeating one song still moves on to the next once it is skipped.
        val repeat = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else player.repeatMode
        val uris = ArrayList<String>()
        var index = player.currentMediaItemIndex
        repeat(count) {
            index = timeline.getNextWindowIndex(index, repeat, player.shuffleModeEnabled)
            if (index == C.INDEX_UNSET || index == player.currentMediaItemIndex) return uris
            val item = player.getMediaItemAt(index)
            val uri = item.localConfiguration?.uri?.toString()
            if (uri != null && !isFind(item.mediaId) && parseStreamUri(uri) != null) uris += uri
        }
        return uris
    }

    // Saves one song whole unless it already is. False when fetching should
    // stop, such as when the connection is gone or the fetching was stopped
    // (`active` false) before this song began.
    private fun fetch(uri: String, active: () -> Boolean): Boolean {
        val ref = parseStreamUri(uri)?.let(streams::pinned) ?: return true
        val key = streams.cacheKey(ref) ?: return true
        if (saved.isFullySaved(key)) return true
        val source = streams.savingSource() ?: return false
        val cacheWriter = CacheWriter(source, DataSpec(streamUri(ref).toUri()), null, null)
        if (!gate.start(cacheWriter, active)) return false
        return try {
            cacheWriter.cache()
            true
        } catch (e: IOException) {
            Log.i("Octo", "fetch ahead stopped: ${e.javaClass.simpleName}")
            false
        } finally {
            gate.done(cacheWriter)
            saved.refreshUsed()
        }
    }
}

// The one fetch running now, made safe to stop: a stop and the start of a
// fetch never cross, so a fetch either starts first and the stop cancels it
// (`cancel`), or it sees the stop and never starts. Safe across threads.
internal class FetchGate<W : Any>(private val cancel: (W) -> Unit) {
    private val lock = Any()
    private var running: W? = null

    // Stops the fetching (`stopJob`, which cancels its job) and the fetch
    // running now, as one step.
    fun stop(stopJob: () -> Unit) = synchronized(lock) {
        stopJob()
        running?.let(cancel)
        running = null
    }

    // Takes `writer` as the fetch running now, unless the fetching was
    // stopped first (`active` false). Answers whether it may run.
    fun start(writer: W, active: () -> Boolean): Boolean = synchronized(lock) {
        if (!active()) return false
        running = writer
        true
    }

    // A fetch ended; a later one that started since stays the running one.
    fun done(writer: W) = synchronized(lock) {
        if (running === writer) running = null
    }
}

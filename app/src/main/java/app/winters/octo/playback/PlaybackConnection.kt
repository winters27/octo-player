package app.winters.octo.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.winters.octo.catalog.CatalogDao
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// What the app shows about the song that is on.
data class NowPlaying(
    val trackId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val artwork: String? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
)

// The app's line to the playback service. Screens ask it to play things and
// read what is playing from it; the service does the rest.
@Singleton
class PlaybackConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalog: CatalogDao,
) {
    private val scope = MainScope()
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    // Requests made before the connection is up run as soon as it is.
    private val waiting = mutableListOf<(MediaController) -> Unit>()

    private val _now = MutableStateFlow(NowPlaying())
    val now: StateFlow<NowPlaying> = _now

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    // Called when the app comes to the front.
    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, OctoPlaybackService::class.java))
        val pending = MediaController.Builder(context, token).buildAsync()
        future = pending
        pending.addListener(
            {
                val ready = runCatching { pending.get() }.getOrNull() ?: return@addListener
                controller = ready
                ready.addListener(listener)
                publish(ready)
                waiting.forEach { it(ready) }
                waiting.clear()
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    // Called when the app leaves the front, so the service can stop when
    // nothing is playing.
    fun release() {
        controller?.removeListener(listener)
        future?.let(MediaController::releaseFuture)
        future = null
        controller = null
    }

    fun positionMs(): Long = controller?.currentPosition ?: 0

    // Plays a list of songs, starting at one of them, or shuffled.
    fun playTracks(trackIds: List<String>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (trackIds.isEmpty()) return
        withController { c ->
            val items = trackIds.map(::songRequest)
            c.shuffleModeEnabled = shuffle
            if (shuffle) c.setMediaItems(items, true) else c.setMediaItems(items, startIndex, 0)
            c.prepare()
            c.play()
        }
    }

    fun playAlbum(albumId: String, shuffle: Boolean = false) {
        scope.launch { playTracks(catalog.albumTrackIds(albumId), 0, shuffle) }
    }

    // With nothing queued, the play button shuffles the whole library.
    fun togglePlayPause() = withController { c ->
        when {
            c.mediaItemCount == 0 -> scope.launch { playTracks(catalog.allTrackIds(), shuffle = true) }
            c.isPlaying -> c.pause()
            else -> {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                c.play()
            }
        }
    }

    fun next() = withController { it.seekToNext() }

    fun previous() = withController { it.seekToPrevious() }

    fun playNext(trackIds: List<String>) = withController { c ->
        c.addMediaItems((c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount), trackIds.map(::songRequest))
    }

    fun playLast(trackIds: List<String>) = withController { c -> c.addMediaItems(trackIds.map(::songRequest)) }

    private fun withController(action: (MediaController) -> Unit) {
        val ready = controller
        if (ready != null) action(ready) else {
            waiting += action
            connect()
        }
    }

    private fun publish(player: Player) {
        val item: MediaItem? = player.currentMediaItem
        val meta = item?.mediaMetadata
        _now.value = NowPlaying(
            trackId = item?.mediaId,
            title = meta?.title?.toString(),
            artist = meta?.artist?.toString(),
            artwork = meta?.artworkRef(),
            isPlaying = player.isPlaying,
            durationMs = player.duration.takeIf { it > 0 } ?: meta?.durationMs ?: 0,
        )
    }
}

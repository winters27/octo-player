package app.winters.octo.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import app.winters.octo.playback.artworkRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Keeps the now playing widget in step with the player. The playback service
// calls it only when the song changes or it starts or stops playing, so it
// costs nothing while music simply plays.
@OptIn(UnstableApi::class)
class WidgetUpdates(private val context: Context, private val scope: CoroutineScope) {
    private var playback: WidgetPlayback? = null
    private var artRef: String? = null
    private var art: Bitmap? = null
    // Whether the cover for artRef was tried, found or not.
    private var loaded = false
    private var loading: Job? = null

    fun show(player: Player) {
        val item = player.currentMediaItem
        val meta = item?.mediaMetadata
        playback = item?.let {
            WidgetPlayback(
                title = meta?.title?.toString(),
                artist = meta?.artist?.toString(),
                // Buffering after a press of play already shows pause.
                isPlaying = !Util.shouldShowPlayButton(player),
            )
        }
        val ref = meta?.artworkRef()
        if (ref != artRef) {
            artRef = ref
            art = null
            loaded = false
            loading?.cancel()
            loading = null
        }
        // The cover is loaded once per song, and only with a widget placed.
        if (ref != null && !loaded && loading == null && NowPlayingWidget.placed(context)) {
            // The words wait for the cover, so the two change together.
            loading = scope.launch {
                art = withContext(Dispatchers.IO) { runCatching { widgetArt(context, ref) }.getOrNull() }
                loaded = true
                loading = null
                NowPlayingWidget.show(context, playback, art)
            }
        }
        // While it loads, it shows the newest words once it is done.
        if (loading == null) NowPlayingWidget.show(context, playback, art)
    }

    // The service is going away: back to "Nothing playing".
    fun clear() {
        loading?.cancel()
        loading = null
        playback = null
        artRef = null
        art = null
        loaded = false
        NowPlayingWidget.show(context, null, null)
    }
}

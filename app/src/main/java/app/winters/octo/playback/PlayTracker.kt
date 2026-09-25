package app.winters.octo.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

// Counts how long each song was actually heard (pauses excluded) and
// records a play when it moves on, if enough of it was heard. Also says when
// each song first starts to play.
class PlayTracker(
    private val plays: PlayStore,
    private val isPlaying: () -> Boolean,
) : Player.Listener {
    private var trackId: String? = null
    private var durationMs = 0L
    private var startedAt = 0L
    private var heardMs = 0L
    private var playingSince: Long? = null
    private var announced = false

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            playingSince = SystemClock.elapsedRealtime()
            announce()
        } else {
            stopClock()
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // Songs change while music keeps playing, so the clock keeps running.
        flush(stillPlaying = isPlaying())
        trackId = mediaItem?.mediaId
        durationMs = mediaItem?.mediaMetadata?.durationMs ?: 0
        startedAt = System.currentTimeMillis()
        announced = false
        if (isPlaying()) announce()
    }

    // Once per song, the first time it actually plays.
    private fun announce() {
        if (announced) return
        announced = true
        trackId?.let(plays::started)
    }

    // Records the current song if it counts, then starts over.
    fun flush(stillPlaying: Boolean = false) {
        stopClock()
        trackId?.let { plays.record(it, startedAt, heardMs, durationMs) }
        heardMs = 0
        if (stillPlaying) playingSince = SystemClock.elapsedRealtime()
    }

    private fun stopClock() {
        playingSince?.let { heardMs += SystemClock.elapsedRealtime() - it }
        playingSince = null
    }
}

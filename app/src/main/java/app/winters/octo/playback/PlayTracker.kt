package app.winters.octo.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

// Counts how long each song was actually heard (pauses excluded) and
// records a play the moment enough of it was heard, or when it moves on if
// that moment was missed. Also says when each song first starts to play.
class PlayTracker(
    private val started: (String) -> Unit,
    private val record: (trackId: String, startedAt: Long, heardMs: Long, durationMs: Long) -> Unit,
    private val isPlaying: () -> Boolean,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
    // Runs a block after a wait, on the main thread; gives back a way to call it off.
    private val later: (Long, () -> Unit) -> () -> Unit = ::onMainLater,
) : Player.Listener {
    private var trackId: String? = null
    private var durationMs = 0L
    private var startedAt = 0L
    private var heardMs = 0L
    private var playingSince: Long? = null
    private var announced = false
    private var counted = false
    private var callOff: (() -> Unit)? = null

    override fun onIsPlayingChanged(isPlaying: Boolean) = playingChanged(isPlaying)

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
        moved(mediaItem?.mediaId, mediaItem?.mediaMetadata?.durationMs ?: 0)

    fun playingChanged(playing: Boolean) {
        if (playing) {
            playingSince = clock()
            announce()
            watch()
        } else {
            stopClock()
            stopWatching()
        }
    }

    fun moved(id: String?, length: Long) {
        // Songs change while music keeps playing, so the clock keeps running.
        flush(stillPlaying = isPlaying())
        trackId = id
        durationMs = length
        startedAt = wallClock()
        announced = false
        counted = false
        if (isPlaying()) {
            announce()
            watch()
        }
    }

    // Once per song, the first time it actually plays.
    private fun announce() {
        if (announced) return
        announced = true
        trackId?.let(started)
    }

    // Records the current song if it counts and was not recorded already,
    // then starts over.
    fun flush(stillPlaying: Boolean = false) {
        stopWatching()
        stopClock()
        if (!counted) trackId?.let { record(it, startedAt, heardMs, durationMs) }
        heardMs = 0
        if (stillPlaying) playingSince = clock()
    }

    private fun heardNow(): Long = heardMs + (playingSince?.let { clock() - it } ?: 0)

    // Waits for the moment the song counts, while it plays and has not yet.
    private fun watch() {
        stopWatching()
        if (counted || trackId == null || playingSince == null) return
        val left = msLeftToCount(heardNow(), durationMs) ?: return
        callOff = later(maxOf(left, 1)) { due() }
    }

    private fun due() {
        callOff = null
        val id = trackId ?: return
        if (counted || playingSince == null) return
        val heard = heardNow()
        if (!countsAsPlay(heard, durationMs)) return watch()
        counted = true
        record(id, startedAt, heard, durationMs)
    }

    private fun stopWatching() {
        callOff?.invoke()
        callOff = null
    }

    private fun stopClock() {
        playingSince?.let { heardMs += clock() - it }
        playingSince = null
    }
}

private val main by lazy { Handler(Looper.getMainLooper()) }

private fun onMainLater(ms: Long, block: () -> Unit): () -> Unit {
    val run = Runnable(block)
    main.postDelayed(run, ms)
    return { main.removeCallbacks(run) }
}

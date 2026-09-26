package app.winters.octo.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// Where the sleep timer is: off, counting down, or waiting for songs to end.
sealed interface SleepState {
    data object Off : SleepState
    data class Counting(val remainingMs: Long) : SleepState
    data object EndOfSong : SleepState

    // Waiting for this many songs to end, the one playing included. Always
    // 2 or more; the last one is EndOfSong.
    data class Songs(val left: Int) : SleepState

    // Waiting for one song further down the queue to end: `key` is its
    // queue entry, `title` what to call it.
    data class AfterSong(val key: String, val title: String) : SleepState
}

// What the sleep timer works on: the player, or a stand-in in tests.
interface SleepTarget {
    // Volume on top of any ducking: 1 is full, 0 is silent.
    var sleepFade: Float
    var pauseAtEndOfSong: Boolean

    // The queue entry playing now.
    val currentKey: String? get() = null

    // Whether a crossfade is under way, so a song change is the song ending.
    val isBlending: Boolean get() = false

    // Whether a queue entry is still in the queue.
    fun hasEntry(key: String): Boolean = true
    fun pause()
    fun addListener(listener: Player.Listener)
    fun removeListener(listener: Player.Listener)
}

// A song changed because the one before it ended, not by a skip or a new
// queue: on its own, on repeat, or through a crossfade.
private fun endedOnItsOwn(reason: Int, blending: Boolean): Boolean =
    reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
        reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT ||
        (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && blending)

// The last stretch of a timer, over which the music fades out.
const val SLEEP_FADE_MS = 30_000L

// How loud the music is with this long left: full until the last 30
// seconds, then down to silence. Squared, so the fade sounds even.
fun sleepFade(remainingMs: Long): Float {
    val left = (remainingMs.toFloat() / SLEEP_FADE_MS).coerceIn(0f, 1f)
    return left * left
}

// How long until the next tick: on each whole second while counting, so
// the clock on screen never skips, and ten times a second through the fade.
private fun untilNextTick(remainingMs: Long): Long {
    val step = if (remainingMs > SLEEP_FADE_MS) 1_000L else 100L
    return (remainingMs - 1) % step + 1
}

// Stops the music after a while, or at the end of the song. The service
// attaches its player; the app starts and cancels it. The clock is passed
// in so tests can run it on their own time.
@Singleton
class SleepTimer internal constructor(
    private val clock: () -> Long,
    private val scope: CoroutineScope,
) {
    @Inject constructor() : this(SystemClock::elapsedRealtime, MainScope())

    private val _state = MutableStateFlow<SleepState>(SleepState.Off)
    val state: StateFlow<SleepState> = _state

    private var target: SleepTarget? = null
    private var ticker: Job? = null
    private var endsAt = 0L

    // A timer waiting on songs follows them: it counts songs as they end,
    // is done once the player pauses at the end of the last one, and goes
    // when the song it waits for leaves the queue.
    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val songEnded = !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            if (songEnded && stopsAfterThisSong()) cancel()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val now = _state.value
            if (now is SleepState.Songs && endedOnItsOwn(reason, target?.isBlending == true)) {
                set(if (now.left <= 2) SleepState.EndOfSong else SleepState.Songs(now.left - 1))
            } else if (now is SleepState.AfterSong) {
                target?.let(::apply)
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val now = _state.value
            if (now is SleepState.AfterSong && target?.hasEntry(now.key) == false) cancel()
        }
    }

    fun attach(player: SleepTarget) {
        target = player
        player.addListener(listener)
        apply(player)
    }

    // A running timer goes with the service: there is nothing left for it to stop.
    fun detach() {
        cancel()
        target?.removeListener(listener)
        target = null
    }

    // Replaces any timer that is already set.
    fun start(minutes: Int) {
        ticker?.cancel()
        endsAt = clock() + minutes * 60_000L
        val first = tick()
        ticker = scope.launch {
            var left = first
            while (left > 0) {
                delay(untilNextTick(left))
                left = tick()
            }
        }
    }

    fun startEndOfSong() {
        ticker?.cancel()
        ticker = null
        set(SleepState.EndOfSong)
    }

    // Adds time to a running countdown, lifting any fade already begun.
    fun extend(minutes: Int) {
        if (_state.value !is SleepState.Counting) return
        endsAt += minutes * 60_000L
        tick()
    }

    // Stops once this many songs have ended, the one playing included.
    fun startAfterSongs(count: Int) {
        if (count <= 1) return startEndOfSong()
        ticker?.cancel()
        ticker = null
        set(SleepState.Songs(count))
    }

    // Stops once one song in the queue has ended: `key` is its queue entry.
    fun startAfter(key: String, title: String) {
        if (key == target?.currentKey) return startEndOfSong()
        ticker?.cancel()
        ticker = null
        set(SleepState.AfterSong(key, title))
    }

    // Also puts the volume back and stops pausing at the end of the song.
    fun cancel() {
        ticker?.cancel()
        ticker = null
        set(SleepState.Off)
    }

    // Moves the countdown and the fade along; at zero, pauses and turns off.
    private fun tick(): Long {
        val remaining = (endsAt - clock()).coerceAtLeast(0)
        set(SleepState.Counting(remaining))
        if (remaining == 0L) {
            target?.pause()
            cancel()
        }
        return remaining
    }

    private fun set(state: SleepState) {
        _state.value = state
        target?.let(::apply)
    }

    private fun apply(player: SleepTarget) {
        val now = _state.value
        player.sleepFade = if (now is SleepState.Counting) sleepFade(now.remainingMs) else 1f
        player.pauseAtEndOfSong = stopsAfterThisSong()
    }

    // Whether the song playing now is the last one before stopping.
    private fun stopsAfterThisSong(): Boolean {
        val now = _state.value
        return now == SleepState.EndOfSong || (now is SleepState.AfterSong && now.key == target?.currentKey)
    }
}

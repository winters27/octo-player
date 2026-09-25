package app.winters.octo.playback

import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// Where the sleep timer is: off, counting down, or waiting for the song to end.
sealed interface SleepState {
    data object Off : SleepState
    data class Counting(val remainingMs: Long) : SleepState
    data object EndOfSong : SleepState
}

// What the sleep timer works on: the player, or a stand-in in tests.
interface SleepTarget {
    // Volume on top of any ducking: 1 is full, 0 is silent.
    var sleepFade: Float
    var pauseAtEndOfSong: Boolean
    fun pause()
    fun addListener(listener: Player.Listener)
    fun removeListener(listener: Player.Listener)
}

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

    // The end-of-song timer is done once the player pauses at the end of the song.
    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val songEnded = !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            if (songEnded && _state.value == SleepState.EndOfSong) cancel()
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
        player.pauseAtEndOfSong = now == SleepState.EndOfSong
    }
}

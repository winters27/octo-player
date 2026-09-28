package app.winters.octo.desktop.player

import app.winters.octo.playback.SleepState
import app.winters.octo.playback.sleepFade
import app.winters.octo.playback.untilNextSleepTick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Stops the music after a while, at the end of the song, or after some
// songs, as the phone's timer does. A countdown fades the music out over
// its last 30 seconds (through the player's fade, so the volume the
// listener set is kept) and pauses at zero.
//
// "Stop after this song" on the player and the timer's end of this song
// are one switch: turning either on turns on the other, and turning either
// off (or the song ending) turns both off. A timer waiting on songs owns
// the switch and sets it for the last song. A countdown is separate and
// leaves it alone. The clock is passed in so tests run on their own time.
class SleepTimer(
    private val player: DesktopPlayer,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val lock = Any()
    private val _state = MutableStateFlow<SleepState>(SleepState.Off)
    val state: StateFlow<SleepState> = _state

    private var ticker: Job? = null
    private var endsAt = 0L

    // Whether stop-after-this-song is on as the timer's own doing.
    private var holdsStop = false

    // The player's count of songs ended, when songs were last counted.
    private var endedSeen = 0

    // Bumped by every new timer, so a fade put back late never undoes one.
    private var generation = 0

    init {
        scope.launch { player.state.collect { follow() } }
    }

    // A countdown of `minutes`. Replaces any timer already set.
    fun start(minutes: Int) {
        synchronized(lock) {
            replace()
            endsAt = clock() + minutes.coerceAtLeast(1) * 60_000L
            val mine = generation
            val first = tick()
            if (first <= 0) return
            ticker = scope.launch {
                var left = first
                while (left > 0) {
                    delay(untilNextSleepTick(left))
                    // A tick that lost the race with a cancel does nothing.
                    left = synchronized(lock) { if (generation == mine) tick() else 0L }
                }
            }
        }
    }

    // Adds time to a running countdown, lifting any fade already begun.
    fun extend(minutes: Int) {
        synchronized(lock) {
            if (_state.value !is SleepState.Counting) return
            endsAt += minutes * 60_000L
            tick()
        }
    }

    // Pauses when the song playing ends.
    fun endOfSong() {
        synchronized(lock) {
            replace()
            holdStop()
            _state.value = SleepState.EndOfSong
        }
    }

    // Pauses once this many songs have ended by themselves, the one playing
    // included. Skipped songs do not count.
    fun afterSongs(count: Int) {
        if (count <= 1) return endOfSong()
        synchronized(lock) {
            replace()
            releaseStop()
            endedSeen = player.state.value.ended
            _state.value = SleepState.Songs(count)
        }
    }

    // Pauses once one song in the queue has ended: `key` is its queue
    // entry, `title` what to call it.
    fun after(key: Long, title: String) {
        if (key == player.state.value.current?.key) return endOfSong()
        synchronized(lock) {
            replace()
            releaseStop()
            _state.value = SleepState.AfterSong(key.toString(), title)
        }
        follow()
    }

    // Turns the timer off, puts the volume back, and turns off
    // stop-after-this-song when the timer had turned it on.
    fun cancel() {
        synchronized(lock) {
            replace()
            _state.value = SleepState.Off
        }
    }

    // Clears whatever timer there is, before a new one or none: the
    // countdown and its fade, and the switch when the timer turned it on.
    private fun replace() {
        generation++
        ticker?.cancel()
        ticker = null
        if (_state.value is SleepState.Counting) player.setFade(1f)
        if (holdsStop) {
            holdsStop = false
            player.setStopAfterCurrent(false)
        }
    }

    // A timer waiting on songs owns the switch, so one turned on beside a
    // countdown goes off until the last song.
    private fun releaseStop() {
        if (player.state.value.stopAfterCurrent) player.setStopAfterCurrent(false)
    }

    // Moves the countdown and the fade along; at zero, pauses and turns off.
    private fun tick(): Long {
        val remaining = (endsAt - clock()).coerceAtLeast(0)
        _state.value = SleepState.Counting(remaining)
        player.setFade(sleepFade(remaining))
        if (remaining == 0L) {
            player.pause()
            ticker = null
            _state.value = SleepState.Off
            // The volume comes back once the pause has faded out, so the
            // pause's own short fade is not heard at full volume.
            val mine = ++generation
            scope.launch {
                delay(FADE_BACK_AFTER_MS)
                synchronized(lock) { if (generation == mine) player.setFade(1f) }
            }
        }
        return remaining
    }

    private fun holdStop() {
        holdsStop = true
        player.setStopAfterCurrent(true)
    }

    // Keeps the timer in step with the player: songs ending, the switch
    // turned on or off, and the awaited song leaving the queue.
    private fun follow() {
        synchronized(lock) {
            val now = player.state.value
            val timer = _state.value
            if (timer !is SleepState.Counting && now.stopAfterCurrent != holdsStop) {
                if (now.stopAfterCurrent) {
                    // Turned on from the player: the timer's end of this song.
                    generation++
                    holdsStop = true
                    _state.value = SleepState.EndOfSong
                } else {
                    // The song ended and the player paused, or it was turned off.
                    holdsStop = false
                    _state.value = SleepState.Off
                }
                return
            }
            when (timer) {
                is SleepState.Songs -> {
                    val done = now.ended - endedSeen
                    if (done <= 0) return
                    endedSeen = now.ended
                    val left = timer.left - done
                    if (left <= 1) {
                        holdStop()
                        _state.value = SleepState.EndOfSong
                    } else {
                        _state.value = SleepState.Songs(left)
                    }
                }
                is SleepState.AfterSong -> when {
                    now.queue.none { it.key.toString() == timer.key } -> {
                        replace()
                        _state.value = SleepState.Off
                    }
                    now.current?.key?.toString() == timer.key && !holdsStop -> holdStop()
                }
                else -> {}
            }
        }
    }

    private companion object {
        const val FADE_BACK_AFTER_MS = 500L
    }
}

package app.winters.octo.desktop.player

import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

// A stand-in player with no sound, until the audio engine is wired in. It
// keeps the queue exactly as the real player will, and keeps time from a
// clock as if each song were playing, moving on at the end of each one. It
// exists so every screen can be built and tested now.
class SilentPlayer(
    // Milliseconds from any fixed point; the tests pass their own.
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    // Keeps time moving on its own when given; the tests call tick() instead.
    scope: CoroutineScope? = null,
    random: Random = Random.Default,
    volume: Float = 0.8f,
) : DesktopPlayer {
    private val queue = PlayQueue(random)
    private val lock = Any()
    private val system = OutputDevice("default", "System default")

    // Where the song was at `since`, and whether time runs from there.
    private var anchorMs = 0L
    private var since = clock()
    private var running = false
    private var shuffle = false
    private var repeat = RepeatMode.Off
    private var level = volume.coerceIn(0f, 1f)

    private val _state = MutableStateFlow(PlayerState(volume = level, output = system, outputs = listOf(system)))
    override val state: StateFlow<PlayerState> = _state

    private val ticker: Job? = scope?.launch {
        while (isActive) {
            delay(TICK_MS)
            tick()
        }
    }

    override fun positionMs(): Long = synchronized(lock) { rawPosition().coerceAtMost(durationMs().coerceAtLeast(0)) }

    private fun rawPosition(): Long = if (running) anchorMs + (clock() - since) else anchorMs

    private fun durationMs(): Long = (queue.currentEntry?.song?.duration ?: 0) * 1000L

    // Moves on when the song has run out. The real engine hears this from
    // the audio; here the clock says so.
    fun tick() {
        synchronized(lock) {
            if (!running) return
            val duration = durationMs()
            // A song of unknown length never ends by itself.
            if (duration <= 0) return
            var over = rawPosition() - duration
            while (running && over >= 0) {
                val endedAt = clock() - over
                if (repeat == RepeatMode.One) {
                    anchorMs = 0
                    since = endedAt
                } else {
                    val next = queue.nextPosition(repeat)
                    if (next == null) {
                        running = false
                        anchorMs = duration
                        publish()
                        return
                    }
                    queue.moveTo(next)
                    anchorMs = 0
                    since = endedAt
                }
                val length = durationMs()
                if (length <= 0) break
                over = rawPosition() - length
            }
            publish()
        }
    }

    override fun play(songs: List<Song>, start: Int, shuffle: Boolean) {
        synchronized(lock) {
            this.shuffle = shuffle
            queue.replace(songs, start, shuffle)
            restartAt(0, play = queue.currentEntry != null)
        }
    }

    override fun resume() {
        synchronized(lock) {
            if (queue.currentEntry == null || running) return
            // At the very end of the last song, Play starts it over.
            if (anchorMs >= durationMs() && durationMs() > 0) anchorMs = 0
            since = clock()
            running = true
            publish()
        }
    }

    override fun pause() {
        synchronized(lock) {
            if (!running) return
            anchorMs = rawPosition()
            running = false
            publish()
        }
    }

    override fun togglePlay() {
        if (state.value.playing) pause() else resume()
    }

    override fun next() {
        synchronized(lock) {
            val next = queue.nextPosition(if (repeat == RepeatMode.One) RepeatMode.All else repeat) ?: return
            queue.moveTo(next)
            restartAt(0, play = running)
        }
    }

    override fun previous() {
        synchronized(lock) {
            if (queue.currentEntry == null) return
            val back = queue.previousPosition(if (repeat == RepeatMode.One) RepeatMode.All else repeat)
            if (rawPosition() > RESTART_AFTER_MS || back == null) {
                restartAt(0, play = running)
            } else {
                queue.moveTo(back)
                restartAt(0, play = running)
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        synchronized(lock) {
            if (queue.currentEntry == null) return
            restartAt(positionMs.coerceIn(0, durationMs().coerceAtLeast(0)), play = running)
        }
    }

    override fun skipTo(key: Long) {
        synchronized(lock) {
            if (queue.jumpTo(key)) restartAt(0, play = true)
        }
    }

    override fun playNext(songs: List<Song>) {
        synchronized(lock) {
            val wasEmpty = queue.currentEntry == null
            queue.playNext(songs)
            if (wasEmpty) restartAt(0, play = false) else publish()
        }
    }

    override fun addToQueue(songs: List<Song>) {
        synchronized(lock) {
            val wasEmpty = queue.currentEntry == null
            queue.add(songs)
            if (wasEmpty) restartAt(0, play = false) else publish()
        }
    }

    override fun moveUpcoming(from: Int, to: Int) {
        synchronized(lock) {
            queue.moveUpcoming(from, to)
            publish()
        }
    }

    override fun remove(key: Long) {
        synchronized(lock) {
            if (queue.remove(key)) restartAt(0, play = running && queue.currentEntry != null) else publish()
        }
    }

    override fun clear() {
        synchronized(lock) {
            queue.clear()
            restartAt(0, play = false)
        }
    }

    override fun setShuffle(on: Boolean) {
        synchronized(lock) {
            shuffle = on
            queue.setShuffle(on)
            publish()
        }
    }

    override fun setRepeat(mode: RepeatMode) {
        synchronized(lock) {
            repeat = mode
            publish()
        }
    }

    override fun setVolume(volume: Float) {
        synchronized(lock) {
            level = volume.coerceIn(0f, 1f)
            publish()
        }
    }

    override fun selectOutput(id: String) {
        // One output only, until the engine lists the real ones.
    }

    override fun close() {
        ticker?.cancel()
    }

    private fun restartAt(position: Long, play: Boolean) {
        anchorMs = position
        since = clock()
        running = play && queue.currentEntry != null
        publish()
    }

    private fun publish() {
        _state.value = PlayerState(
            queue = queue.songs,
            current = queue.currentEntry,
            upcoming = queue.upcoming,
            playing = running,
            shuffle = shuffle,
            repeat = repeat,
            volume = level,
            durationMs = durationMs(),
            output = system,
            outputs = listOf(system),
        )
    }

    private companion object {
        const val TICK_MS = 200L
    }
}

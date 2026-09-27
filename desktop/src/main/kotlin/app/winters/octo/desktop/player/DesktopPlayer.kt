package app.winters.octo.desktop.player

import app.winters.octo.subsonic.Song
import kotlinx.coroutines.flow.StateFlow

// Where the sound goes: a sound card, headphones, a USB DAC.
data class OutputDevice(val id: String, val name: String)

// What the player is doing, for the screen. Position is not in here: it
// moves all the time, so it is read with positionMs() while drawing.
data class PlayerState(
    // Every entry in queue order.
    val queue: List<QueueEntry> = emptyList(),
    // The entry playing (or paused), if any.
    val current: QueueEntry? = null,
    // The entries still to come, in the order they will play.
    val upcoming: List<QueueEntry> = emptyList(),
    val playing: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    // From 0 to 1.
    val volume: Float = 0.8f,
    val durationMs: Long = 0,
    // The output in use, and the ones there are.
    val output: OutputDevice? = null,
    val outputs: List<OutputDevice> = emptyList(),
    // Why the last song could not play, in plain words, if it could not.
    val problem: String? = null,
)

// What the desktop app needs from whatever plays its music. The screens
// only ever talk to this. Phase one is SilentPlayer, which keeps time and
// the queue with no sound; the Rust audio engine takes its place behind the
// same calls, and has to pass the same contract tests.
interface DesktopPlayer : AutoCloseable {
    val state: StateFlow<PlayerState>

    // Where the song is, in milliseconds, right now.
    fun positionMs(): Long

    // Replaces the queue and plays from `start`. Shuffled, that song plays
    // first and the rest in a random order.
    fun play(songs: List<Song>, start: Int = 0, shuffle: Boolean = false)

    fun resume()

    fun pause()

    fun togglePlay()

    fun next()

    // Back to the start of the song, or to the one before when it has only
    // just begun.
    fun previous()

    fun seekTo(positionMs: Long)

    // Plays a queue entry now.
    fun skipTo(key: Long)

    // Puts songs right after the current one.
    fun playNext(songs: List<Song>)

    // Puts songs at the end of what is to come.
    fun addToQueue(songs: List<Song>)

    // Moves a song still to come, counted from the first after the current.
    fun moveUpcoming(from: Int, to: Int)

    fun remove(key: Long)

    // Empties the queue and stops.
    fun clear()

    fun setShuffle(on: Boolean)

    fun setRepeat(mode: RepeatMode)

    fun setVolume(volume: Float)

    fun selectOutput(id: String)
}

// How far into a song "Previous" restarts it instead of going back.
const val RESTART_AFTER_MS = 3_000L

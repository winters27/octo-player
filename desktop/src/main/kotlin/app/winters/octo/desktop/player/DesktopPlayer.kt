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
    // The entries already played before the current one, in play order.
    val played: List<QueueEntry> = emptyList(),
    // How many times the current entry has started over by itself (repeat
    // one), so each time round counts as its own play.
    val rounds: Int = 0,
    val playing: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    // From 0 to 1.
    val volume: Float = 0.8f,
    val durationMs: Long = 0,
    // The output in use, and the ones there are.
    val output: OutputDevice? = null,
    val outputs: List<OutputDevice> = emptyList(),
    // Why the last song could not play, if it could not.
    val problem: PlayProblem? = null,
    // Waiting for sound (the network, or a song opening) while playing.
    val buffering: Boolean = false,
    // Pauses once the song playing ends, a single time.
    val stopAfterCurrent: Boolean = false,
    // How fast the music plays, 1 for as recorded. Lyrics follow it.
    val speed: Float = 1f,
    // The device sound goes to now; while following the system's default,
    // `output` is the default and this says which device that is.
    val playingOn: OutputDevice? = null,
    // The current song's format and the device's, for the player bar's
    // label and the Info panel (see formatLabel and outputSentence).
    val format: PlayFormat? = null,
    // The sleep timer's fade on top of the volume, from 1 (none) to 0.
    val fade: Float = 1f,
    // How many songs have played to their end by themselves since the
    // player started (not skipped), for the sleep timer to count.
    val ended: Int = 0,
)

// Why a song could not play: in plain words for the listener, the song if
// it was one song's fault, and the engine's own words for the details.
data class PlayProblem(val words: String, val song: Song? = null, val detail: String? = null)

// A queue put aside to come back to: the songs in queue order, the queue
// positions in the order they play, which one is current and how far into
// it, and the shuffle and repeat it had.
data class SavedQueue(
    val songs: List<Song>,
    val order: List<Int>,
    val index: Int,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
)

// What the desktop app needs from whatever plays its music. The screens
// only ever talk to this. EnginePlayer (audio/) plays through the Rust
// audio engine; SilentPlayer keeps time and the queue with no sound, for
// tests and machines where the engine cannot load. Both pass the same
// contract tests (DesktopPlayerContract).
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

    // Plays to one of `outputs`, or follows the system's default with
    // DEFAULT_OUTPUT.
    fun selectOutput(id: String)

    // Pauses at the end of the song playing, once.
    fun setStopAfterCurrent(on: Boolean) {}

    // Turns the sound down by a factor on top of the volume, from 1 (as
    // set) to 0, for the sleep timer's fade. The volume the listener set
    // stays as it is.
    fun setFade(fade: Float)

    // Puts a saved queue back, paused where it was left.
    fun restore(saved: SavedQueue)
}

// The output that follows whatever the system uses.
const val DEFAULT_OUTPUT = "default"

// How far into a song "Previous" restarts it instead of going back.
const val RESTART_AFTER_MS = 3_000L

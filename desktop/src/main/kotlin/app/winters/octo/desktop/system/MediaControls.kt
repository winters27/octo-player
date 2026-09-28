package app.winters.octo.desktop.system

import java.io.File

// What the operating system tells the app: the listener pressed a media
// key or a button in the system's player, or the machine is going to sleep.
sealed interface SystemEvent {
    data object Play : SystemEvent
    data object Pause : SystemEvent
    data object Toggle : SystemEvent
    data object Next : SystemEvent
    data object Previous : SystemEvent
    data object Stop : SystemEvent

    // Go to this place in the song.
    data class SeekTo(val positionMs: Long) : SystemEvent

    // Move by this much from where the song is (MPRIS's Seek).
    data class SeekBy(val offsetMs: Long) : SystemEvent

    // Bring the window forward (MPRIS's Raise).
    data object Raise : SystemEvent

    // Quit Octo (MPRIS's Quit).
    data object Quit : SystemEvent

    // Set the volume, from 0 to 1 (MPRIS's Volume).
    data class SetVolume(val volume: Float) : SystemEvent

    // Play an address: a file:// file or an octo:// link (MPRIS's OpenUri).
    data class OpenUri(val uri: String) : SystemEvent

    data object Sleep : SystemEvent
    data object Wake : SystemEvent
}

// A cover for the system to show: the picture itself, and the same saved as
// a file for systems that want an address.
class CoverArt(val bytes: ByteArray, val file: File)

// The system's own player controls: SMTC on Windows, Now Playing on macOS,
// MPRIS on Linux. The app shows its song through this and hears the
// system's buttons back. Every call returns at once and never throws.
interface SystemMediaControls : AutoCloseable {
    // Which system integration this is, in a few words, for the settings.
    val label: String

    // Starts listening. False when this system cannot, which leaves Octo
    // working with no media keys.
    fun start(events: (SystemEvent) -> Unit): Boolean

    // A new song, with its cover if it has one.
    fun showTrack(now: NowPlaying, art: CoverArt?)

    // Playing or paused, and where. `jumped` says the listener moved in the
    // song, which some systems announce.
    fun showPlayback(now: NowPlaying, positionMs: Long, jumped: Boolean)

    // Nothing in the queue.
    fun clear()

    // The volume, for systems that show it (MPRIS).
    fun showVolume(volume: Float) {}
}

// For when there are no media controls to be had.
class NoMediaControls(override val label: String = "Not available") : SystemMediaControls {
    override fun start(events: (SystemEvent) -> Unit) = false
    override fun showTrack(now: NowPlaying, art: CoverArt?) {}
    override fun showPlayback(now: NowPlaying, positionMs: Long, jumped: Boolean) {}
    override fun clear() {}
    override fun close() {}
}

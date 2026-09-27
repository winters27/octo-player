package app.winters.octo.output

import kotlinx.coroutines.flow.StateFlow

// The kinds of device music can be sent to.
enum class OutputFamily {
    // Chromecasts, Google TVs, Nest speakers and speaker groups.
    Cast,

    // Media renderers: smart TVs, AV receivers and hi-fi streamers.
    Renderer,
}

// What a device is, for its icon.
enum class DeviceShape { Tv, Speaker, Group }

// A device the phone found that music can play on.
data class OutputDevice(
    val id: String,
    val name: String,
    val family: OutputFamily,
    val shape: DeviceShape,
    // A short line under the name, such as "Group" or the maker and model.
    val detail: String? = null,
)

// A song as a device gets it: where to fetch it, what it is, and what to
// show while it plays.
data class RemoteMedia(
    val url: String,
    val mimeType: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val coverUrl: String? = null,
    val durationMs: Long? = null,
    // Whether the device can jump around in it.
    val seekable: Boolean = true,
    // A radio station, which never ends.
    val live: Boolean = false,
)

enum class RemoteState {
    // Nothing loaded, or stopped from somewhere else.
    Idle,

    // Loading or buffering.
    Loading,
    Playing,
    Paused,

    // The song played to its end, and the device has nothing after it.
    Ended,
}

// What a device says it is doing. Anything it did not say is null.
data class RemoteStatus(
    val state: RemoteState,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    // The address of what it is playing, which tells the song it moved on
    // to by itself from the one it was given.
    val url: String? = null,
)

// A device music is playing on, driven by the phone: the phone keeps the
// queue, and hands the device one song at a time, plus the next one ahead
// of time when the device takes it, so songs follow on with little gap.
interface RemoteOutput {
    val device: OutputDevice

    // Whether the device plays files of this type.
    fun accepts(mimeType: String): Boolean

    // Whether the device takes the next song ahead of time.
    val takesNext: Boolean

    fun load(media: RemoteMedia, startMs: Long, play: Boolean, next: RemoteMedia?)
    fun setNext(next: RemoteMedia?)
    fun play()
    fun pause()
    fun seek(positionMs: Long)
    fun stop()

    // The device's own volume, from 0 to 1.
    fun setVolume(level: Float)
    fun setMuted(muted: Boolean)

    // Whom to tell what the device is doing, on the main thread.
    fun listen(listener: Listener?)

    // Lets go of the device. `stopPlaying` stops the music on it too.
    fun close(stopPlaying: Boolean)

    interface Listener {
        fun onStatus(status: RemoteStatus)
        fun onVolume(level: Float, muted: Boolean)

        // The device could not play what it was given.
        fun onFailed(message: String)
    }
}

// One kind of device: finding them, and connecting to one.
interface OutputFinder {
    val family: OutputFamily

    // What has been found, for as long as it is looking.
    val devices: StateFlow<List<OutputDevice>>

    fun startLooking()
    fun stopLooking()

    // Connects; the answer comes back through the events.
    fun connect(device: OutputDevice)

    // Called once, by the one place that keeps track of casting.
    fun setEvents(events: Events)

    interface Events {
        fun connected(output: RemoteOutput)
        fun failed(device: OutputDevice)

        // The connection ended from the device's side: stopped from
        // somewhere else, or lost (`lost`).
        fun ended(output: RemoteOutput, lost: Boolean)
    }
}

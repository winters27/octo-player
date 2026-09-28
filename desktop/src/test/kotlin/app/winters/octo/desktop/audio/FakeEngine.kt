package app.winters.octo.desktop.audio

import app.winters.octo.audio.DspSettings
import app.winters.octo.audio.EngineEvent
import app.winters.octo.audio.EqSettings
import app.winters.octo.audio.PlaybackState
import app.winters.octo.audio.QueueItem
import app.winters.octo.audio.ReplayGainSettings
import app.winters.octo.audio.OutputDevice as EngineDevice
import app.winters.octo.audio.RepeatMode as EngineRepeat

// A pretend engine for the player's own tests: it keeps its queue the way
// the Rust engine does (a flat list and an index), writes down every call,
// and sends the events a test asks it to.
class FakeEngine : AudioEngine {
    private var listener: (EngineEvent) -> Unit = {}
    val calls = mutableListOf<String>()
    var queue: List<QueueItem> = emptyList()
    var index = -1
    var heard = Heard(null, 0.0, null)
    var engineState = PlaybackState.IDLE
    var level = -1f
    var device: String? = "unset"
    var lastEq: EqSettings? = null
    var lastReplayGain: ReplayGainSettings? = null
    var lastDsp: DspSettings? = null
    var fadeMs = -1
    var speed = 1f to 1f
    var listed = listOf(EngineDevice("spk", "Speakers", true), EngineDevice("usb", "USB DAC", false))
    var current: EngineDevice? = null
    var pins: Map<String, String>? = null

    val ids: List<String> get() = queue.map { it.id }

    fun emit(event: EngineEvent) = listener(event)

    override fun setListener(listener: (EngineEvent) -> Unit) {
        this.listener = listener
    }

    override fun load(items: List<QueueItem>, startIndex: Int, startMs: Long, play: Boolean) {
        queue = items
        index = if (items.isEmpty()) -1 else startIndex
        calls += "load ${items.size} at $startIndex from $startMs ${if (play) "playing" else "paused"}"
    }

    // Like the engine, finds the song it is on in the new list by its id.
    override fun replaceQueue(items: List<QueueItem>, current: Int) {
        val on = queue.getOrNull(index)?.id
        queue = items
        index = items.indexOfFirst { it.id == on }.takeIf { it >= 0 } ?: current
        calls += "queue ${items.size} at $current"
    }

    override fun skipTo(index: Int) {
        this.index = index
        calls += "skip $index"
    }

    override fun play() {
        calls += "play"
    }

    override fun pause() {
        calls += "pause"
    }

    override fun stop() {
        calls += "stop"
    }

    override fun seek(positionMs: Long) {
        calls += "seek $positionMs"
    }

    override fun setVolume(linear: Float) {
        level = linear
    }

    override fun setRepeat(mode: EngineRepeat) {
        calls += "repeat $mode"
    }

    override fun setStopAfterCurrent(on: Boolean) {
        calls += "stop after $on"
    }

    override fun setCrossfade(ms: Int) {
        fadeMs = ms
    }

    override fun setEq(eq: EqSettings) {
        lastEq = eq
    }

    override fun setReplayGain(settings: ReplayGainSettings) {
        lastReplayGain = settings
    }

    override fun setDsp(dsp: DspSettings) {
        lastDsp = dsp
    }

    override fun setSpeed(speed: Float, pitch: Float) {
        this.speed = speed to pitch
    }

    override fun setOutputDevice(id: String?) {
        device = id
    }

    override fun devices(): List<EngineDevice> = listed

    override fun currentDevice(): EngineDevice? = current

    override fun heard(): Heard = heard

    override fun state(): PlaybackState = engineState

    override fun setPositionInterval(ms: Int) {}

    override fun setTrustedCertificates(pins: Map<String, String>) {
        this.pins = pins
        calls += "trust ${pins.size}"
    }

    override fun close() {}
}

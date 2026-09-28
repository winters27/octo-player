package app.winters.octo.desktop.audio

import app.winters.octo.audio.DspSettings
import app.winters.octo.audio.Engine
import app.winters.octo.audio.EngineEvent
import app.winters.octo.audio.EngineListener
import app.winters.octo.audio.EqSettings
import app.winters.octo.audio.PlaybackState
import app.winters.octo.audio.QueueItem
import app.winters.octo.audio.ReplayGainSettings
import app.winters.octo.audio.TrustedCertificate
import app.winters.octo.audio.OutputDevice as EngineDevice
import app.winters.octo.audio.RepeatMode as EngineRepeat

// Where the engine's clock says the listener is: which queue item, how far
// in (with a fraction, for word-by-word lyrics), and its length when known.
data class Heard(val itemId: String?, val positionMs: Double, val durationMs: Long?)

// The calls the desktop player makes on the audio engine. The real one is
// the Rust engine through its bindings; the tests give a pretend one that
// writes down what it was asked, so the player's side can be checked
// without a sound device or the native library.
interface AudioEngine : AutoCloseable {
    fun setListener(listener: (EngineEvent) -> Unit)

    fun load(items: List<QueueItem>, startIndex: Int, startMs: Long, play: Boolean)

    // Replaces the whole queue around the song the engine is playing, which
    // carries on. The engine finds that song in `items` by its id, and uses
    // `current` only when it is not there.
    fun replaceQueue(items: List<QueueItem>, current: Int)

    fun skipTo(index: Int)

    fun play()

    fun pause()

    fun stop()

    fun seek(positionMs: Long)

    fun setVolume(linear: Float)

    fun setRepeat(mode: EngineRepeat)

    fun setStopAfterCurrent(on: Boolean)

    fun setCrossfade(ms: Int)

    fun setEq(eq: EqSettings)

    fun setReplayGain(settings: ReplayGainSettings)

    fun setDsp(dsp: DspSettings)

    fun setSpeed(speed: Float, pitch: Float)

    // Plays to one device, or follows the system's default with null.
    fun setOutputDevice(id: String?)

    fun devices(): List<EngineDevice>

    fun currentDevice(): EngineDevice?

    fun heard(): Heard

    fun state(): PlaybackState

    // Sends a Position event this often while playing; 0 for none.
    fun setPositionInterval(ms: Int)

    // The certificates the listener trusted, as SHA-256 fingerprints by
    // host, so songs stream from the servers the app itself may reach.
    // Replaces the ones given before.
    fun setTrustedCertificates(pins: Map<String, String>)
}

// The Rust engine behind the interface. Every call returns at once; the
// engine does the work on its own threads.
class NativeAudioEngine(private val engine: Engine) : AudioEngine {
    // Once closed, calls do nothing and questions get the last answer: the
    // window can draw a frame, and the media controls hear a last change,
    // after the engine is gone on the way out.
    @Volatile
    private var closed = false

    @Volatile
    private var lastHeard = Heard(null, 0.0, null)

    private inline fun <T> ifOpen(otherwise: T, call: () -> T): T {
        if (closed) return otherwise
        return try {
            call()
        } catch (e: IllegalStateException) {
            // Closed on another thread while this call was on its way.
            if (closed) otherwise else throw e
        }
    }

    override fun setListener(listener: (EngineEvent) -> Unit) {
        engine.setListener(
            object : EngineListener {
                override fun onEvent(event: EngineEvent) = listener(event)
            },
        )
    }

    override fun load(items: List<QueueItem>, startIndex: Int, startMs: Long, play: Boolean) = ifOpen(Unit) { engine.load(items, startIndex.coerceAtLeast(0).toUInt(), startMs.coerceAtLeast(0).toULong(), play) }

    override fun replaceQueue(items: List<QueueItem>, current: Int) = ifOpen(Unit) { engine.replaceQueue(items, current.coerceAtLeast(0).toUInt()) }

    override fun skipTo(index: Int) = ifOpen(Unit) { engine.skipTo(index.coerceAtLeast(0).toUInt()) }

    override fun play() = ifOpen(Unit) { engine.play() }

    override fun pause() = ifOpen(Unit) { engine.pause() }

    override fun stop() = ifOpen(Unit) { engine.stop() }

    override fun seek(positionMs: Long) = ifOpen(Unit) { engine.seek(positionMs.coerceAtLeast(0).toULong()) }

    override fun setVolume(linear: Float) = ifOpen(Unit) { engine.setVolume(linear) }

    override fun setRepeat(mode: EngineRepeat) = ifOpen(Unit) { engine.setRepeat(mode) }

    override fun setStopAfterCurrent(on: Boolean) = ifOpen(Unit) { engine.setStopAfterCurrent(on) }

    override fun setCrossfade(ms: Int) = ifOpen(Unit) { engine.setCrossfade(ms.coerceAtLeast(0).toUInt()) }

    override fun setEq(eq: EqSettings) = ifOpen(Unit) { engine.setEq(eq) }

    override fun setReplayGain(settings: ReplayGainSettings) = ifOpen(Unit) { engine.setReplaygain(settings) }

    override fun setDsp(dsp: DspSettings) = ifOpen(Unit) { engine.setDsp(dsp) }

    override fun setSpeed(speed: Float, pitch: Float) = ifOpen(Unit) { engine.setSpeed(speed, pitch) }

    override fun setOutputDevice(id: String?) = ifOpen(Unit) { engine.setOutputDevice(id) }

    override fun devices(): List<EngineDevice> = ifOpen(emptyList()) { engine.devices() }

    override fun currentDevice(): EngineDevice? = ifOpen(null) { engine.currentDevice() }

    override fun heard(): Heard = ifOpen(lastHeard) {
        val p = engine.position()
        Heard(p.itemId, p.positionMs, p.durationMs?.toLong()).also { lastHeard = it }
    }

    override fun state(): PlaybackState = ifOpen(PlaybackState.IDLE) { engine.state() }

    override fun setPositionInterval(ms: Int) = ifOpen(Unit) { engine.setPositionInterval(ms.coerceAtLeast(0).toUInt()) }

    override fun setTrustedCertificates(pins: Map<String, String>) = ifOpen(Unit) {
        engine.setTrustedCertificates(pins.map { (host, fingerprint) -> TrustedCertificate(host, fingerprint) })
    }

    override fun close() {
        if (closed) return
        closed = true
        engine.shutdown()
        engine.close()
    }

    companion object {
        // The engine playing to the system's devices, or a silent one for
        // tests and machines with no sound.
        fun open(silent: Boolean = false): NativeAudioEngine = NativeAudioEngine(if (silent) Engine.newSilent() else Engine())
    }
}

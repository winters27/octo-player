package app.winters.octo.desktop.audio

import app.winters.octo.audio.DspSettings
import app.winters.octo.audio.Engine
import app.winters.octo.audio.EngineEvent
import app.winters.octo.audio.EngineListener
import app.winters.octo.audio.EqSettings
import app.winters.octo.audio.PlaybackState
import app.winters.octo.audio.QueueItem
import app.winters.octo.audio.ReplayGainSettings
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

    // Replaces everything after the song the engine is playing.
    fun replaceUpcoming(items: List<QueueItem>)

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
}

// The Rust engine behind the interface. Every call returns at once; the
// engine does the work on its own threads.
class NativeAudioEngine(private val engine: Engine) : AudioEngine {
    override fun setListener(listener: (EngineEvent) -> Unit) {
        engine.setListener(
            object : EngineListener {
                override fun onEvent(event: EngineEvent) = listener(event)
            },
        )
    }

    override fun load(items: List<QueueItem>, startIndex: Int, startMs: Long, play: Boolean) =
        engine.load(items, startIndex.coerceAtLeast(0).toUInt(), startMs.coerceAtLeast(0).toULong(), play)

    override fun replaceUpcoming(items: List<QueueItem>) = engine.replaceUpcoming(items)

    override fun skipTo(index: Int) = engine.skipTo(index.coerceAtLeast(0).toUInt())

    override fun play() = engine.play()

    override fun pause() = engine.pause()

    override fun stop() = engine.stop()

    override fun seek(positionMs: Long) = engine.seek(positionMs.coerceAtLeast(0).toULong())

    override fun setVolume(linear: Float) = engine.setVolume(linear)

    override fun setRepeat(mode: EngineRepeat) = engine.setRepeat(mode)

    override fun setStopAfterCurrent(on: Boolean) = engine.setStopAfterCurrent(on)

    override fun setCrossfade(ms: Int) = engine.setCrossfade(ms.coerceAtLeast(0).toUInt())

    override fun setEq(eq: EqSettings) = engine.setEq(eq)

    override fun setReplayGain(settings: ReplayGainSettings) = engine.setReplaygain(settings)

    override fun setDsp(dsp: DspSettings) = engine.setDsp(dsp)

    override fun setSpeed(speed: Float, pitch: Float) = engine.setSpeed(speed, pitch)

    override fun setOutputDevice(id: String?) = engine.setOutputDevice(id)

    override fun devices(): List<EngineDevice> = engine.devices()

    override fun currentDevice(): EngineDevice? = engine.currentDevice()

    override fun heard(): Heard {
        val p = engine.position()
        return Heard(p.itemId, p.positionMs, p.durationMs?.toLong())
    }

    override fun state(): PlaybackState = engine.state()

    override fun setPositionInterval(ms: Int) = engine.setPositionInterval(ms.coerceAtLeast(0).toUInt())

    override fun close() {
        engine.shutdown()
        engine.close()
    }

    companion object {
        // The engine playing to the system's devices, or a silent one for
        // tests and machines with no sound.
        fun open(silent: Boolean = false): NativeAudioEngine = NativeAudioEngine(if (silent) Engine.newSilent() else Engine())
    }
}

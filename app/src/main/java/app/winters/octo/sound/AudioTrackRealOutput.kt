package app.winters.octo.sound

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatConfig
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

// The phone's real output: Media3's AudioTrackAudioOutput, float stereo at
// the mix rate. Built on the builder thread, used only from the mix thread,
// which adds and removes no Media3 listener on it.
@UnstableApi
class AudioTrackRealOutput(private val out: AudioTrackAudioOutput) : RealOutput {
    private var bytes = ByteBuffer.allocateDirect(4_096 * MIX_FRAME_BYTES).order(ByteOrder.nativeOrder())
    private val stamp = AudioTimestamp()
    private var routing: AudioRouting.OnRoutingChangedListener? = null

    val track: AudioTrack get() = out.audioTrack

    // The buffer as built: the track's size in use changes with
    // setBufferSize on API 29 and 30.
    override val bufferFrames: Int = out.bufferSizeInFrames.toInt()

    override fun write(samples: FloatArray, offset: Int, frames: Int): Int {
        val need = frames * MIX_FRAME_BYTES
        if (bytes.capacity() < need) bytes = ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder())
        bytes.clear()
        bytes.asFloatBuffer().put(samples, offset * 2, frames * 2)
        bytes.limit(need)
        try {
            out.write(bytes, 1, 0)
        } catch (e: AudioOutput.WriteException) {
            if (!e.isRecoverable || e.errorCode == AudioTrack.ERROR_DEAD_OBJECT) throw RealOutputDead(e.errorCode)
        }
        return bytes.position() / MIX_FRAME_BYTES
    }

    override fun play() = out.play()

    override fun pause() = out.pause()

    override fun release() {
        routing?.let { track.removeOnRoutingChangedListener(it) }
        routing = null
        out.release()
    }

    override fun head(): Long = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    override fun timestamp(into: RealTimestamp): Boolean {
        val ok = try {
            track.getTimestamp(stamp)
        } catch (e: IllegalStateException) {
            false
        }
        if (ok) {
            into.frame = stamp.framePosition
            into.nanos = stamp.nanoTime
        }
        return ok
    }

    override fun underruns(): Int = track.underrunCount

    override fun setVolume(volume: Float) = out.setVolume(volume)

    override fun setStartThreshold(frames: Int): Int =
        if (android.os.Build.VERSION.SDK_INT >= 31) track.setStartThresholdInFrames(max(1, frames)) else -1

    override fun setBufferSize(frames: Int): Int = track.setBufferSizeInFrames(max(1, frames))

    override fun isStalled(): Boolean = out.isStalled

    override fun routedDevice(): String? = track.routedDevice?.let { "${it.type}:${it.address}:${it.productName}" }

    override fun listenForRoutes(handler: Handler, onRouted: () -> Unit) {
        val l = AudioRouting.OnRoutingChangedListener { onRouted() }
        routing = l
        track.addOnRoutingChangedListener(l, handler)
    }

    override fun setPreferredDevice(device: AudioDeviceInfo?) = out.setPreferredDevice(device)

    override fun attachAuxEffect(effectId: Int) = out.attachAuxEffect(effectId)

    override fun setAuxEffectSendLevel(level: Float) = out.setAuxEffectSendLevel(level)
}

// Builds AudioTrackRealOutputs with its own provider, made on the builder
// thread with no context: no capability receiver, no routing listener and
// no looper check to throw on the mix thread; its default capabilities
// cover float stereo PCM. Within one attempt the buffer is halved only
// while above the larger of one second of audio and the minimum, as
// Media3's sink does, so its 500 ms target is never halved.
@UnstableApi
class AudioTrackOutputBuilder(
    private val mixRate: Int,
    private val attributes: AudioAttributes,
    private val sessionId: Int,
) : RealOutputBuilder {
    private var provider: AudioTrackAudioOutputProvider? = null

    private fun provider(): AudioTrackAudioOutputProvider =
        provider ?: AudioTrackAudioOutputProvider.Builder(null).build().also {
            it.setClock(Clock.DEFAULT)
            provider = it
        }

    override fun build(bufferFrames: Int): RealOutput {
        val p = provider()
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .setSampleRate(mixRate)
            .setChannelCount(2)
            .build()
        val config = p.getOutputConfig(
            FormatConfig.Builder(format).setAudioAttributes(attributes).setAudioSessionId(sessionId).setEnableHighResolutionPcmOutput(true).build(),
        )
        val floor = max(mixRate, minimumFrames()) * MIX_FRAME_BYTES
        var size = bufferFrames * MIX_FRAME_BYTES
        while (true) {
            try {
                return AudioTrackRealOutput(p.getAudioOutput(config.buildUpon().setBufferSize(size).build()))
            } catch (e: AudioOutputProvider.InitializationException) {
                if (size <= floor) throw e
                size = max(floor, size / 2 / MIX_FRAME_BYTES * MIX_FRAME_BYTES)
            }
        }
    }

    override fun hasPendingReleases(): Boolean = provider?.hasPendingReleases() == true

    override fun minimumFrames(): Int =
        AudioTrack.getMinBufferSize(mixRate, android.media.AudioFormat.CHANNEL_OUT_STEREO, android.media.AudioFormat.ENCODING_PCM_FLOAT) / MIX_FRAME_BYTES

    override fun release() {
        provider?.release()
        provider = null
    }
}

// The phone's mixer: its mix thread at audio priority, the builder thread
// every real output is built on, the duck gate's reader thread, and a
// device callback that follows outputs coming and going.
@UnstableApi
object DeckMixers {
    fun create(
        context: Context,
        attributes: AudioAttributes,
        sessionId: Int,
        listener: MixListener,
        tuning: MixTuning = MixTuning(),
        clock: Clock = Clock.DEFAULT,
    ): DeckMixer {
        val audio = context.getSystemService(AudioManager::class.java)
        val rate = audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48_000
        val mix = HandlerThread("octo-mix", Process.THREAD_PRIORITY_URGENT_AUDIO).also { it.start() }
        val build = HandlerThread("octo-mix-build", Process.THREAD_PRIORITY_AUDIO).also { it.start() }
        val gate = HandlerThread("octo-mix-gate", Process.THREAD_PRIORITY_AUDIO).also { it.start() }
        val main = Handler(Looper.getMainLooper())
        lateinit var mixer: DeckMixer
        val devices = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = mixer.devicesChanged()

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = mixer.devicesChanged()
        }
        mixer = DeckMixer(
            mixRate = rate,
            sessionId = sessionId,
            loop = HandlerMixLoop(clock, mix.looper),
            builderLoop = HandlerMixLoop(clock, build.looper),
            gateLoop = HandlerMixLoop(clock, gate.looper),
            builder = AudioTrackOutputBuilder(rate, attributes, sessionId),
            playbackList = SystemPlaybackList(audio, attributes.audioAttributesV21.audioAttributes),
            toMain = { main.post(it) },
            listener = listener,
            tuning = tuning,
            onReleased = {
                // Each thread runs what it already holds, then ends.
                audio.unregisterAudioDeviceCallback(devices)
                gate.quitSafely()
                mix.quitSafely()
                build.quitSafely()
            },
        )
        audio.registerAudioDeviceCallback(devices, Handler(mix.looper))
        return mixer
    }
}

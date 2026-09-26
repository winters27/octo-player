package app.winters.octo.sound

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.math.roundToInt

// How long a change of level, balance or curve takes, so it never clicks.
const val GLIDE_MS = 30f

// Whether these settings ask for any shaping at all. While they do, the
// output stays in one format from song to song, so songs still run into
// each other without a gap.
fun SoundSettings.shapesSound(): Boolean =
    eqEnabled || replayGain != ReplayGainMode.Off || limiter || balance != 0f || mono

// A value that glides to a new setting over a number of frames.
internal class Glide(initial: Float) {
    var value = initial
        private set
    private var target = initial
    private var step = 0f
    private var left = 0

    val moving: Boolean get() = left > 0

    fun to(next: Float, frames: Int) {
        target = next
        if (frames <= 0 || next == value) {
            value = next
            left = 0
        } else {
            step = (next - value) / frames
            left = frames
        }
    }

    fun next(): Float {
        if (left > 0) {
            left--
            value = if (left == 0) target else value + step
        }
        return value
    }
}

// The sound shaping itself, for one stream, apart from the player so it can
// be tested alone. In order: the level (ReplayGain and the equalizer's
// preamp), the equalizer's filters, mono and balance, then the limiter.
// Samples are floats, interleaved, full scale at 1.
class SoundShaper(val sampleRate: Int, val channels: Int) {
    private val glideFrames = (sampleRate * GLIDE_MS / 1_000f).roundToInt().coerceAtLeast(1)
    private val gain = Glide(1f)
    private val left = Glide(1f)
    private val right = Glide(1f)
    private val mono = Glide(0f)

    private var bank = FilterBank(channels, emptyList())

    // While the curve changes, the old filters keep running and fade out.
    private var fadingFrom: FilterBank? = null
    private var fadeLeft = 0
    private var fadeScratch = FloatArray(0)

    private val limiter = Limiter(channels, sampleRate)
    private var started = false

    // How many frames late the sound comes out.
    val latency: Int get() = limiter.latency

    // Takes up new settings or a new song. Changes glide in, unless
    // `instant`, as at the very start of a song.
    fun apply(settings: SoundSettings, song: SongLoudness?, instant: Boolean) {
        val now = instant || !started
        started = true
        val frames = if (now) 0 else glideFrames

        val bands = bandCoefficients(settings.activeFilters(), sampleRate)
        if (bands != bank.coefficients) {
            val next = FilterBank(channels, bands)
            next.continueFrom(bank)
            fadingFrom = if (now) null else bank
            fadeLeft = if (now) 0 else glideFrames
            bank = next
        }

        val preamp = 10f.pow(settings.effectivePreampDb(sampleRate) / 20f)
        val level = replayGainFactor(settings, song) * preamp
        gain.to(level, frames)

        val stereo = channels >= 2
        val balance = if (stereo) settings.balance.coerceIn(-1f, 1f) else 0f
        left.to(if (balance > 0f) 1f - balance else 1f, frames)
        right.to(if (balance < 0f) 1f + balance else 1f, frames)
        val toMono = stereo && settings.mono
        mono.to(if (toMono) 1f else 0f, frames)

        // Sound that is left as it is passes through untouched; the limiter
        // only catches peaks the shaping could push over.
        val untouched = bands.isEmpty() && level == 1f && balance == 0f && !toMono
        limiter.enabled = settings.limiter && !untouched
    }

    // Shapes `frames` frames of `samples` (changed in place) and writes what
    // comes out of the limiter to `output`. Returns the frames written.
    fun process(samples: FloatArray, frames: Int, output: FloatArray): Int {
        applyGain(samples, frames)
        applyFilters(samples, frames)
        applyMix(samples, frames)
        return limiter.process(samples, frames, output)
    }

    // Hands out the sound still held back, at the end of a stream. `output`
    // needs room for `latency` frames.
    fun drain(output: FloatArray): Int = limiter.drain(output)

    // Forgets the sound held back, after a jump to another place.
    fun clearHeld() = limiter.clear()

    private fun applyGain(samples: FloatArray, frames: Int) {
        if (!gain.moving && gain.value == 1f) return
        var i = 0
        for (frame in 0 until frames) {
            val g = gain.next()
            for (ch in 0 until channels) {
                samples[i] *= g
                i++
            }
        }
    }

    private fun applyFilters(samples: FloatArray, frames: Int) {
        val old = fadingFrom
        if (old == null) {
            bank.process(samples, frames)
            return
        }
        val count = frames * channels
        if (fadeScratch.size < count) fadeScratch = FloatArray(count)
        samples.copyInto(fadeScratch, 0, 0, count)
        old.process(fadeScratch, frames)
        bank.process(samples, frames)
        var i = 0
        for (frame in 0 until frames) {
            val w = if (fadeLeft > 0) 1f - fadeLeft.toFloat() / glideFrames else 1f
            if (fadeLeft > 0) fadeLeft--
            for (ch in 0 until channels) {
                samples[i] = fadeScratch[i] + (samples[i] - fadeScratch[i]) * w
                i++
            }
        }
        if (fadeLeft == 0) fadingFrom = null
    }

    private fun applyMix(samples: FloatArray, frames: Int) {
        if (channels < 2) return
        val still = !mono.moving && !left.moving && !right.moving
        if (still && mono.value == 0f && left.value == 1f && right.value == 1f) return
        var i = 0
        for (frame in 0 until frames) {
            val m = mono.next()
            if (m != 0f) {
                var sum = 0f
                for (ch in 0 until channels) sum += samples[i + ch]
                val average = sum / channels
                for (ch in 0 until channels) samples[i + ch] += (average - samples[i + ch]) * m
            }
            samples[i] *= left.next()
            samples[i + 1] *= right.next()
            i += channels
        }
    }
}

// Octo's own sound shaping, as a step in the player's audio path. It takes
// 16-bit sound and gives floats, so boosts have room above full scale until
// the limiter brings them back. Each deck has its own, all following the
// same settings. A song's ReplayGain is handed over when the song is set up
// and taken up only once the song before it has played out, so the level
// changes exactly between the two songs.
@UnstableApi
class OctoDspProcessor(private val settings: () -> SoundSettings) : BaseAudioProcessor() {
    private var pendingSong: SongLoudness? = null
    private var song: SongLoudness? = null
    private var shaper: SoundShaper? = null

    // The settings last taken up. The settings flow hands out a new object
    // on each change, so comparing the object is enough to see a change.
    private var seen: SoundSettings? = null

    private var shorts = ShortArray(0)
    private var input = FloatArray(0)
    private var output = FloatArray(0)

    // The loudness of the song that plays after the current one.
    fun setNextSong(next: SongLoudness) {
        pendingSong = next
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) return AudioFormat.NOT_SET
        if (!settings().shapesSound()) return AudioFormat.NOT_SET
        return AudioFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, C.ENCODING_PCM_FLOAT)
    }

    // Runs after the song before has played out, and after a seek.
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val newSong = pendingSong
        if (newSong != null) {
            song = newSong
            pendingSong = null
        }
        if (!isActive) return
        val format = inputAudioFormat
        val kept = shaper?.takeIf { it.sampleRate == format.sampleRate && it.channels == format.channelCount }
        val current = kept ?: SoundShaper(format.sampleRate, format.channelCount).also { shaper = it }
        current.clearHeld()
        val now = settings()
        seen = now
        current.apply(now, song, instant = kept == null || newSong != null)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val current = shaper ?: SoundShaper(format.sampleRate, format.channelCount).also { shaper = it }
        val now = settings()
        if (now !== seen) {
            seen = now
            current.apply(now, song, instant = false)
        }
        val frames = inputBuffer.remaining() / format.bytesPerFrame
        if (frames == 0) return
        val count = frames * format.channelCount
        if (input.size < count) input = FloatArray(count)
        if (output.size < count) output = FloatArray(count)
        if (format.encoding == C.ENCODING_PCM_16BIT) {
            if (shorts.size < count) shorts = ShortArray(count)
            inputBuffer.asShortBuffer().get(shorts, 0, count)
            for (i in 0 until count) input[i] = shorts[i] / 32_768f
        } else {
            inputBuffer.asFloatBuffer().get(input, 0, count)
        }
        inputBuffer.position(inputBuffer.position() + frames * format.bytesPerFrame)
        emit(current.process(input, frames, output) * format.channelCount)
    }

    override fun onQueueEndOfStream() {
        val current = shaper ?: return
        val room = current.latency * current.channels
        if (output.size < room) output = FloatArray(room)
        emit(current.drain(output) * current.channels)
    }

    override fun onReset() {
        shaper = null
        song = null
        seen = null
    }

    private fun emit(samples: Int) {
        if (samples == 0) return
        val bytes = samples * Float.SIZE_BYTES
        val buffer = replaceOutputBuffer(bytes)
        buffer.asFloatBuffer().put(output, 0, samples)
        buffer.position(bytes)
        buffer.flip()
    }
}

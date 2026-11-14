package app.winters.octo.sound

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import app.winters.octo.playback.entryId
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

// How long a change of level, balance or curve takes, so it never clicks.
const val GLIDE_MS = 30f

// Whether these settings ask for any shaping at all. While they do, the
// output stays in one format from song to song, so songs still run into
// each other without a gap.
fun SoundSettings.shapesSound(): Boolean =
    eqEnabled || replayGain != ReplayGainMode.Off || limiter || balance != 0f || mono || crossfeed.on

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
// preamp), the equalizer's filters, crossfeed, mono and balance, then the
// limiter.
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

    private val crossfeed = CrossfeedFilter()

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

        // Crossfeed blends the two sides, so it has nothing to do in mono.
        val crossfeedOn = stereo && settings.crossfeed.on && !toMono
        crossfeed.set(crossfeedCoefficients(settings.crossfeed, sampleRate), crossfeedOn, frames)

        // Sound that is left as it is passes through untouched; the limiter
        // only catches peaks the shaping could push over.
        val untouched = bands.isEmpty() && level == 1f && balance == 0f && !toMono && !crossfeedOn
        limiter.enabled = settings.limiter && !untouched
    }

    // Shapes `frames` frames of `samples` (changed in place) and writes what
    // comes out of the limiter to `output`. Returns the frames written.
    fun process(samples: FloatArray, frames: Int, output: FloatArray): Int {
        applyGain(samples, frames)
        applyFilters(samples, frames)
        crossfeed.process(samples, frames, channels)
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

// What the crossfade needs from a deck's audio path: whether its sound
// runs through Octo's processor (so transitions can be shaped there), a
// way to arm a transition, and what the deck has heard of its song, its
// tempo's octave picked by the song's tag tempo when it has one.
interface DeckSound {
    val shapesTransitions: Boolean

    fun arm(transition: DeckTransition?)

    fun reading(entryId: String?, tagBpm: Double? = null): TapReading?

    // The deck's gain in dB under the current settings (ReplayGain and the
    // EQ's preamp) for `song` at `sampleRate`, or, with none, for the song
    // it plays now at its own rate.
    fun replayGainDb(song: SongLoudness? = null, sampleRate: Int = 0): Double

    // The loudness the deck applies, or will once the song before has played
    // out, to queue entry `entryId`, once that song's sound is open; null
    // before then.
    fun loudnessOf(entryId: String?): SongLoudness?

    // The rate of the sound the deck has open, or 0 before any.
    val sampleRate: Int
}

// Octo's own sound shaping, as a step in the player's audio path. It takes
// 16-bit sound and gives floats, so boosts have room above full scale until
// the limiter brings them back. Each deck has its own, all following the
// same settings. A song's ReplayGain is handed over when the song is set up
// and taken up only once the song before it has played out, so the level
// changes exactly between the two songs.
//
// While `keepActive` says so (crossfade is on) it runs even when the
// settings shape nothing, so a transition can start at any moment without
// the output being set up again; the sound then passes through unchanged.
// It keeps the song time of every frame it hands on (from where the player
// says each stream starts, before the speed step changed it, and the
// speed), runs an armed transition on that time, and feeds what the
// listener hears to a LiveTap.
//
// Given `labels`, it is a deck's last step on the way to the mixer: it is
// always active, hands on float stereo at the song's rate whatever the
// song's channels (mono doubled, more than two mixed down), labels every
// stretch it hands on with its song time and stream, and leaves blends to
// the mixer.
@UnstableApi
class OctoDspProcessor(
    private val keepActive: () -> Boolean = { false },
    private val tap: LiveTapFeed? = null,
    private val entryOf: (AudioProcessor.StreamMetadata) -> String? = ::entryOfStream,
    private val labels: DeckTimeQueue? = null,
    private val settings: () -> SoundSettings,
) : BaseAudioProcessor(), DeckSound {
    // Whether this step feeds the mixer.
    val mixPath: Boolean get() = labels != null

    private var times: ChainTimes? = null
    private var stereo = FloatArray(0)
    private var downmix: FloatArray? = null
    private var songEndMs = Double.NaN
    private var live = false
    private var streamSpeed = 1.0
    private var streamStarted = false
    // The next song's loudness and queue entry, and the current song's,
    // with the rates their sounds are at; read on main too, for a plan's
    // gain between two songs.
    @Volatile private var pendingSong: SongLoudness? = null
    @Volatile private var pendingEntry: String? = null
    @Volatile private var song: SongLoudness? = null
    @Volatile private var configuredRate = 0
    @Volatile private var songRate = 0
    private var shaper: SoundShaper? = null
    private var transitions: TransitionShaper? = null

    // The settings last taken up. The settings flow hands out a new object
    // on each change, so comparing the object is enough to see a change.
    private var seen: SoundSettings? = null

    private var shorts = ShortArray(0)
    private var input = FloatArray(0)
    private var output = FloatArray(0)

    @Volatile private var armed: DeckTransition? = null

    // The queue entry the stream belongs to, where in its song the stream
    // started, and the frames handed on since.
    @Volatile private var entryId: String? = null
    private var streamStartMs = 0.0
    private var framesOut = 0L

    @Volatile private var speed = 1f

    override val shapesTransitions: Boolean get() = isActive

    override fun arm(transition: DeckTransition?) {
        armed = transition
    }

    override fun reading(entryId: String?, tagBpm: Double?): TapReading? = tap?.reading(entryId, tagBpm)

    override fun replayGainDb(song: SongLoudness?, sampleRate: Int): Double {
        val now = settings()
        val rate = sampleRate.takeIf { it > 0 } ?: songRate.takeIf { it > 0 } ?: 48_000
        return 20.0 * log10(replayGainFactor(now, song ?: this.song).toDouble()) + now.effectivePreampDb(rate)
    }

    override fun loudnessOf(entryId: String?): SongLoudness? {
        val pending = pendingSong
        if (pending != null && pendingEntry == entryId) return pending
        return song.takeIf { this.entryId == entryId }
    }

    override val sampleRate: Int get() = configuredRate

    // The playback speed; the stream starts again at it right after.
    fun setSpeed(value: Float) {
        speed = value
    }

    // Where the chain notes each stream's start and skipped silence.
    fun useTimes(chainTimes: ChainTimes) {
        times = chainTimes
    }

    // The loudness of the song that plays after the current one, and its
    // queue entry.
    fun setNextSong(next: SongLoudness, entry: String? = null) {
        pendingEntry = entry
        pendingSong = next
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        configuredRate = inputAudioFormat.sampleRate
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            if (mixPath) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            return AudioFormat.NOT_SET
        }
        if (mixPath) {
            downmix = stereoMatrix(inputAudioFormat.channelCount)
            return AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_FLOAT)
        }
        if (!settings().shapesSound() && !keepActive()) return AudioFormat.NOT_SET
        return AudioFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, C.ENCODING_PCM_FLOAT)
    }

    // Runs after the song before has played out, and after a seek.
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        draining = false
        val newSong = pendingSong
        if (newSong != null) song = newSong
        songRate = inputAudioFormat.sampleRate
        if (!isActive) {
            if (newSong != null && pendingSong === newSong) pendingSong = null
            return
        }
        val format = inputAudioFormat
        val kept = shaper?.takeIf { it.sampleRate == format.sampleRate && it.channels == format.channelCount }
        val current = kept ?: SoundShaper(format.sampleRate, format.channelCount).also { shaper = it }
        current.clearHeld()
        val now = settings()
        seen = now
        current.apply(now, song, instant = kept == null || newSong != null)

        val entry = entryOf(streamMetadata)
        val sameSong = kept != null && entry == entryId
        entryId = entry
        // Cleared only now, so the song's loudness reads the same from main
        // all through the hand-over.
        if (newSong != null && pendingSong === newSong) pendingSong = null
        // The speed step hands later steps the start divided by the speed;
        // the song time is the start it received.
        streamSpeed = speed.toDouble()
        streamStartMs = streamMetadata.positionOffsetUs * streamSpeed / 1_000.0
        framesOut = 0
        if (mixPath) {
            labels?.clear()
            // While skipping is off the skipper is out of the pipeline and
            // never sees this flush, so its old points are dropped here.
            times?.flushed()
            streamStarted = true
            val info = songOfStream(streamMetadata)
            songEndMs = info.first
            live = info.second
        }
        val shaping = transitions
        if (shaping == null || shaping.sampleRate != format.sampleRate || shaping.channels != format.channelCount) {
            transitions = TransitionShaper(format.sampleRate, format.channelCount)
        }
        if (!sameSong) tap?.begin(entry, format.sampleRate, format.channelCount)
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
        // The tap is only for planning blends.
        if (keepActive()) tap?.push(input, count)
        val out = current.process(input, frames, output)
        if (mixPath) {
            emitToMix(out, format.channelCount)
        } else {
            shapeTransition(out)
            emit(out * format.channelCount)
        }
    }

    // The sound the limiter still holds goes out at the end of the stream,
    // but only once what was handed on before it has been taken: the
    // player asks for the end while its last output may still be unwritten,
    // and a new output now would overwrite it.
    private var draining = false

    override fun onQueueEndOfStream() {
        draining = true
    }

    override fun getOutput(): ByteBuffer {
        if (draining && !hasPendingOutput()) {
            draining = false
            drainHeld()
        }
        return super.getOutput()
    }

    override fun isEnded(): Boolean = !draining && super.isEnded()

    private fun drainHeld() {
        val current = shaper ?: return
        val room = current.latency * current.channels
        if (output.size < room) output = FloatArray(room)
        val out = current.drain(output)
        if (mixPath) {
            emitToMix(out, current.channels)
        } else {
            shapeTransition(out)
            emit(out * current.channels)
        }
    }

    override fun onReset() {
        draining = false
        shaper = null
        transitions = null
        song = null
        seen = null
        entryId = null
        labels?.clear()
        tap?.close()
    }

    // Hands `frames` shaped frames of `output` on as stereo, labeled with
    // their song time. A frame's place in the song is its place in the
    // speed step's input (this step's frame times the speed) plus the
    // silence skipped before that place; a label ends wherever more was
    // skipped.
    private fun emitToMix(frames: Int, channels: Int) {
        if (frames == 0) return
        val queue = labels ?: return
        val format = inputAudioFormat
        val rate = format.sampleRate.toDouble()
        val msPerFrame = streamSpeed * 1_000.0 / rate
        var at = 0
        while (at < frames) {
            val k = framesOut + at
            val sonicIn = k * streamSpeed
            val here = floor(sonicIn + 1e-9).toLong()
            val skips = times?.skipsBefore(here) ?: 0L
            val nextPoint = times?.nextPointAfter(here) ?: Long.MAX_VALUE
            val until = if (nextPoint == Long.MAX_VALUE) Long.MAX_VALUE else ceil(nextPoint / streamSpeed - 1e-9).toLong()
            val n = minOf((frames - at).toLong(), maxOf(1L, until - k)).toInt()
            val songMs = streamStartMs + (sonicIn + skips) * 1_000.0 / rate
            queue.push(n, songMs, msPerFrame, entryId, streamStarted, songEndMs, live)
            streamStarted = false
            at += n
        }
        framesOut += frames
        emitStereo(frames, channels)
    }

    private fun emitStereo(frames: Int, channels: Int) {
        val samples = frames * 2
        if (stereo.size < samples) stereo = FloatArray(samples)
        when (channels) {
            1 -> for (i in 0 until frames) {
                stereo[i * 2] = output[i]
                stereo[i * 2 + 1] = output[i]
            }
            2 -> output.copyInto(stereo, 0, 0, samples)
            else -> {
                val m = downmix ?: stereoMatrix(channels)
                for (i in 0 until frames) {
                    var l = 0f
                    var r = 0f
                    val base = i * channels
                    for (ch in 0 until channels) {
                        val x = output[base + ch]
                        l += x * m[ch * 2]
                        r += x * m[ch * 2 + 1]
                    }
                    stereo[i * 2] = l
                    stereo[i * 2 + 1] = r
                }
            }
        }
        val bytes = samples * Float.SIZE_BYTES
        val buffer = replaceOutputBuffer(bytes)
        buffer.asFloatBuffer().put(stereo, 0, samples)
        buffer.position(bytes)
        buffer.flip()
    }

    // Runs the armed transition, when it is for this song, on the frames
    // about to be handed on, and moves the song time past them.
    private fun shapeTransition(frames: Int) {
        if (frames == 0) return
        val format = inputAudioFormat
        val msPerFrame = 1_000.0 * speed / format.sampleRate
        val songMs = streamStartMs + framesOut * msPerFrame
        framesOut += frames
        val shaping = transitions ?: TransitionShaper(format.sampleRate, format.channelCount).also { transitions = it }
        // A stream whose song is not known takes the transition too.
        val transition = armed?.takeIf { it.entryId == null || entryId == null || it.entryId == entryId }
        shaping.arm(transition, songMs)
        shaping.process(output, frames, songMs, msPerFrame)
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

// Each input channel's share of left and right, as [ch * 2] and
// [ch * 2 + 1]: Media3's standard constant-power mix down to stereo, or
// for a layout it has none for, odd channels to the left and even to the
// right, scaled to keep the level.
@UnstableApi
internal fun stereoMatrix(channels: Int): FloatArray {
    val out = FloatArray(channels * 2)
    if (channels <= 2) {
        out[0] = 1f
        if (channels == 2) out[3] = 1f else out[1] = 1f
        return out
    }
    try {
        val m = ChannelMixingMatrix.createForConstantPower(channels, 2)
        for (ch in 0 until channels) {
            out[ch * 2] = m.getMixingCoefficient(ch, 0)
            out[ch * 2 + 1] = m.getMixingCoefficient(ch, 1)
        }
    } catch (e: UnsupportedOperationException) {
        val share = 1f / sqrt((channels + 1) / 2f)
        for (ch in 0 until channels) out[ch * 2 + (ch and 1)] = share
    }
    return out
}

// The song's length in ms (NaN when not known) and whether its window is
// live or unseekable, from what the player says about the stream.
@UnstableApi
internal fun songOfStream(metadata: AudioProcessor.StreamMetadata): Pair<Double, Boolean> {
    val uid = metadata.periodUid ?: return Double.NaN to false
    val timeline = metadata.timeline
    if (timeline.isEmpty) return Double.NaN to false
    val index = timeline.getIndexOfPeriod(uid)
    if (index < 0) return Double.NaN to false
    val period = timeline.getPeriod(index, Timeline.Period())
    val window = timeline.getWindow(period.windowIndex, Timeline.Window())
    val end = if (window.durationUs == C.TIME_UNSET) Double.NaN else window.durationUs / 1_000.0
    return end to (window.isLive() || !window.isSeekable)
}

// The queue entry a stream belongs to, from what the player says about it.
@UnstableApi
fun entryOfStream(metadata: AudioProcessor.StreamMetadata): String? {
    val uid = metadata.periodUid ?: return null
    val timeline = metadata.timeline
    if (timeline.isEmpty) return null
    val index = timeline.getIndexOfPeriod(uid)
    if (index < 0) return null
    val period = timeline.getPeriod(index, Timeline.Period())
    return timeline.getWindow(period.windowIndex, Timeline.Window()).mediaItem.entryId
}

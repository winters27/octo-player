package app.winters.octo.sound

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.HandlerWrapper
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatConfig
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatSupport
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

// Bytes in one float stereo frame.
internal const val MIX_FRAME_BYTES = 8

// Mark flags.
internal const val MARK_STREAM_START = 1
internal const val MARK_LIVE = 2

// Song-time marks in a deck ring: from ring frame `ringAt` on, frames carry
// song time `songMs` plus `msPerFrame` each, of stream `entryId`, until the
// next mark. Written by the deck's playback thread, read by the mix thread:
// a mark is complete before the count that shows it is published, and the
// arrays are replaced whole when they grow.
internal class MarkList {
    class Arrays(val size: Int) {
        val ringAt = LongArray(size)
        val songMs = DoubleArray(size)
        val msPerFrame = DoubleArray(size)
        val songEndMs = DoubleArray(size)
        val flags = IntArray(size)
        val entry = arrayOfNulls<String>(size)
    }

    @Volatile private var arrays = Arrays(64)

    // Marks ever added; the mark with number i sits at i % size.
    @Volatile var count = 0L
        private set

    // The oldest mark number the mix thread may still read.
    @Volatile var floor = 0L

    fun add(ringAt: Long, songMs: Double, msPerFrame: Double, entry: String?, flags: Int, songEndMs: Double) {
        var a = arrays
        val n = count
        if (n - floor >= a.size) {
            val bigger = Arrays(a.size * 2)
            for (i in floor until n) copy(a, (i % a.size).toInt(), bigger, (i % bigger.size).toInt())
            arrays = bigger
            a = bigger
        }
        val at = (n % a.size).toInt()
        a.ringAt[at] = ringAt
        a.songMs[at] = songMs
        a.msPerFrame[at] = msPerFrame
        a.songEndMs[at] = songEndMs
        a.flags[at] = flags
        a.entry[at] = entry
        count = n + 1
    }

    private fun copy(from: Arrays, i: Int, to: Arrays, j: Int) {
        to.ringAt[j] = from.ringAt[i]
        to.songMs[j] = from.songMs[i]
        to.msPerFrame[j] = from.msPerFrame[i]
        to.songEndMs[j] = from.songEndMs[i]
        to.flags[j] = from.flags[i]
        to.entry[j] = from.entry[i]
    }

    // Mix thread: a reading of the marks as they stand.
    fun view(into: MarkView): MarkView {
        into.count = count
        into.arrays = arrays
        into.floor = floor
        return into
    }
}

// A consistent reading of a MarkList, taken on the mix thread.
internal class MarkView {
    var count = 0L
    var floor = 0L
    var arrays: MarkList.Arrays = MarkList.Arrays(1)

    private fun slot(i: Long) = (i % arrays.size).toInt()

    // The number of the mark covering ring frame `frame`, or -1.
    fun covering(frame: Long): Long {
        var lo = floor
        var hi = count - 1
        var found = -1L
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (arrays.ringAt[slot(mid)] <= frame) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    fun ringAt(mark: Long): Long = arrays.ringAt[slot(mark)]

    fun songMs(mark: Long): Double = arrays.songMs[slot(mark)]

    fun msPerFrame(mark: Long): Double = arrays.msPerFrame[slot(mark)]

    fun songEndMs(mark: Long): Double = arrays.songEndMs[slot(mark)]

    fun flags(mark: Long): Int = arrays.flags[slot(mark)]

    fun entry(mark: Long): String? = arrays.entry[slot(mark)]

    // The ring frame where the mark after `mark` starts, or Long.MAX_VALUE.
    fun endOf(mark: Long): Long = if (mark + 1 < count) ringAt(mark + 1) else Long.MAX_VALUE

    fun songAt(frame: Long): Double {
        val m = covering(frame)
        if (m < 0) return Double.NaN
        return songMs(m) + (frame - ringAt(m)) * msPerFrame(m)
    }
}

// One deck output's frames at the mix rate, as the deck wrote them, and
// their song-time marks. The playback thread owns `written`; the mix
// thread owns `freed`. Frames stay from the block holding the consumed
// point on, so a re-mix can start there from the raw frames.
internal class DeckRing(val capacity: Int) {
    val raw = FloatArray(capacity * 2)
    val marks = MarkList()

    @Volatile var written = 0L

    // Frames below this may be written over.
    @Volatile var freed = 0L

    fun at(frame: Long): Int = ((frame % capacity) * 2).toInt()
}

// What the mixer last saw of a deck output, for its position: input frames
// presented at `nanos`, whether they run on from there, and how far they
// may run.
internal class OutputSnap(val presentedIn: Long, val nanos: Long, val advancing: Boolean, val maxIn: Long, val songRate: Int)

// A deck's playback-thread memory of its last output, so a new output
// that plays on in the same song at speed 1 lays its resampled frames on
// the old output's grid.
internal class SlotLine {
    var entry: String? = null
    var songRate = 0

    // The song time of input frame 0 of the grid.
    var originMs = Double.NaN
}

// Gives a deck's DefaultAudioSink a MixAudioOutput on the deck's slot. It
// takes 16-bit PCM in (the chain only runs on 16-bit) and float stereo out
// of the chain; anything else, and offload, it refuses.
@UnstableApi
class MixOutputProvider(
    private val mixer: DeckMixer,
    val slot: Int,
    private val labels: DeckTimeQueue?,
    private val deckClock: Clock = Clock.DEFAULT,
) : AudioOutputProvider {
    private val line = SlotLine()

    override fun getFormatSupport(formatConfig: FormatConfig): FormatSupport {
        val f = formatConfig.format
        val pcm = MimeTypes.AUDIO_RAW == f.sampleMimeType && Util.isEncodingLinearPcm(f.pcmEncoding)
        val level = if (pcm) AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY else AudioOutputProvider.FORMAT_UNSUPPORTED
        return FormatSupport.Builder().setFormatSupportLevel(level).build()
    }

    override fun getOutputConfig(formatConfig: FormatConfig): OutputConfig {
        val f = formatConfig.format
        if (f.pcmEncoding != C.ENCODING_PCM_FLOAT || f.channelCount != 2 || formatConfig.enableTunneling) {
            throw AudioOutputProvider.ConfigurationException("The mix takes float stereo PCM, not $f")
        }
        return OutputConfig.Builder()
            .setEncoding(C.ENCODING_PCM_FLOAT)
            .setSampleRate(f.sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setBufferSize(mixer.deckTargetFrames(f.sampleRate) * MIX_FRAME_BYTES)
            .setAudioAttributes(formatConfig.audioAttributes)
            .setAudioSessionId(mixer.sessionId)
            .setIsOffload(false)
            .build()
    }

    override fun getAudioOutput(config: OutputConfig): AudioOutput = MixAudioOutput(mixer, slot, config, labels, deckClock, line)

    override fun addListener(listener: AudioOutputProvider.Listener) {}

    override fun removeListener(listener: AudioOutputProvider.Listener) {}

    // The mixer outlives every provider.
    override fun release() {}
}

// One deck output. Media3 calls it on the deck's playback thread; it
// resamples to the mix rate into a ring the mix thread reads. Every rate,
// size and position it reports is in the song's units.
@UnstableApi
class MixAudioOutput internal constructor(
    private val mixer: DeckMixer,
    val slot: Int,
    config: OutputConfig,
    private val labels: DeckTimeQueue?,
    deckClock: Clock,
    private val line: SlotLine,
) : AudioOutput {
    val id = ids.incrementAndGet()
    val songRate = config.sampleRate
    private val targetFrames = config.bufferSize / MIX_FRAME_BYTES
    internal val ring = DeckRing(mixer.ringCapacityFrames())
    private val handler: HandlerWrapper = deckClock.createHandler(Looper.myLooper() ?: Looper.getMainLooper(), null)
    private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()

    // Created at the first write, when the stream's first song time says
    // which grid it lies on.
    @Volatile internal var resampler: OctoResampler? = null
        private set

    // Input frames taken, at the song's rate.
    @Volatile internal var inWritten = 0L
        private set

    // The deck's sink plays (as the mixer sees it: a pause counts once the
    // playback thread's message that made it has ended).
    @Volatile internal var playing = false

    // The sink stopped feeding: its last frames are in the ring.
    @Volatile internal var ended = false
        private set

    @Volatile internal var released = false
        private set

    @Volatile internal var volume = 1f
        private set

    // Set by the mixer when this deck must fail: its next write throws.
    @Volatile internal var failure = 0

    // Whether its writes have woken the mixer yet.
    @Volatile internal var nudged = false

    @Volatile internal var snap = OutputSnap(0, 0, false, 0, songRate)

    // Each play and pause call's number: a posted pause acts only if it is
    // still the latest.
    private val calls = AtomicInteger()
    @Volatile private var advancingSent = false

    private var scratch = FloatArray(4_096 * 2)
    private var lastMarkEnd = Double.NaN
    private var lastMarkMsPerFrame = Double.NaN
    private var lastMarkEntry: String? = null
    private var lastMarkFlags = -1
    private var nextMarkRing = 0L

    init {
        mixer.outputCreated(this)
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        val failed = failure
        if (failed != 0) throw AudioOutput.WriteException(failed, false)
        val frames = buffer.remaining() / MIX_FRAME_BYTES
        if (frames == 0) return true
        val r = resampler ?: startStream()
        val free = mixer.fillLimitFrames(ring.capacity) - (ring.written - ring.freed)
        val k = minOf(frames, r.maxInputFor(free.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()))
        if (k <= 0) {
            mixer.deckWrote(this)
            return false
        }
        takeLabels(r, k)
        if (scratch.size < k * 2) scratch = FloatArray(k * 2)
        val floats = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
        floats.get(scratch, 0, k * 2)
        buffer.position(buffer.position() + k * MIX_FRAME_BYTES)
        r.process(scratch, k, ::put)
        inWritten += k
        mixer.deckWrote(this)
        return k == frames
    }

    // The first write: the resampler, laid on the grid of the slot's last
    // output when this one plays on in the same song at speed 1 and the
    // same rate, so a player's own restart joins it sample for sample.
    private fun startStream(): OctoResampler {
        val first = labels?.peek()
        var grid = 0L
        if (first != null && first.entryId != null) {
            val songMs = first.songMs + first.taken * first.msPerFrame
            val unit = 1_000.0 / songRate
            val atSpeedOne = abs(first.msPerFrame - unit) < unit * 1e-9
            if (atSpeedOne && line.entry == first.entryId && line.songRate == songRate && !line.originMs.isNaN()) {
                val frames = (songMs - line.originMs) / unit
                val whole = Math.round(frames)
                if (abs(frames - whole) < 0.01 && whole >= 0) grid = whole
            }
            if (grid == 0L) line.originMs = songMs
            line.entry = first.entryId
            line.songRate = songRate
        }
        val made = OctoResampler(songRate, mixer.mixRate, gridFrom = grid)
        resampler = made
        return made
    }

    // Turns the labels of the next k input frames into ring marks wherever
    // the song time, the speed or the stream changes.
    private fun takeLabels(r: OctoResampler, k: Int) {
        val queue = labels
        var left = k
        var input = r.inputTaken
        while (left > 0) {
            val label = queue?.peek()
            if (label == null) {
                if (lastMarkFlags < 0) addMark(r, input, Double.NaN, Double.NaN, null, 0, Double.NaN)
                return
            }
            val take = minOf(left, label.frames - label.taken)
            val songMs = label.songMs + label.taken * label.msPerFrame
            var flags = if (label.live) MARK_LIVE else 0
            if (label.streamStart && label.taken == 0) flags = flags or MARK_STREAM_START
            addMark(r, input, songMs, label.msPerFrame, label.entryId, flags, label.songEndMs)
            queue.take(take)
            left -= take
            input += take
        }
    }

    private fun addMark(r: OctoResampler, input: Long, songMs: Double, msPerFrame: Double, entry: String?, flags: Int, songEndMs: Double) {
        val at = r.firstOutputAtOrAfter(input)
        val shift = r.inputPositionOf(at) - input
        val markMs = songMs + shift * msPerFrame
        val perMix = msPerFrame * songRate / mixer.mixRate
        val predicted = lastMarkEnd + (at - nextMarkRing) * lastMarkMsPerFrame
        val continuous = flags and MARK_STREAM_START == 0 && entry == lastMarkEntry && flags == lastMarkFlags &&
            abs(perMix - lastMarkMsPerFrame) <= 1e-12 && abs(markMs - predicted) <= abs(perMix) * 0.25
        if (!continuous || lastMarkFlags < 0) {
            ring.marks.add(at, markMs, perMix, entry, flags, songEndMs)
            lastMarkEnd = markMs
            nextMarkRing = at
            lastMarkMsPerFrame = perMix
            lastMarkEntry = entry
            lastMarkFlags = flags
        }
    }

    private fun put(data: FloatArray, n: Int) {
        var w = ring.written
        val raw = ring.raw
        for (i in 0 until n) {
            val at = ring.at(w)
            raw[at] = data[i * 2]
            raw[at + 1] = data[i * 2 + 1]
            w++
        }
        ring.written = w
    }

    // Mix thread: what of the ring went where on the mix timeline.
    private val runs = ArrayDeque<Run>()
    private var runsPresented = 0L

    internal fun addRun(mixAt: Long, ringStart: Long, n: Int) {
        if (n <= 0) return
        val last = runs.lastOrNull()
        if (last != null && last.mixEnd > last.mixAt && last.mixEnd == mixAt && last.ringEnd == ringStart) {
            last.mixEnd += n
            last.ringEnd += n
        } else {
            runs.addLast(Run(mixAt, ringStart, ringStart + n, mixAt + n))
        }
    }

    internal fun addDrop(mixAt: Long, ringStart: Long, n: Long) {
        if (n > 0) runs.addLast(Run(mixAt, ringStart, ringStart + n, mixAt))
    }

    // The ring frames used by mix frames before `mix` (all of a drop at or
    // before it).
    internal fun ringAt(mix: Long): Long {
        var r = runsPresented
        for (run in runs) {
            if (run.mixAt > mix) break
            r = if (run.mixEnd <= mix) run.ringEnd else run.ringStart + (mix - run.mixAt)
        }
        return r
    }

    // Whether this output's mixed frames cover mix frame `mix`.
    internal fun coversMixFrame(mix: Long): Boolean {
        val first = runs.firstOrNull { it.mixEnd > it.mixAt } ?: return false
        val last = runs.lastOrNull { it.mixEnd > it.mixAt } ?: return false
        return mix >= first.mixAt && mix < last.mixEnd
    }

    internal fun lastRunEnd(): Long = runs.lastOrNull()?.let { if (it.mixEnd > it.mixAt) it.mixEnd else it.mixAt } ?: -1L

    // Forgets what was mixed at or after `mix` (a re-mix does it again).
    internal fun clipRuns(mix: Long) {
        while (runs.isNotEmpty() && runs.last().mixAt >= mix) runs.removeLast()
        val last = runs.lastOrNull() ?: return
        if (last.mixEnd > mix) {
            last.ringEnd = last.ringStart + (mix - last.mixAt)
            last.mixEnd = mix
        }
    }

    // Forgets runs that ended before `mix`.
    internal fun pruneRuns(mix: Long) {
        while (runs.size > 1) {
            val f = runs.first()
            val done = if (f.mixEnd > f.mixAt) f.mixEnd < mix else f.mixAt < mix
            if (!done) break
            runsPresented = runs.removeFirst().ringEnd
        }
    }

    internal fun firePositionAdvancing() {
        if (advancingSent) return
        advancingSent = true
        val now = System.currentTimeMillis()
        handler.post { for (l in listeners) l.onPositionAdvancing(now) }
    }

    override fun play() {
        val call = calls.incrementAndGet()
        playing = true
        mixer.deckPlay(this, call)
    }

    // Acted on only after the playback thread's current message ends, and
    // only if no later play or pause and no release came: Media3's seek and
    // its own restart pause the sink and release the output in one message.
    override fun pause() {
        val call = calls.incrementAndGet()
        handler.post {
            if (call == calls.get() && !released) {
                playing = false
                mixer.deckPause(this, call)
            }
        }
    }

    // Media3 1.11.1 never calls this (DefaultAudioSink releases the output
    // instead); it drops the unmixed frames on the mix thread and waits.
    override fun flush() = mixer.flushDeck(this)

    override fun stop() {
        if (ended) return
        resampler?.finish(::put)
        ended = true
        mixer.deckWrote(this)
    }

    override fun release() {
        released = true
        mixer.outputReleased(this)
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        mixer.deckVolume(this)
    }

    override fun isOffloadedPlayback() = false

    override fun getAudioSessionId() = mixer.sessionId

    override fun getSampleRate() = songRate

    override fun getBufferSizeInFrames(): Long = targetFrames.toLong()

    // Input frames presented, from the mixer's last reading, run on by the
    // clock while they advance and never past what the mixer allows.
    override fun getPositionUs(): Long {
        val s = snap
        var frames = s.presentedIn
        if (s.advancing) frames += ((mixer.nanoTime() - s.nanos) * songRate / 1_000_000_000L).coerceAtLeast(0)
        return Util.sampleCountToDurationUs(minOf(frames, s.maxIn), songRate)
    }

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

    override fun isStalled() = false

    override fun addListener(listener: AudioOutput.Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: AudioOutput.Listener) {
        listeners.remove(listener)
    }

    override fun setPlaybackParameters(playbackParams: PlaybackParameters) {}

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {}

    override fun setOffloadEndOfStream() {}

    override fun canReuseAudioOutput(currentConfig: OutputConfig, newFormat: FormatConfig, newConfig: OutputConfig): Boolean =
        newConfig.encoding == currentConfig.encoding && newConfig.channelMask == currentConfig.channelMask &&
            newConfig.sampleRate == currentConfig.sampleRate

    override fun attachAuxEffect(effectId: Int) = mixer.onReal { it.attachAuxEffect(effectId) }

    override fun setAuxEffectSendLevel(level: Float) = mixer.onReal { it.setAuxEffectSendLevel(level) }

    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = mixer.preferDevice(preferredDevice)

    override fun toString(): String = "deck output $id on slot $slot"

    private companion object {
        val ids = AtomicInteger()
    }
}

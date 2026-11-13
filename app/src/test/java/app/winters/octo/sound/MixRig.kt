package app.winters.octo.sound

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig
import app.winters.octo.playback.BlendArm
import app.winters.octo.playback.LATE_MOVE_UNBOUNDED
import app.winters.octo.playback.TransitionKind
import app.winters.octo.playback.TransitionPlan
import org.robolectric.Shadows.shadowOf
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.min

// One virtual clock and every thread's queue on it, run in time order, so
// the mixer, its builder and gate threads, main and the fake system mixer
// interleave exactly as scheduled with no sleeps.
class Sched {
    var now = 0L
        private set
    private var seq = 0L
    private class Item(val at: Long, val seq: Long, val loop: TestLoop?, val task: Runnable)
    private val queue = PriorityQueue<Item>(compareBy<Item>({ it.at }, { it.seq }))
    internal var running: TestLoop? = null

    internal fun add(at: Long, loop: TestLoop?, task: Runnable) {
        queue.add(Item(max(at, now), seq++, loop, task))
    }

    internal fun remove(loop: TestLoop?, task: Runnable) {
        queue.removeIf { it.loop === loop && it.task === task }
    }

    // Runs everything due up to `nanos`, then stands the clock there.
    fun runUntil(nanos: Long) {
        while (true) {
            val next = queue.peek() ?: break
            if (next.at > nanos) break
            queue.poll()
            now = max(now, next.at)
            val was = running
            running = next.loop
            next.task.run()
            running = was
            shadowOf(Looper.getMainLooper()).idle()
        }
        now = max(now, nanos)
        shadowOf(Looper.getMainLooper()).idle()
    }

    fun runFor(ms: Double) = runUntil(now + (ms * 1_000_000).toLong())

    // A task on the clock outside any loop (the fake's cycles, a test's own steps).
    fun at(nanos: Long, task: () -> Unit) = add(nanos, null, Runnable(task))

    fun loop(name: String) = TestLoop(this, name)
}

class TestLoop(private val sched: Sched, val name: String) : MixLoop {
    override fun nanoTime(): Long = sched.now

    override fun post(task: Runnable) = sched.add(sched.now, this, task)

    override fun postAt(nanos: Long, task: Runnable) {
        sched.remove(this, task)
        sched.add(nanos, this, task)
    }

    override fun remove(task: Runnable) = sched.remove(this, task)

    override fun isCurrent(): Boolean = sched.running === this

    override fun handler(): Handler = Handler(Looper.getMainLooper())

    override fun quit() {}
}

// A model of the system mixer behind one stream track, by mechanism, not
// by one run's means: it runs cycles of `quantum` frames at the instants
// the profile gives, takes from a started track only at a cycle, mixes a
// newly played track first at the cycle after play(), plays a track only
// once it holds the smaller of its start threshold and its buffer (on
// every start, resume and underrun restart), fades a paused track to
// silence over its next `pauseCycles` cycles then stops it, and presents
// each cycle `latencyCycles` cycles after it took it.
@UnstableApi
class FakeSystemMixer(
    val sched: Sched,
    val quantum: Int = 1_920,
    val bufferFrames: Int = 24_000,
    val pauseCycles: Int = 1,
    val latencyCycles: Int = 3,
    // The gap before each cycle, in ms, repeated.
    val spacingMs: DoubleArray = doubleArrayOf(40.0),
) {
    val tracks = ArrayList<FakeTrack>()
    private var cycles = 0L
    var running = false
        private set

    fun start() {
        if (running) return
        running = true
        schedule()
    }

    fun stop() {
        running = false
    }

    private fun schedule() {
        val gap = spacingMs[(cycles % spacingMs.size).toInt()]
        sched.at(sched.now + (gap * 1_000_000).toLong()) {
            if (!running) return@at
            cycles++
            for (t in tracks.toList()) t.cycle(sched.now)
            schedule()
        }
    }

    // Everything any track played, in order, with the nanos of its cycle.
    val heard = ArrayList<Played>()

    class Played(val track: Int, val nanos: Long, val frame: Long, val left: Float, val right: Float)
}

@UnstableApi
class FakeTrack(private val mixer: FakeSystemMixer, val id: Int, override val bufferFrames: Int) : RealOutput {
    private val data = ArrayList<Float>()
    var written = 0L
        private set
    var head = 0L
        private set
    var playing = false
        private set
    var pausing = 0
        private set
    private var filling = true
    var threshold = bufferFrames
        private set
    var volume = 1f
        private set
    var released = false
        private set
    var dead = false
    var underrunCount = 0
        private set
    var route: String? = "speaker"
    private var routeListener: (() -> Unit)? = null
    private var playedAt = -1L
    private val presented = ArrayList<LongArray>()
    val writes = ArrayList<Long>()
    var plays = 0
        private set
    var pauses = 0
        private set

    override fun write(samples: FloatArray, offset: Int, frames: Int): Int {
        if (dead) throw RealOutputDead(-32)
        check(!released) { "write after release" }
        val room = (bufferFrames - (written - head)).toInt()
        val n = min(frames, room)
        for (i in 0 until n * 2) data += samples[offset * 2 + i]
        written += n
        if (n > 0) writes += mixer.sched.now
        return n
    }

    // The frame `i` this track was written, left channel.
    fun left(i: Long): Float = data[(i * 2).toInt()]

    fun right(i: Long): Float = data[(i * 2 + 1).toInt()]

    override fun play() {
        check(!released)
        plays++
        playing = true
        pausing = 0
        filling = true
        playedAt = mixer.sched.now
    }

    override fun pause() {
        if (!playing) return
        pauses++
        pausing = mixer.pauseCycles
        playing = false
    }

    override fun release() {
        released = true
        mixer.tracks.remove(this)
    }

    internal fun cycle(nanos: Long) {
        // A dead track plays nothing more.
        if (released || dead) return
        val active = playing || pausing > 0
        if (!active) return
        // A newly played track joins at the first cycle after play().
        if (playing && playedAt >= nanos) return
        val unplayed = written - head
        if (filling) {
            if (unplayed < min(threshold, bufferFrames)) return
            filling = false
        }
        val take = min(mixer.quantum.toLong(), unplayed).toInt()
        val fade = if (!playing && pausing > 0) pausing.toFloat() / (mixer.pauseCycles + 1) else 1f
        for (k in 0 until take) {
            val f = head + k
            var g = volume
            if (!playing) g *= fade * (1f - k.toFloat() / take)
            mixer.heard += FakeSystemMixer.Played(id, nanos, f, left(f) * g, right(f) * g)
        }
        head += take
        presented += longArrayOf(nanos + mixer.latencyCycles * (mixer.spacingMs[0] * 1_000_000).toLong(), head)
        if (take < mixer.quantum && playing) {
            underrunCount++
            filling = true
        }
        if (!playing && pausing > 0) {
            pausing--
            if (pausing == 0) filling = true
        }
    }

    override fun head(): Long = head and 0xFFFFFFFFL

    override fun timestamp(into: RealTimestamp): Boolean {
        val now = mixer.sched.now
        val last = presented.lastOrNull { it[0] <= now } ?: return false
        into.frame = last[1]
        into.nanos = last[0]
        return true
    }

    override fun underruns(): Int = underrunCount

    override fun setVolume(volume: Float) {
        this.volume = volume
    }

    override fun setStartThreshold(frames: Int): Int {
        threshold = max(1, frames)
        if (playing && filling && written - head >= min(threshold, bufferFrames)) filling = true
        return threshold
    }

    override fun setBufferSize(frames: Int): Int {
        threshold = frames
        return frames
    }

    override fun isStalled(): Boolean = false

    override fun routedDevice(): String? = route

    override fun listenForRoutes(handler: Handler, onRouted: () -> Unit) {
        routeListener = onRouted
    }

    fun reroute(to: String) {
        route = to
        routeListener?.invoke()
    }

    override fun setPreferredDevice(device: AudioDeviceInfo?) {}

    override fun attachAuxEffect(effectId: Int) {}

    override fun setAuxEffectSendLevel(level: Float) {}
}

@UnstableApi
class FakeBuilder(private val mixer: FakeSystemMixer) : RealOutputBuilder {
    val built = ArrayList<FakeTrack>()
    var failing = 0
    var pendingReleaseUntil = -1L

    override fun build(bufferFrames: Int): RealOutput {
        if (failing > 0) {
            failing--
            throw IllegalStateException("no track")
        }
        val t = FakeTrack(mixer, built.size + 1, bufferFrames)
        built += t
        mixer.tracks += t
        mixer.start()
        return t
    }

    override fun hasPendingReleases(): Boolean = mixer.sched.now < pendingReleaseUntil

    override fun minimumFrames(): Int = 960

    override fun release() {}
}

// The system's playback list as a script: Octo's tracks appear once they
// play, plus whatever the test adds.
@UnstableApi
class FakeList(private val builder: FakeBuilder) : PlaybackList {
    var others = 0
    var hidden = false

    override fun musicEntries(): Int {
        if (hidden) return others
        return others + builder.built.count { it.playing && !it.released }
    }
}

// The mixer with its fake system mixer, built on one virtual clock.
@UnstableApi
class MixRig(
    val rate: Int = 48_000,
    quantum: Int = 1_920,
    spacingMs: DoubleArray = doubleArrayOf(40.0),
    val tuning: MixTuning = MixTuning(watchdogMs = 2_000),
    latencyCycles: Int = 3,
) {
    val sched = Sched()
    val system = FakeSystemMixer(sched, quantum = quantum, bufferFrames = rate / 2, spacingMs = spacingMs, latencyCycles = latencyCycles)
    val builder = FakeBuilder(system)
    val list = FakeList(builder)
    val mixLoop = sched.loop("mix")
    val builderLoop = sched.loop("builder")
    val gateLoop = sched.loop("gate")
    val mainLoop = sched.loop("main")
    val events = ArrayList<MixEvent>()
    val gateBounds = ArrayList<GateRecord>()

    // A deck's feed stops at this time on the clock (a stalled source).
    val stalled = longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE)
    val written = ArrayList<FloatArray>()
    var onEvent: (MixEvent) -> Unit = {}

    val mixer = DeckMixer(
        mixRate = rate,
        sessionId = 7,
        loop = mixLoop,
        builderLoop = builderLoop,
        gateLoop = gateLoop,
        builder = builder,
        playbackList = list,
        toMain = { mainLoop.post(it) },
        listener = object : MixListener {
            override fun onBlendEvent(event: MixEvent) {
                events += event
                onEvent(event)
            }

            override fun onGateBound(record: GateRecord) {
                gateBounds += record
            }
        },
        tuning = tuning,
        sdk = 35,
        pcmTargetFrames = { it / 2 },
    )

    // Every frame written to any track, in the order of the mix timeline.
    val tap = MixTapRecorder()

    init {
        mixer.probe = tap
    }

    fun deck(slot: Int, songRate: Int = rate): TestDeck = TestDeck(this, slot, songRate)

    fun runFor(ms: Double) = sched.runFor(ms)

    fun track(i: Int): FakeTrack = builder.built[i]

    val lastTrack: FakeTrack get() = builder.built.last()
}

// Records what the mixer wrote, frame by frame on the mix timeline.
class MixTapRecorder : MixProbe {
    val frames = HashMap<Long, Float>()
    val byOutput = HashMap<Int, ArrayList<Long>>()
    val notes = ArrayList<String>()

    override fun written(output: Int, frame: Long, samples: FloatArray, offset: Int, frames: Int) {
        val list = byOutput.getOrPut(output) { ArrayList() }
        for (i in 0 until frames) {
            this.frames[frame + i] = samples[(offset + i) * 2]
            list += frame + i
        }
    }

    override fun note(what: String, frame: Long) {
        notes += "$what@$frame"
    }
}

// A deck feeding one MixAudioOutput directly, as its sink would: stereo
// floats at its song rate, each buffer labeled with its song time.
@UnstableApi
class TestDeck(val rig: MixRig, val slot: Int, val songRate: Int) {
    val labels = DeckTimeQueue()
    val provider = rig.mixer.provider(slot, labels)
    var output: MixAudioOutput = newOutput()
        private set
    var entry: String? = "e$slot"
    var songMs = 0.0
    var speed = 1.0
    var songEndMs = Double.NaN
    private var streamStart = true
    private var frameIndex = 0L

    // Samples by song frame, so a test knows what each frame should be.
    var signal: (Long) -> Float = { f -> ((f % 997) - 498) / 1_000f }

    fun newOutput(): MixAudioOutput {
        val config = OutputConfig.Builder()
            .setEncoding(C.ENCODING_PCM_FLOAT)
            .setSampleRate(songRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setBufferSize(songRate / 2 * MIX_FRAME_BYTES)
            .setAudioAttributes(AudioAttributes.DEFAULT)
            .setAudioSessionId(7)
            .build()
        return provider.getAudioOutput(config) as MixAudioOutput
    }

    // The first output plays from `fromMs` of the song.
    fun from(fromMs: Double) {
        songMs = fromMs
        frameIndex = Math.round(fromMs * songRate / 1_000.0)
    }

    // A new output from `fromMs` of the song (a seek or the player's own
    // restart), released or not by the caller.
    fun restartAt(fromMs: Double, release: Boolean = true) {
        if (release) output.release()
        labels.clear()
        output = newOutput()
        songMs = fromMs
        frameIndex = (fromMs * songRate / 1_000.0).toLong()
        streamStart = true
    }

    // Writes up to `frames` song frames; returns how many the output took.
    fun write(frames: Int): Int {
        val buffer = ByteBuffer.allocateDirect(frames * MIX_FRAME_BYTES).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val v = signal(frameIndex + i)
            buffer.putFloat(v).putFloat(v)
        }
        buffer.flip()
        labels.push(frames, songMs, speed * 1_000.0 / songRate, entry, streamStart, songEndMs, false)
        streamStart = false
        output.write(buffer, 1, 0)
        val took = frames - buffer.remaining() / MIX_FRAME_BYTES
        if (took < frames) {
            // The rest stays with the sink, as Media3 keeps it: its label too.
            labels.clear()
        }
        songMs += took * speed * 1_000.0 / songRate
        frameIndex += took
        return took
    }

    // Writes as much as fits, in buffers of `chunk`.
    fun fill(chunk: Int = 1_024) {
        while (write(chunk) == chunk) {}
    }

    // The deck's sink feeds as a playback thread would: `chunk` frames
    // every `everyMs`, for `forMs`.
    fun feed(everyMs: Double = 10.0, chunk: Int = (songRate * 0.02).toInt(), forMs: Double = 1e9, until: () -> Boolean = { false }) {
        val stop = rig.sched.now + (forMs * 1_000_000).toLong()
        fun step() {
            if (rig.sched.now >= stop || until() || rig.sched.now >= rig.stalled[slot]) return
            fill(chunk)
            rig.sched.at(rig.sched.now + (everyMs * 1_000_000).toLong()) { step() }
        }
        step()
    }

    fun play() = output.play()

    fun pause() = output.pause()
}

// A plain test plan: start and entry in ms, an overlap, equal-power.
fun testPlan(start: Long, entry: Long, overlap: Long, kind: TransitionKind = TransitionKind.BLEND, strength: Double = 0.0, holdShare: Double = 0.0, beatMs: Double? = null) =
    TransitionPlan(start, entry, overlap, kind, 0.4, strength, null, beatMs, beatMs?.let { 0.0 }, false, 0.0, "test", holdShare = holdShare, latestEndMs = start + overlap + 4_000, lateMoveBars = LATE_MOVE_UNBOUNDED)

@UnstableApi
fun testBlend(gen: Long, plan: TransitionPlan, a: Int = 0, b: Int = 1, endMs: Double = plan.startMs + plan.overlapMs + 5_000.0, lateMove: Boolean = true): MixBlend =
    MixBlend(
        arm = BlendArm(
            generation = gen,
            startMs = plan.startMs.toDouble(),
            overlapMs = plan.overlapMs.toDouble(),
            latestEndMs = plan.latestEndMs.toDouble(),
            outgoingEndMs = endMs,
            lateMoveBars = if (lateMove) LATE_MOVE_UNBOUNDED else 0,
            barMs = plan.beatMs?.let { it * 4 } ?: Double.NaN,
            holdShare = plan.holdShare,
        ),
        outgoingSlot = a,
        incomingSlot = b,
        plan = plan,
        outgoing = DeckTransition("e$a", plan, incoming = false),
        incoming = DeckTransition("e$b", plan, incoming = true),
    )

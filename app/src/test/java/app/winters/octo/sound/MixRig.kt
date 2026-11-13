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
import app.winters.octo.playback.BlendEventKind
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

    // How late every timed wake runs (a busy phone's late wakes).
    var lateNanos = 0L

    override fun post(task: Runnable) = sched.add(sched.now, this, task)

    override fun postAt(nanos: Long, task: Runnable) {
        sched.remove(this, task)
        sched.add(nanos + lateNanos, this, task)
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
    var quantum: Int = 1_920,
    val bufferFrames: Int = 24_000,
    val pauseCycles: Int = 1,
    val latencyCycles: Int = 3,
    // The gap before each cycle, in ms, repeated.
    val spacingMs: DoubleArray = doubleArrayOf(40.0),
) {
    val tracks = ArrayList<FakeTrack>()
    var cycles = 0L
        private set
    var running = false
        private set

    // The frames a cycle takes, by cycle number, when a test sets it (a
    // cycle a track only partly fills, an alternating resampled route).
    var takes: ((Long) -> Int)? = null

    // The gap before the cycle after the one at `nanos`, when a test sets
    // it; null keeps the spacing.
    var gapAfter: ((Long) -> Double?)? = null

    // A session effect that controls volume: every track is mixed at
    // unity and one volume, that of the track whose volume was set last,
    // applies to the whole session, smoothed over 100 ms (LVM) or stepped
    // once a cycle (DynamicsProcessing).
    var effect = Effect.NONE
    enum class Effect { NONE, SMOOTHED, STEPPED }
    internal var lastVolumeSet: FakeTrack? = null
    private var sessionVolume = 1f
    val sessionVolumes = ArrayList<Float>()

    fun start() {
        if (running) return
        running = true
        schedule()
    }

    fun stop() {
        running = false
    }

    fun take(): Int = takes?.invoke(cycles) ?: quantum

    private fun schedule() {
        val gap = gapAfter?.invoke(sched.now) ?: spacingMs[(cycles % spacingMs.size).toInt()]
        sched.at(sched.now + (gap * 1_000_000).toLong()) {
            if (!running) return@at
            cycles++
            if (effect == Effect.NONE) {
                for (t in tracks.toList()) t.cycle(sched.now, null)
            } else {
                val target = lastVolumeSet?.volume ?: 1f
                val perFrame = 1f / (bufferFrames / 500f * 100f)
                val volumes = FloatArray(take()) { k ->
                    if (effect == Effect.STEPPED) {
                        target
                    } else {
                        sessionVolume += (target - sessionVolume).coerceIn(-perFrame, perFrame)
                        sessionVolume
                    }
                }
                if (effect == Effect.STEPPED) sessionVolume = target
                for (v in volumes) sessionVolumes += v
                for (t in tracks.toList()) t.cycle(sched.now, volumes)
            }
            schedule()
        }
    }

    // Everything any track played, in order, with the nanos of its cycle.
    val heard = ArrayList<Played>()

    class Played(val track: Int, val nanos: Long, val frame: Long, val left: Float, val right: Float)
}

@UnstableApi
class FakeTrack(private val mixer: FakeSystemMixer, val id: Int, override val bufferFrames: Int) : RealOutput {
    val builtAt = mixer.sched.now
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

    // API 29 and 30: the buffer size in use, which bounds what the track
    // holds and, with no start threshold, what it must hold to start.
    var sizeInUse = bufferFrames
        private set
    var volume = 1f
        private set
    var released = false
        private set
    var releasedAt = -1L
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
    val playTimes = ArrayList<Long>()
    val pauseTimes = ArrayList<Long>()

    // Every start-threshold and buffer-size call, and every volume set, as
    // (nanos, value).
    val thresholdCalls = ArrayList<LongArray>()
    val sizeCalls = ArrayList<LongArray>()
    val volumeCalls = ArrayList<Pair<Long, Float>>()

    // The first cycle that took music from this track, and the head at
    // each cycle, as (nanos, head).
    var firstCycleNanos = -1L
        private set
    val steps = ArrayList<LongArray>()

    // Timestamps stop at this frame (a fake whose timestamps freeze short
    // of the head).
    var stampsUpTo = Long.MAX_VALUE

    override fun write(samples: FloatArray, offset: Int, frames: Int): Int {
        if (dead) throw RealOutputDead(-32)
        check(!released) { "write after release" }
        val room = (min(bufferFrames, sizeInUse) - (written - head)).toInt().coerceAtLeast(0)
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
        playTimes += mixer.sched.now
        playing = true
        pausing = 0
        filling = true
        playedAt = mixer.sched.now
    }

    // What it held written at each pause.
    val writtenAtPauses = ArrayList<Long>()

    override fun pause() {
        if (!playing) return
        pauses++
        pauseTimes += mixer.sched.now
        writtenAtPauses += written
        pausing = mixer.pauseCycles
        playing = false
        // A pausing track under a volume-controlling effect goes to 0 with
        // no ramp of its own.
        if (mixer.effect != FakeSystemMixer.Effect.NONE) setVolume(0f)
    }

    override fun release() {
        released = true
        releasedAt = mixer.sched.now
        mixer.tracks.remove(this)
    }

    // The route went away and the next client call restores the track:
    // its unplayed frames are dropped and its head moves to its written
    // count, as AudioTrack's restoreTrack_l does.
    fun invalidate(to: String) {
        route = to
        head = written
    }

    // A stuck track takes nothing more, with no error.
    var stuck = false

    internal fun cycle(nanos: Long, sessionVolumes: FloatArray?) {
        // A dead track plays nothing more.
        if (released || dead || stuck) return
        val active = playing || pausing > 0
        if (!active) return
        // A newly played track joins at the first cycle after play().
        if (playing && playedAt >= nanos) return
        val unplayed = written - head
        if (filling) {
            if (unplayed < min(threshold, min(bufferFrames, sizeInUse))) return
            filling = false
        }
        val quantum = mixer.take()
        val take = min(quantum.toLong(), unplayed).toInt()
        val fade = if (!playing && pausing > 0) pausing.toFloat() / (mixer.pauseCycles + 1) else 1f
        for (k in 0 until take) {
            val f = head + k
            var g = if (sessionVolumes != null) sessionVolumes[min(k, sessionVolumes.size - 1)] else volume
            if (!playing && sessionVolumes == null) g *= fade * (1f - k.toFloat() / take)
            mixer.heard += FakeSystemMixer.Played(id, nanos, f, left(f) * g, right(f) * g)
        }
        if (take > 0 && firstCycleNanos < 0) firstCycleNanos = nanos
        head += take
        steps += longArrayOf(nanos, head)
        if (head <= stampsUpTo) presented += longArrayOf(nanos + mixer.latencyCycles * (mixer.spacingMs[0] * 1_000_000).toLong(), head)
        if (take < quantum && playing) {
            underrunCount++
            filling = true
        }
        if (!playing && pausing > 0) {
            pausing--
            if (pausing == 0) filling = true
        }
    }

    // Reads of 0 still to come (Android 10's read after a failed Bluetooth
    // connect).
    var zeroReads = 0

    override fun head(): Long {
        if (zeroReads > 0) {
            zeroReads--
            return 0
        }
        return head and 0xFFFFFFFFL
    }

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
        volumeCalls += mixer.sched.now to volume
        mixer.lastVolumeSet = this
    }

    override fun setStartThreshold(frames: Int): Int {
        threshold = max(1, frames)
        thresholdCalls += longArrayOf(mixer.sched.now, threshold.toLong())
        return threshold
    }

    override fun setBufferSize(frames: Int): Int {
        sizeInUse = min(bufferFrames, max(1, frames))
        sizeCalls += longArrayOf(mixer.sched.now, sizeInUse.toLong())
        return sizeInUse
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

    // A new client proxy (a re-route on API 29 and 30) starts at the whole buffer.
    fun resetSize() {
        sizeInUse = bufferFrames
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

    // What each new track is routed to, and where its timestamps stop.
    var nextRoute: String? = "speaker"
    var nextStampsUpTo = Long.MAX_VALUE
    var nextStuck = false

    override fun build(bufferFrames: Int): RealOutput {
        if (failing > 0) {
            failing--
            throw IllegalStateException("no track")
        }
        val t = FakeTrack(mixer, built.size + 1, bufferFrames)
        t.route = nextRoute
        t.stampsUpTo = nextStampsUpTo
        t.stuck = nextStuck
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
class FakeList(private val builder: FakeBuilder, private val sched: Sched) : PlaybackList {
    var others = 0
    var hidden = false

    // How long system_server takes to list a started track and to take a
    // paused or released one off.
    var arriveLagNanos = 0L
    var departLagNanos = 0L

    override fun musicEntries(): Int {
        if (hidden) return others
        val now = sched.now
        return others + builder.built.count { t ->
            val played = t.playTimes.lastOrNull() ?: return@count false
            val left = listOfNotNull(t.pauseTimes.lastOrNull()?.takeIf { it >= played }, t.releasedAt.takeIf { it >= 0 }).minOrNull()
            now >= played + arriveLagNanos && (left == null || now < left + departLagNanos)
        }
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
    sdk: Int = 35,
    // Route measures the mixer keeps by device key; a test may store some.
    val routes: MutableMap<String, RouteMeasure> = HashMap(),
    pauseCycles: Int = 1,
) {
    val sched = Sched()
    val system = FakeSystemMixer(sched, quantum = quantum, bufferFrames = rate / 2, spacingMs = spacingMs, latencyCycles = latencyCycles, pauseCycles = pauseCycles)
    val builder = FakeBuilder(system)
    val list = FakeList(builder, sched)
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
        sdk = sdk,
        pcmTargetFrames = { it / 2 },
        routes = routes,
    )

    // Main acks every proposal as it hears it, as the Crossfader does.
    fun ackProposals() {
        onEvent = { e -> if (e.kind == BlendEventKind.PROPOSED && mixer.commitWord.ack(e.generation)) mixer.acked(e.generation) }
    }

    // Registers `blend` as the Crossfader does: the generation opened first.
    fun arm(blend: MixBlend) {
        mixer.commitWord.open(blend.arm.generation)
        mixer.arm(blend)
    }

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

    // The deck reads on into its next song gaplessly: the same output, a
    // new stream from `fromMs` of `next`.
    fun nextSong(next: String, fromMs: Double = 0.0, endMs: Double = Double.NaN) {
        entry = next
        songMs = fromMs
        songEndMs = endMs
        frameIndex = Math.round(fromMs * songRate / 1_000.0)
        streamStart = true
    }

    // The song frame the next write starts at.
    val nextFrame: Long get() = frameIndex

    // The deck's data arrives at its own pace: `chunk` frames every
    // `everyMs` and no more, for `forMs`. A later call takes over from an
    // earlier one.
    private var paces = 0

    fun pace(everyMs: Double = 10.0, chunk: Int = (songRate * everyMs / 1_000).toInt(), forMs: Double = 1e9, until: () -> Boolean = { false }) {
        val stop = rig.sched.now + (forMs * 1_000_000).toLong()
        val mine = ++paces
        fun step() {
            if (mine != paces || rig.sched.now >= stop || until() || rig.sched.now >= rig.stalled[slot]) return
            write(chunk)
            rig.sched.at(rig.sched.now + (everyMs * 1_000_000).toLong()) { step() }
        }
        step()
    }

    // The sink's stream ends: its last frames are in the ring.
    fun end() = output.stop()

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

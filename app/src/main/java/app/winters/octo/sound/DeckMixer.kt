package app.winters.octo.sound

import android.media.AudioDeviceInfo
import android.os.Build
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import app.winters.octo.playback.BLEND_PASS_HELD
import app.winters.octo.playback.BLEND_PASS_NONE
import app.winters.octo.playback.BlendAction
import app.winters.octo.playback.BlendActionKind
import app.winters.octo.playback.BlendArm
import app.winters.octo.playback.BlendCause
import app.winters.octo.playback.BlendDeck
import app.winters.octo.playback.BlendDisarm
import app.winters.octo.playback.BlendEvent
import app.winters.octo.playback.BlendEventKind
import app.winters.octo.playback.BlendInput
import app.winters.octo.playback.BlendSession
import app.winters.octo.playback.BlendState
import app.winters.octo.playback.BlendTiming
import app.winters.octo.playback.CommitState
import app.winters.octo.playback.CommitWord
import app.winters.octo.playback.TransitionPlan
import app.winters.octo.playback.blendPassOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// A blend as the Crossfader registers it: the session's plan, which slot
// plays out and which comes in, and each deck's part of the shaping. The
// Crossfader opens the generation on `commitWord` before it sends it.
class MixBlend(
    val arm: BlendArm,
    val outgoingSlot: Int,
    val incomingSlot: Int,
    val plan: TransitionPlan,
    val outgoing: DeckTransition,
    val incoming: DeckTransition,
)

// A blend event as main hears it.
class MixEvent(
    val kind: BlendEventKind,
    val generation: Long,
    val frame: Long,
    val ms: Double,
    val ms2: Double,
    val cause: BlendCause,
)

// What main hears from the mixer, on main.
interface MixListener {
    fun onBlendEvent(event: MixEvent) {}

    // A gated start that waited for the bound, classified.
    fun onGateBound(record: GateRecord) {}
}

// The debug rig's view of the mixer, called on the mix thread.
interface MixProbe {
    // Frames written into real output `output`, the first carrying mix frame `frame`.
    fun written(output: Int, frame: Long, samples: FloatArray, offset: Int, frames: Int) {}

    // A head read of real output `output`.
    fun head(output: Int, nanos: Long, head: Long, written: Long) {}

    // Something the mixer did, for the log.
    fun note(what: String, frame: Long) {}
}

// The one mix output of a player service. Both decks write through Media3's
// AudioOutputProvider into rings here, and one mix thread sums them into
// the only real output: the blend's shaping, each deck's share, the
// limiter on the sum, and the blend's rules (BlendSession) all run there,
// on the frame. Frames are counted on one mix timeline; a real output's
// written frames carry the mix frames from its `base` on (the mix limiter's
// fixed delay already taken out), and its head is read on that timeline.
//
// Every public call may come from any thread; each runs on the mix thread.
@UnstableApi
class DeckMixer(
    val mixRate: Int,
    val sessionId: Int,
    private val loop: MixLoop,
    private val builderLoop: MixLoop,
    gateLoop: MixLoop,
    private val builder: RealOutputBuilder,
    playbackList: PlaybackList,
    private val toMain: (Runnable) -> Unit,
    private val listener: MixListener,
    private val tuning: MixTuning = MixTuning(),
    private val sdk: Int = Build.VERSION.SDK_INT,
    private val pcmTargetFrames: (Int) -> Int = ::mediaPcmTargetFrames,
    // Route measures kept between real outputs, by device key.
    private val routes: MutableMap<String, RouteMeasure> = HashMap(),
    // Runs on the builder thread once the mixer has released everything:
    // whoever made the mixer's threads ends them here.
    private val onReleased: () -> Unit = {},
) {
    val commitWord = CommitWord()

    @Volatile var probe: MixProbe? = null

    val blockFrames = max(1, mixRate * tuning.blockMs / 1_000)
    private val rampFrames = max(1, (mixRate * tuning.rampMs / 1_000).roundToInt())
    private val unitMs = 1_000.0 / mixRate
    private val gate = DuckGate(playbackList, gateLoop, tuning)

    // Media3's PCM target for the real output at the mix rate.
    private val realTargetFrames = pcmTargetFrames(mixRate)

    // The real output's buffer as built (the target until one is).
    @Volatile private var realBufferFrames = realTargetFrames

    // A ring holds the deck's target, the real buffer, and the largest
    // proposal lead the margin may use.
    @Volatile private var ringCapacity = ringCapacityFor(realTargetFrames)

    // While a blend is registered a deck may fill its ring past plain
    // playback's lead by the proposal lead.
    @Volatile private var blendRegistered = false
    @Volatile private var proposalLeadFrames = 0L

    // Set while the real output's written lead is under its refill point,
    // so a deck's write wakes the mix thread.
    @Volatile private var wantsFrames = true

    // Mix thread state.

    private val decks = arrayOf(DeckState(0), DeckState(1))
    private val timing = BlendTiming(mixRate, blockFrames.toLong(), 0, 0, 0, 0.0, 0.0)
    private val session = BlendSession(commitWord, timing)
    private val action = BlendAction()
    private val event = BlendEvent()
    // The sum is held under the ceiling between its samples too.
    private val limiter = Limiter(2, mixRate, rampedAttack = true, truePeak = true)
    private val latency = limiter.latency
    private var mixPoint = 0L
    private var limiterOut = 0L
    private var windowOn = Long.MAX_VALUE
    private var windowOff = Long.MAX_VALUE
    private var sessionPoint = 0L
    private val blocks = ArrayDeque<Block>()
    private val spareBlocks = ArrayDeque<Block>()
    private val pending = ArrayList<Act>()
    private val log = ArrayList<Act>()
    private val sum = FloatArray(blockFrames * 2)
    private val tmp = FloatArray(blockFrames * 2)
    private val limited = FloatArray(blockFrames * 2)
    private val view = MarkView()
    private var blend: MixBlend? = null
    private var blendGen = -1L
    private var lastA = -1
    private var startPostedMs = Double.NaN
    private var endPostedMs = Double.NaN
    private var startS = -1L
    private var capAt = Long.MAX_VALUE
    private var extensionMs = 0.0
    private var proposeWakeAt = -1L
    private var floorWakeAt = -1L
    private val watch = LongArray(16)
    private var watchCount = 0

    private val mainLag = LagWindow(tuning.lagSpanMs * 1_000_000, tuning.lagFloorMs, tuning.lagExtraMs, tuning.lagSamples)
    private val wakeLate = LagWindow(tuning.lagSpanMs * 1_000_000, tuning.lagFloorMs, tuning.lagExtraMs, tuning.lagSamples)
    private var nextProbeAt = 0L

    // Real outputs: the one written and played; the paused one settling,
    // kept until its replacement is built; a fresh one built for the next
    // start.
    private var cur: Real? = null
    private var held: Real? = null
    private var fresh: Real? = null
    private var realIds = 0
    private var building = false
    private var buildFailedAt = -1L
    private var buildFailingSince = -1L
    private var noPendingSince = -1L
    private var buildsFailed = false
    private var writePoint = 0L
    private var settledHead = -1L
    private var resumeOnset = false
    private var presentedHold = -1L
    private var intentPlaying = true
    private var playerPaused = false
    private var holding = false
    private var playerVolume = 1f
    private var lastHeadMix = 0L
    private var lastPresented = 0L
    private var lastPresentedNanos = 0L
    private var dryAnchorNanos = -1L
    private var fallback: Fallback? = null
    private val ownEvents = LongArray(32)
    private var ownEventCount = 0
    private val otherArrivals = LongArray(32)
    private var otherArrivalCount = 0
    private var quitting = false
    private val ts = RealTimestamp()

    private val wakeTask = Runnable { wake(scheduled = -1L) }
    private var wakeAt = Long.MAX_VALUE
    private val headTask = Runnable { headWake() }
    private var headDue = -1L
    private val settleTask = Runnable { settleRead() }
    private var settleDue = 0L

    // Records

    // One mix block: the mix frames [g0, g0 + n), the frames the limiter
    // let out for it (carrying mix frames from `outStart`), and the state
    // at its start before the actions at g0, so a re-mix can start there.
    private inner class Block {
        var g0 = 0L
        var n = 0
        var outStart = 0L
        var outCount = 0
        val out = FloatArray(blockFrames * 2)
        val state = arrayOf(DeckState(0), DeckState(1))
        val limiterState = Limiter(2, mixRate, rampedAttack = true, truePeak = true)
        var limiterOutAt = 0L
        var windowOnAt = Long.MAX_VALUE
        var windowOffAt = Long.MAX_VALUE
        var extension = 0.0
        var start = -1L
        var cap = Long.MAX_VALUE
    }

    private class Act(val kind: BlendActionKind, var frame: Long, val value: Long, val ms: Double, val gen: Long)

    // A real output and what the mixer knows of it.
    private inner class Real(val out: RealOutput, val id: Int) {
        var base = 0L
        var written = 0L
        var started = false
        var paused = false
        var music = false
        var firstMusicNanos = 0L
        var gateState = GATE_IDLE
        var gateGen = 0L
        var playNanos = 0L
        var signalNanos = 0L
        var onsetLeft = 0
        var rawHead = 0L
        var wraps = 0L
        var head = 0L
        var headNanos = 0L
        var lastMoveNanos = 0L
        var lastBelowNanos = -1L
        var route: String? = null
        var measure = RouteMeasure()
        var learning: HeadLearning? = null
        var thresholdSet = 0
        var lowered = false

        // The head when the start was lowered: put back once it moves on.
        var loweredAt = -1L

        // Since when frames have been written while the head stood (from
        // the first music frame, or the first frame written after the head
        // stopped at its written count), or -1.
        var stillSince = -1L

        // The first head step after music was written (its first music
        // cycle), and whether the head has stepped at all.
        var firstCycleNanos = -1L

        // API 29 and 30: the buffer size is held small until the first head step.
        var smallSize = false
        var dead = false
        var released = false
        var underruns = 0
        var clamping = true
        var stampNanos = 0L
        var stampFrame = -1L
        var signalled = false
        var learnDue = -1L
        val learnTask = Runnable { learnTick(this) }

        val headMix: Long get() = base + head

        fun readHead(now: Long): Long {
            val raw = out.head() and 0xFFFFFFFFL
            // Media3's guard: Android 10 and below can read 0 after a
            // failed Bluetooth connect.
            if (sdk <= 29 && raw == 0L && rawHead > 0 && started) return head
            if (raw < rawHead) wraps++
            rawHead = raw
            val next = raw + (wraps shl 32)
            if (next != head) {
                lastMoveNanos = now
                stillSince = -1
                if (music && firstCycleNanos < 0) firstCycleNanos = now
            }
            head = next
            headNanos = now
            if (head < written) lastBelowNanos = now
            probe?.head(id, now, head, written)
            return head
        }
    }

    // The silent fallback when no fresh output could be built: the paused
    // output played at volume 0 until its held frames and the duck's span
    // have passed, then music again from h.
    private class Fallback(val heldEnd: Long, val silenceEnd: Long, val musicFrom: Long) {
        var restored = false
        var musicStarted = false
    }

    // Public calls

    // What a deck's sink should be given.
    fun provider(slot: Int, labels: DeckTimeQueue?, deckClock: Clock = Clock.DEFAULT): MixOutputProvider =
        MixOutputProvider(this, slot, labels, deckClock)

    fun deckTargetFrames(songRate: Int): Int = pcmTargetFrames(songRate)

    internal fun ringCapacityFrames(): Int = ringCapacity

    // The ring frames a deck may hold, mixed and not: the deck's target and
    // the real buffer, plus the proposal lead while a blend is registered.
    internal fun fillLimitFrames(capacity: Int): Long {
        val plain = realTargetFrames.toLong() + realBufferFrames
        val limit = if (blendRegistered) plain + proposalLeadFrames else plain
        return min(limit, (capacity - 2 * blockFrames).toLong())
    }

    internal fun nanoTime(): Long = loop.nanoTime()

    // How far ahead of the head a start must be decided, in ms of output, as
    // last measured (0 before the first wake).
    val proposalLeadMs: Double get() = proposalLeadFrames * 1_000.0 / mixRate

    // The proposal lead in mix frames, for main's re-plan.
    val proposalLead: Long get() = proposalLeadFrames

    // The Crossfader registers a blend.
    fun arm(blend: MixBlend) = post { onArm(blend) }

    // The listener's pace became `pace` during the blend: the session hears
    // it at the first frame of the outgoing deck that carries it.
    fun paceChanged(generation: Long, pace: Double) = post {
        if (generation == blendGen && pace > 0) pacePending = pace
    }

    private var pacePending = 0.0

    // Main's interrupt, after its compare-and-set on the commit word.
    fun disarm(reason: BlendDisarm, generation: Long) = post { onDisarm(reason, generation) }

    // A deck's player error or stop, heard on main; B's carries the follow-up.
    fun failed(deck: BlendDeck, generation: Long, followUp: Long) = post {
        if (generation == blendGen) {
            session.failed(deck, followUp)
            afterSession(fromMain = true)
        }
    }

    fun followUp(generation: Long, pass: Long) = post {
        // The answer to a pause's interrupt that waits for the paused output
        // to settle goes right after that interrupt.
        if (pendingDisarm != null && pendingDisarmGen == generation) {
            pendingFollowUp = pass
            return@post
        }
        session.followUp(generation, pass)
        afterSession(fromMain = true)
    }

    private var pendingFollowUp = BLEND_NO_FOLLOW

    fun outgoingStopped(generation: Long) = post {
        session.outgoingStopped(generation)
        afterSession(fromMain = false)
        if (generation == blendGen) retireBlend()
    }

    // Main acked the start: fills run on at once.
    fun acked(generation: Long) = post { wake(-1L) }

    // A slot holding the spare, parked until the session starts it.
    fun park(slot: Int, parked: Boolean) = post {
        val d = decks[slot]
        d.parked = parked
        if (!parked && d.role == ROLE_NONE) d.on = true
        wake(-1L)
    }

    // A move the app makes on a deck (a seek, a load, a prepare), sent on
    // the deck's playback looper before the call: the deck's next new
    // output plays what it is told, never continued by song time.
    fun announce(slot: Int) = post { decks[slot].announced = true }

    // The move above was a no-op: an announcement nothing used is cleared.
    fun moveDone(slot: Int) = post {
        val d = decks[slot]
        if (!d.announcedPending) d.announced = false
    }

    // A player stop, an idle state, a prepare or new items: nothing on the
    // slot continues by song time.
    fun resetContinuation(slot: Int) = post { decks[slot].resetPass() }

    // OctoPlayer's pause (and pause-and-hold): the real output pauses at once.
    fun pause(hold: Boolean) = post {
        playerPaused = true
        if (hold) holding = true
        lifecycle(loop.nanoTime())
    }

    // OctoPlayer's intent: whether the person wants music now.
    fun wantPlaying(playing: Boolean) = post {
        intentPlaying = playing
        if (playing) {
            playerPaused = false
            stopped = false
        }
        wake(-1L)
    }

    // OctoPlayer's stop: nothing resumes, so what was kept for a resume
    // goes once nothing is left to play.
    fun stop() = post {
        stopped = true
        lifecycle(loop.nanoTime())
    }

    // A releasing interrupt (a seek, a skip, new items, stop, Cast): the
    // real output pauses and holds only when B's frames are in it.
    fun holdIfHandedOver(generation: Long) = post {
        if (commitWord.stateOf(generation) == CommitState.ACKED) {
            holding = true
            lifecycle(loop.nanoTime())
        }
    }

    // The operation behind a hold ran (posted on the deck's playback looper
    // after it): a hold no release lifted plays on through the fresh output
    // built when it paused, through the gate.
    fun holdDone() = post {
        if (!holding) return@post
        holding = false
        wake(-1L)
    }

    // Another app's music player that the debug rig started at `atNanos`
    // on this mixer's clock, for the gate's bound classifier: a rise after a
    // bound that one explains is never a late listing.
    fun otherMusicArrived(atNanos: Long) {
        synchronized(otherArrivals) {
            otherArrivals[otherArrivalCount % otherArrivals.size] = atNanos
            otherArrivalCount++
        }
    }

    // Devices came or went: each live output's route is read again.
    fun devicesChanged() = post {
        cur?.let { routed(it) }
        fresh?.let { routed(it) }
    }

    // The player-level volume (duck, sleep fade, a controller's level).
    fun setVolume(level: Float) = post {
        val v = if (level.isFinite()) level.coerceIn(0f, 1f) else return@post
        playerVolume = v
        // The silent fallback keeps its output at 0 until its held frames
        // have played; the level is put on when it comes back.
        if (fallback == null) cur?.out?.setVolume(v)
    }

    fun release(): Boolean {
        val done = CountDownLatch(1)
        post {
            quitting = true
            loop.remove(wakeTask)
            loop.remove(headTask)
            loop.remove(settleTask)
            gate.cancel()
            for (r in listOfNotNull(cur, held, fresh)) releaseReal(r)
            cur = null
            held = null
            fresh = null
            done.countDown()
        }
        if (!loop.isCurrent()) done.await(2, TimeUnit.SECONDS)
        builderLoop.post {
            builder.release()
            onReleased()
        }
        return done.count == 0L
    }

    // Deck outputs

    internal fun outputCreated(o: MixAudioOutput) = post { onOutputCreated(o) }

    // A deck's write wakes the mix thread while the real output's lead is
    // under its refill point; a new nudge replaces one still queued.
    internal fun deckWrote(o: MixAudioOutput) {
        if (quitting) return
        // A new output's first frames always wake it: if it plays on by song
        // time (or restarts an earlier pass) that is decided at once.
        val first = !o.nudged
        o.nudged = true
        if (wantsFrames || first) loop.postAt(loop.nanoTime(), wakeTask)
    }

    internal fun deckPlay(o: MixAudioOutput, call: Int) = post { wake(-1L) }

    internal fun deckPause(o: MixAudioOutput, call: Int) = post { onDeckPause(o) }

    internal fun outputReleased(o: MixAudioOutput) = post { onOutputReleased(o) }

    internal fun deckVolume(o: MixAudioOutput) = post { sharesChanged() }

    internal fun flushDeck(o: MixAudioOutput) {
        val done = CountDownLatch(1)
        post {
            val d = decks[o.slot]
            if (d.out === o) d.mixIdx = o.ring.written
            done.countDown()
        }
        if (!loop.isCurrent()) done.await(1, TimeUnit.SECONDS)
    }

    internal fun onReal(block: (RealOutput) -> Unit) = post { cur?.out?.let(block) }

    private var preferred: AudioDeviceInfo? = null

    internal fun preferDevice(device: AudioDeviceInfo?) = post {
        preferred = device
        cur?.out?.setPreferredDevice(device)
        fresh?.out?.setPreferredDevice(device)
    }

    private fun post(block: () -> Unit) {
        if (loop.isCurrent()) block() else loop.post { if (!quitting) block() }
    }

    // Deck events

    private fun onOutputCreated(o: MixAudioOutput) {
        val d = decks[o.slot]
        val old = d.out
        d.out = o
        d.mixIdx = 0
        d.dry = DRY_NORMAL
        d.fadeLeft = 0
        d.rampLeft = 0
        d.watchNanos = loop.nanoTime()
        // An unannounced output on the slot plays on in the pass being
        // presented, by song time; an announced one plays what it is told.
        val told = d.announced || d.announcedPending
        val earlierPresented = d.prevEntry != null && d.passStartMix > lastPresented
        d.continuing = !told && !d.passLive && ((d.passEntry != null && !d.lastSongMs.isNaN()) || earlierPresented)
        if (told) {
            d.lastSongMs = Double.NaN
            // A load or a move: what it plays is new, so an earlier leave
            // is over. A player's own restart keeps a leave going.
            d.leaveLeft = 0
            d.leaveTotal = 0
            forgetHead(d.slot)
        }
        d.announced = false
        d.announcedPending = false
        if (old != null && old !== o && !old.released) d.tail = null
        d.restarting = false
        if (!d.parked && d.role != ROLE_B) d.on = true
        nothingSince = -1L
        wake(-1L)
    }

    private fun onOutputReleased(o: MixAudioOutput) {
        val d = decks[o.slot]
        if (d.out !== o) return
        // The ring's unmixed frames are kept so a Sonic restart can join
        // them; at speed 1 the new output's own frames take over exactly. A
        // next pass held, or not named by the answer, is never among them.
        d.tail = if (o.ring.written > d.mixIdx && !heldAtBoundary(d, o)) Tail(o, d.mixIdx) else null
        d.out = null
        // A playing output released unannounced: the player's own restart,
        // its new output coming. The real output plays on meanwhile.
        d.restarting = !d.announced && o.playing
        if (d.announced) {
            forgetHead(d.slot)
            d.announcedPending = true
            d.announced = false
            d.tail = null
        }
        // A parked B whose start, cut or commit is still to apply plays too.
        val lone = decks.none { it !== d && it.out != null && ((it.on && !it.parked) || startsSoon(it)) }
        // Paused, or resumed with nothing playing out yet: what was mixed
        // for the old place would go into the next output first.
        val paused = held != null || playerPaused || cur?.let { !it.started || it.paused } != false
        probe?.note("released ${o.slot}${if (d.announcedPending) " announced" else ""}${if (lone) " alone" else ""}${if (paused) " paused" else ""}", mixPoint)
        // An announced release (a seek, a hold) of the only playing deck: the
        // frames of the old position still unheard go. While paused or held
        // everything mixed or written past the settled head is mixed again
        // from there; while playing, the output pauses at the release and the
        // new position starts on a fresh output. An output still in its gate
        // (a resume a moment ago) counts as playing: what was mixed for the
        // old position would be its first music.
        if (d.announcedPending && lone) {
            if (paused || holding) {
                if (max(writePoint, mixPoint) > (if (settledHead >= 0) settledHead else lastHeadMix)) clearAll(keep = null)
            } else if (cur?.started == true && (blend == null || session.state == BlendState.IDLE)) {
                clearPlaying()
            }
        } else if (d.announcedPending && holding) {
            // The operation behind a hold moved the deck on screen while the
            // outgoing one is still in the mix: nothing of the old mix is kept
            // for the resume, the outgoing song's frames up to the start
            // included; the moved deck plays its new place.
            clearAll(keep = null)
        }
        // The operation behind a hold released its deck's output: the hold
        // is lifted, the clear above done first.
        if (holding && !d.parked) holding = false
        wake(-1L)
    }

    // Whether `d` is the blend's B with its start, a cut or the commit still
    // waiting to apply.
    private fun startsSoon(d: DeckState): Boolean =
        d.role == ROLE_B && ((d.startAt >= 0 && !d.started) || pending.any {
            it.gen == blendGen && (it.kind == BlendActionKind.START_B || it.kind == BlendActionKind.CUT || it.kind == BlendActionKind.COMMIT_B)
        })

    // A lone deck's announced move while it plays: the output pauses and
    // goes, nothing mixed for the old position is written, and the new
    // position's frames start a fresh output through the gate at once.
    private fun clearPlaying() {
        val r = cur ?: return
        r.out.pause()
        r.paused = true
        noteOwn(r)
        gate.cancel()
        releaseReal(r)
        cur = null
        lastPresented = max(lastPresented, min(r.headMix, writePoint))
        while (blocks.isNotEmpty()) recycle(blocks.removeLast())
        pending.clear()
        limiter.reset()
        limiterOut = mixPoint
        writePoint = mixPoint
        windowOn = Long.MAX_VALUE
        windowOff = Long.MAX_VALUE
        session.cleared(mixPoint)
        buildFresh()
        probe?.note("cleared", mixPoint)
    }

    private fun onDeckPause(o: MixAudioOutput) {
        val d = decks[o.slot]
        if (d.out !== o) return
        val left = o.ring.written > d.mixIdx
        if (left && d.role != ROLE_NONE && blendGen >= 0 && session.state != BlendState.IDLE) {
            val deck = if (d.role == ROLE_A) BlendDeck.A else BlendDeck.B
            if (session.state == BlendState.ARMED) {
                // In the run-up a deck's own pause disarms; the output pauses
                // only if no other deck plays on (a parked spare does not).
                postPaused(deck, writePoint)
            } else {
                // In the mixed stretch: the output pauses at once and the
                // session disarms by the phase rule from its settled head.
                pauseReal(loop.nanoTime(), willResume = true)
                if (held != null && settledHead < 0) pendingPause = deck else postPaused(deck, writePoint)
            }
        }
        lifecycle(loop.nanoTime())
    }

    // A deck's pause, taken by the session at once even while nothing is
    // mixed: it is posted just before the point the session has reached,
    // since the session takes only inputs before the point it advances to.
    private fun postPaused(deck: BlendDeck, settled: Long) {
        if (session.state == BlendState.IDLE) return
        val at = max(mixPoint, sessionPoint)
        postInput(deck, BlendInput.PAUSED, max(0L, at - 1), settled)
        advanceSession(at)
    }

    private var pendingPause: BlendDeck? = null

    private fun sharesChanged() {
        var most = 0f
        for (d in decks) d.out?.let { if (d.on && !d.parked) most = max(most, it.volume) }
        for (d in decks) {
            val v = d.out?.volume ?: 1f
            val target = if (most <= 0f || !v.isFinite()) 1f else (v / most).coerceIn(0f, 1f)
            if (target != d.shareTo) {
                d.shareFrom = d.share
                d.shareTo = target
                d.shareLeft = rampFrames
            }
        }
    }

    // Blend registration

    private fun onArm(b: MixBlend) {
        if (blend != null) retireBlend()
        blend = b
        blendGen = b.arm.generation
        blendRegistered = true
        lastA = b.outgoingSlot
        startPostedMs = Double.NaN
        endPostedMs = Double.NaN
        startS = -1L
        capAt = Long.MAX_VALUE
        extensionMs = 0.0
        val a = decks[b.outgoingSlot]
        val bd = decks[b.incomingSlot]
        a.role = ROLE_A
        bd.role = ROLE_B
        bd.parked = true
        bd.on = false
        bd.readyPosted = -1
        // The parked spare starts at its own level, nothing of an earlier
        // part's ramps or fades on it.
        bd.clearLevel()
        a.arm(b.outgoing, mixRate)
        bd.arm(b.incoming, mixRate)
        session.arm(b.arm)
        // The outgoing deck's sound has already moved on from the song the
        // plan was made for: it plays on, cancelled as its own advance.
        val mixing = a.passEntry
        if (a.out != null && mixing != null && b.outgoing.entryId != null && mixing != b.outgoing.entryId) {
            val at = max(mixPoint, sessionPoint)
            session.post(BlendDeck.A, BlendInput.MOVED_ON, at)
            sessionPoint = at + 1
        }
        // The head rules decide now, before a frame of A is mixed with the
        // new shaping: a start A's mixing has already passed, or one inside
        // the proposal lead, is moved or refused here.
        sessionPoint = max(sessionPoint, mixPoint)
        session.advance(sessionPoint, lastHeadMix, outgoingMs())
        afterSession(fromMain = true)
        wake(-1L)
    }

    private fun onDisarm(given: BlendDisarm, generation: Long) {
        if (generation != blendGen) return
        // Under a hold the output is paused, so the interrupt takes the
        // pause's rule: what is not heard is mixed again from the settled
        // head.
        val reason = if (holding) BlendDisarm.PAUSE else given
        // A pause's interrupt takes its rule from the settled head, so it
        // waits for the paused output to settle.
        if (reason == BlendDisarm.PAUSE && held != null && settledHead < 0) {
            pendingDisarm = reason
            pendingDisarmGen = generation
            return
        }
        val at = firstRerunnable()
        session.disarm(reason, at, if (settledHead >= 0) settledHead else at)
        afterSession(fromMain = true)
        if (session.state == BlendState.IDLE) retireBlend()
    }

    private fun retireBlend() {
        val b = blend ?: return
        blend = null
        blendRegistered = false
        pacePending = 0.0
        val a = decks[b.outgoingSlot]
        val bd = decks[b.incomingSlot]
        if (a.role == ROLE_A) a.role = ROLE_NONE
        if (bd.role == ROLE_B) {
            bd.role = ROLE_NONE
            if (bd.startAt < 0 && !bd.started) {
                bd.parked = true
                bd.on = false
            }
        }
        capAt = Long.MAX_VALUE
        proposeWakeAt = -1
        floorWakeAt = -1
    }

    // The session

    // Takes every action and event the session has, applying the actions:
    // those for a frame not mixed yet wait for it; an undone start or a
    // main interrupt behind the mix point mixes again from there; the rest
    // apply at the mix point.
    private fun afterSession(fromMain: Boolean) {
        var rerun = Long.MAX_VALUE
        val taken = ArrayList<Act>()
        var remix: Act? = null
        while (session.takeAction(action)) {
            val a = Act(action.kind, action.frame, action.value, action.ms, blendGen)
            taken += a
            if (a.kind == BlendActionKind.REMIX) remix = a
            if (a.frame < mixPoint && (a.kind == BlendActionKind.UNDO_START || fromMain)) rerun = min(rerun, a.frame)
            if (a.frame >= lastHeadMix) addWatch(a.frame)
        }
        when {
            remix != null -> {
                // While paused: mixed again from the settled head by the
                // actions that follow, and at once as far as A's part goes
                // (to S and its leave), so the kept range lives in the block
                // records whatever happens to A's deck output while paused.
                remixPaused(remix.frame)
                var through = remix.frame
                for (a in taken) {
                    if (a === remix) continue
                    schedule(a)
                    if (a.kind == BlendActionKind.CUT || a.kind == BlendActionKind.A_LEAVE) through = max(through, a.frame + a.value)
                }
                keptA = blend?.outgoingSlot ?: lastA
                premix(through + blockFrames)
            }
            rerun != Long.MAX_VALUE -> rerunFrom(max(rerun, firstRerunnable()), taken)
            else -> for (a in taken) schedule(a)
        }
        postEvents()
        retireIfEnded()
    }

    // A blend the session ended on its own before the handover (B not
    // ready, A moved on, a floor or late cancel, a pause in the run-up, a
    // failure) goes once its last action has applied, so seeks and skips
    // are a lone deck's again. One handed over goes when main stops its old
    // deck, which keeps A's frames past its end out of the mix until then.
    private fun retireIfEnded() {
        if (blend == null || session.state != BlendState.IDLE) return
        if (commitWord.stateOf(blendGen) == CommitState.ACKED) return
        if (pending.any { it.gen == blendGen }) return
        retireBlend()
    }

    // The slot whose frames up to S a re-mix while paused keeps; a later
    // clear while paused drops them (and that deck from the mix).
    private var keptA = -1

    // Set while mixing the kept range of a paused output: the decks' ring
    // frames are mixed whether or not their sinks play.
    private var premixing = false

    private fun premix(to: Long) {
        probe?.note("premix to $to", mixPoint)
        premixing = true
        try {
            while (mixPoint < to) {
                val b = mixBlock() ?: break
                blocks.addLast(b)
                afterBlock(b)
            }
        } finally {
            premixing = false
        }
    }

    private fun schedule(a: Act) {
        // What plays after a held end is an answer, not a frame: it applies
        // at once, also when a re-run put the mix back before that end.
        if (a.frame <= mixPoint || a.kind == BlendActionKind.A_FOLLOW) {
            a.frame = mixPoint
            apply(a)
        } else {
            var i = pending.size
            while (i > 0 && pending[i - 1].frame > a.frame) i--
            pending.add(i, a)
        }
    }

    private fun postEvents() {
        while (session.takeEvent(lastHeadMix, event)) {
            val e = MixEvent(event.kind, event.generation, event.frame, event.ms, event.ms2, event.cause)
            val sent = loop.nanoTime()
            toMain(Runnable {
                val lag = (loop.nanoTime() - sent) / 1e6
                loop.post { mainLag.add(loop.nanoTime(), lag) }
                listener.onBlendEvent(e)
            })
        }
    }

    // The session's timing from what is measured now.
    private fun updateTiming(now: Long) {
        val q = quantumFrames()
        val jitter = (measure()?.let { it.largestStep - it.quantum } ?: 0L) + 1
        val marginMs = min(tuning.marginCapMs, mainLag.value(now) + wakeLate.value(now)) + (q + jitter) * unitMs
        val margin = (min(marginMs, tuning.marginCapMs) * mixRate / 1_000).toLong()
        val buffer = realBufferFrames.toLong()
        val floorFrames = buffer - buffer / 4
        val wakeFrames = buffer / 4
        timing.commitAtFrames = floorFrames
        timing.proposeAtFrames = floorFrames + margin
        timing.proposalLeadFrames = floorFrames + margin + wakeFrames + blockFrames
        timing.placementHorizonFrames = blockFrames.toLong()
        timing.ringLeadMs = realTargetFrames * unitMs
        timing.sinkBufferMs = realTargetFrames * unitMs
        proposalLeadFrames = timing.proposalLeadFrames
    }

    private fun quantumFrames(): Long = measure()?.takeIf { it.known }?.quantum ?: (tuning.defaultQuantumMs * mixRate / 1_000).toLong()

    private fun measure(): RouteMeasure? = cur?.measure ?: held?.measure

    // A's song time of its next frame to mix.
    private fun outgoingMs(): Double {
        val b = blend ?: return Double.NaN
        val a = decks[b.outgoingSlot]
        val o = a.out ?: return Double.NaN
        o.ring.marks.view(view)
        return view.songAt(a.mixIdx)
    }

    private fun advanceSession(to: Long) {
        sessionPoint = max(sessionPoint, to)
        session.advance(sessionPoint, lastHeadMix, outgoingMs())
        afterSession(fromMain = false)
    }

    private fun postInput(deck: BlendDeck, input: BlendInput, frame: Long, value: Long = 0) {
        if (frame < sessionPoint && input != BlendInput.PAUSED) return
        session.post(deck, input, frame, value)
        if (frame >= lastHeadMix) addWatch(frame)
    }

    private fun addWatch(frame: Long) {
        if (watchCount == watch.size) return
        watch[watchCount++] = frame
    }

    // Actions

    private fun apply(a: Act) {
        log += a
        val b = blend
        val aSlot = b?.outgoingSlot ?: lastA
        val bSlot = b?.incomingSlot ?: -1
        val ad = if (aSlot >= 0) decks[aSlot] else null
        val bd = if (bSlot >= 0) decks[bSlot] else null
        when (a.kind) {
            BlendActionKind.START_B -> if (bd != null) {
                bd.startAt = a.frame
                startS = a.frame
                capAt = a.frame
            }
            BlendActionKind.COMMIT_B -> capAt = Long.MAX_VALUE
            BlendActionKind.UNDO_START -> {
                bd?.let {
                    it.startAt = -1
                    it.started = false
                    it.parked = true
                    it.on = false
                    it.shaper?.disarm(0)
                }
                ad?.shaper?.disarm(0)
                ad?.transition = null
                capAt = Long.MAX_VALUE
                startS = -1
            }
            BlendActionKind.FADE_OUT_B -> bd?.let { leave(it, a.value.toInt()) }
            BlendActionKind.A_DISARM -> {
                ad?.transition = null
                ad?.shaper?.disarm(a.value)
            }
            BlendActionKind.A_LEAVE -> ad?.let { leave(it, a.value.toInt()) }
            BlendActionKind.A_END -> ad?.endDrop = true
            // A hold the answer already came for is never put on again, nor
            // one of a generation a newer arm replaced.
            BlendActionKind.A_HOLD_NEXT -> ad?.let { if (it.follow == BLEND_NO_FOLLOW && a.gen == blendGen) it.holdNext = true }
            BlendActionKind.A_FOLLOW -> ad?.let { d ->
                d.holdNext = false
                d.follow = a.value
            }
            BlendActionKind.B_DISARM -> bd?.let {
                it.transition = null
                it.shaper?.disarm(a.value)
                if (it.held) {
                    it.held = false
                    it.rampLeft = rampFrames
                    it.rampTotal = rampFrames
                }
            }
            BlendActionKind.HOLD_B -> bd?.held = true
            BlendActionKind.PLACE_B -> bd?.let {
                it.held = false
                it.rampLeft = rampFrames
                it.rampTotal = rampFrames
            }
            BlendActionKind.CUT -> {
                ad?.let { leave(it, a.value.toInt()) }
                bd?.let {
                    it.held = false
                    it.transition = null
                    it.shaper?.disarm(0)
                    if (!it.started) it.startAt = a.frame
                    it.rampLeft = a.value.toInt()
                    it.rampTotal = max(1, a.value.toInt())
                }
            }
            BlendActionKind.FREEZE_A -> ad?.let {
                it.frozen = true
                it.frozenMs = it.blendMsNow
            }
            BlendActionKind.UNFREEZE_A -> ad?.let {
                it.frozen = false
                it.offsetMs = a.ms
                extensionMs = a.ms
            }
            BlendActionKind.MOVE_START -> {
                val blendNow = blend
                if (blendNow != null && ad != null) {
                    val moved = blendNow.plan.copy(startMs = a.ms.roundToLong())
                    ad.transition = DeckTransition(blendNow.outgoing.entryId, moved, incoming = false, stepped = blendNow.outgoing.stepped, gateDb = blendNow.outgoing.gateDb)
                    startPostedMs = Double.NaN
                    endPostedMs = Double.NaN
                }
            }
            BlendActionKind.LIMITER_ON -> {
                windowOn = a.frame
                windowOff = Long.MAX_VALUE
            }
            BlendActionKind.LIMITER_OFF -> windowOff = a.frame
            BlendActionKind.REMIX -> remixPaused(a.frame)
        }
    }

    // A leave ramps the deck out over `frames` as its frames are mixed; a
    // deck with no frame left to mix stops at once, so no leave waits for
    // frames that may come only after the slot has another part. A dry deck
    // counts as having none: what it holds would come back only to blip
    // under the ramp.
    private fun leave(d: DeckState, frames: Int) {
        val o = d.out
        val left = if (o != null) d.dry != DRY_DRY && o.ring.written > d.mixIdx else d.tail?.let { it.out.ring.written > it.at } == true
        probe?.note(if (left) "leave ${d.slot} over $frames" else "leave ${d.slot} at once", mixPoint)
        if (left) d.leave(frames) else d.stop()
    }

    // Mixing again

    // The earliest mix frame a re-mix may start from: nothing written is
    // ever taken back, so the first block whose frames are all unwritten.
    private fun firstRerunnable(): Long {
        for (b in blocks) if (b.g0 >= writePoint) return b.g0
        return mixPoint
    }

    // Mixes again from block boundary `from`: the state there comes back,
    // the actions logged from there are applied again with `extra` after
    // them, and mixing goes on. Frames before `from` are left as written.
    private fun rerunFrom(from: Long, extra: List<Act>) {
        val k = blocks.indexOfFirst { it.g0 >= from }
        if (k < 0) {
            for (a in extra) schedule(a)
            return
        }
        val first = blocks[k]
        val start = first.g0
        restore(first)
        while (blocks.size > k) recycle(blocks.removeLast())
        for (d in decks) d.out?.clipRuns(start)
        val again = log.filter { it.frame >= start }
        log.removeAll { it.frame >= start }
        pending.clear()
        val undone = extra.any { it.kind == BlendActionKind.UNDO_START }
        for (a in again) {
            if (undone && (a.kind == BlendActionKind.START_B || a.kind == BlendActionKind.LIMITER_ON || a.kind == BlendActionKind.COMMIT_B)) continue
            schedule(Act(a.kind, a.frame, a.value, a.ms, a.gen))
        }
        for (a in extra) schedule(Act(a.kind, max(a.frame, start), a.value, a.ms, a.gen))
        // Nothing written is taken back: the events of the stretch mixed
        // again still post as their frames pass the head.
        probe?.note("remix", start)
    }

    private fun restore(b: Block) {
        for (i in 0..1) decks[i].copyFrom(b.state[i])
        limiter.copyFrom(b.limiterState)
        limiterOut = b.limiterOutAt
        windowOn = b.windowOnAt
        windowOff = b.windowOffAt
        extensionMs = b.extension
        startS = b.start
        capAt = b.cap
        mixPoint = b.g0
    }

    // While paused: the output from `from` (the settled head) is mixed again
    // by the actions that follow, each remaining deck from its raw frames:
    // its blend stage brought to `from` from the snapshot of the block
    // holding it, the limiter reset to unity and primed from the decks' own
    // frames, so the resume has no step.
    private fun remixPaused(from: Long) {
        val k = blocks.indexOfLast { it.g0 <= from }
        if (k < 0) return
        val b = blocks[k]
        for (i in 0..1) {
            val d = decks[i]
            val live = d.out
            if (live !== b.state[i].out) {
                // The slot's output went or changed while paused: the slot
                // keeps its own state (parked or not, its role) and plays its
                // new output from its first frame, at its own level.
                live?.clipRuns(from)
                d.mixIdx = 0
                d.tail = null
                d.join = null
                d.dropBlend()
                continue
            }
            d.copyFrom(b.state[i])
            val o = d.out ?: continue
            val ringAtFrom = o.ringAt(from)
            val ringAtBlock = d.mixIdx
            if (ringAtFrom > ringAtBlock) runShaperForward(d, o, ringAtBlock, ringAtFrom)
            d.mixIdx = ringAtFrom
            d.out?.clipRuns(from)
        }
        while (blocks.size > k) recycle(blocks.removeLast())
        log.removeAll { it.frame >= from }
        pending.clear()
        limiter.reset()
        limiterOut = from
        mixPoint = from
        writePoint = from
        windowOn = Long.MAX_VALUE
        windowOff = Long.MAX_VALUE
        session.cleared(from)
        probe?.note("clear", from)
    }

    // The restart's re-mix from the settled head: the slot plays its new
    // output on from the frame after the last one heard of the earlier pass.
    private fun restartFromHead(d: DeckState, out: MixAudioOutput) {
        val from = settledHead
        val k = blocks.indexOfLast { it.g0 <= from }
        val old = if (k >= 0) blocks[k].state[d.slot].out else null
        var lastHeard = Double.NaN
        var entry: String? = d.prevEntry
        if (old != null) {
            old.ring.marks.view(view)
            val ring = old.ringAt(from) - 1
            lastHeard = view.songAt(ring)
            val m = view.covering(ring)
            if (m >= 0) entry = view.entry(m)
        }
        remixPaused(from)
        // A second restart while this one settled folded into it: the
        // slot's newest output plays on.
        d.out = d.out ?: out
        d.mixIdx = 0
        d.tail = null
        d.join = null
        d.passEntry = entry
        d.lastSongMs = lastHeard
        d.continuing = !lastHeard.isNaN()
        // The later pass is gone: the pass heard is the one going on.
        d.prevEntry = null
        d.passStartMix = -1
    }

    // Runs a deck's blend stage over its raw frames [from, to) without
    // mixing them, so it stands where those frames left it.
    private fun runShaperForward(d: DeckState, o: MixAudioOutput, from: Long, to: Long) {
        val s = d.shaper ?: return
        o.ring.marks.view(view)
        var at = from
        while (at < to) {
            val n = min(blockFrames.toLong(), to - at).toInt()
            copyRaw(o.ring, at, n, tmp)
            val m = view.covering(at)
            val song = if (m >= 0) view.songMs(m) + (at - view.ringAt(m)) * view.msPerFrame(m) else Double.NaN
            if (!song.isNaN()) {
                s.arm(d.transition, d.blendMs(song))
                s.process(tmp, n, d.blendMs(song), if (d.frozen) 0.0 else view.msPerFrame(m))
            }
            at += n
        }
    }

    // A clear while paused: every unheard frame goes and is mixed again from
    // the settled head, keeping `keep` (or nothing).
    private fun clearAll(keep: DeckState?) {
        if (held != null && settledHead < 0) {
            clearAfterSettle = true
            return
        }
        val from = if (settledHead >= 0) settledHead else lastHeadMix
        remixPaused(from)
        for (d in decks) if (d !== keep && d.out == null) d.tail = null
        // A re-mix kept for the resume (A's frames up to S) goes too.
        if (keptA >= 0) {
            val a = decks[keptA]
            if (a !== keep) {
                a.stop()
                a.tail = null
            }
            keptA = -1
        }
        cur?.let { r ->
            if (r.written > 0) {
                r.out.pause()
                r.paused = true
                releaseReal(r)
                cur = null
            }
        }
    }

    // The mix loop

    private fun wake(scheduled: Long) {
        if (quitting) return
        loop.remove(wakeTask)
        wakeAt = Long.MAX_VALUE
        val now = loop.nanoTime()
        if (scheduled >= 0) wakeLate.add(now, (now - scheduled) / 1e6)
        readHeads(now)
        updateTiming(now)
        lifecycle(now)
        continueAtOnce()
        fill(now)
        if (blend != null || session.state != BlendState.IDLE) advanceSession(max(mixPoint, sessionPoint))
        dryRule(now)
        publishPositions(now)
        freeRings()
        watchdog(now)
        probeMain(now)
        scheduleNext(now)
    }

    private fun readHeads(now: Long) {
        val r = cur ?: return
        if (!r.started || r.released) return
        val before = r.head
        val beforeNanos = r.headNanos
        r.readHead(now)
        if (jumpedToWritten(r, before, beforeNanos, now)) {
            // A track restored after a route change drops what it held
            // unplayed and reads its written count. Those frames are written
            // again from the read before the jump, with the onset ramp, so
            // nothing is lost and only stepped reads reach the session: a
            // start near it takes the late-start rule, never the floor.
            val from = r.base + before
            probe?.note("head jump ${r.head - before}", from)
            r.base = from - r.head
            writePoint = from
            r.onsetLeft = rampFrames
        }
        lastHeadMix = r.headMix
        // A rebuilt output that plays on is no longer counted as stuck.
        if (r.head != before) rebuiltStuck = false
        if (r.out.isStalled()) {
            rebuild(r, "stalled")
            return
        }
        // Android 11 and later: a head that has not moved for the stuck span
        // while the output plays with data (timed from its last step, its
        // first music, or the first frame written after it stopped).
        if (sdk >= 30 && !r.paused && r.gateState == GATE_OPEN && r.music && r.head < r.written &&
            now - maxOf(r.lastMoveNanos, r.firstMusicNanos, r.stillSince) >= tuning.stuckMs * 1_000_000
        ) {
            rebuild(r, "stuck")
            return
        }
        learn(r, now, -1L)
    }

    // Whether the head read at `now` jumped to the written count past what
    // the cycles since the read before could play.
    private fun jumpedToWritten(r: Real, before: Long, beforeNanos: Long, now: Long): Boolean {
        if (r.head < r.written || before <= 0) return false
        val step = if (r.measure.known) r.measure.largestStep else quantumFrames()
        val interval = max(1L, changeIntervalNanos(r))
        val cycles = max(1L, (now - beforeNanos + interval) / interval)
        return r.head - before > (cycles + 1) * step
    }

    // Mixes what the real output can take (and, while a blend's start is
    // not handed over, ahead of it far enough to decide the start), then
    // writes it.
    private fun fill(now: Long) {
        val r = cur
        // A fresh output's first music waits for enough of it to play on.
        val writable = r != null && r.gateState == GATE_OPEN && !r.paused && !r.released && (r.music || firstFillReady())
        val headNow = if (r != null && r.started) r.headMix else writePoint
        val writeTo = if (writable) headNow + realBufferFrames else writePoint
        var target = writeTo
        if (blend != null && session.state != BlendState.IDLE && commitWord.stateOf(blendGen) != CommitState.ACKED) {
            // Far enough ahead that a start found anywhere since the last
            // wake is still the proposal lead from the head.
            val ahead = headNow + timing.proposalLeadFrames + realBufferFrames / 4 + 3 * blockFrames
            target = max(target, if (startS >= 0) min(ahead, startS + blockFrames) else ahead)
        }
        if (playerPaused || holding || held != null) target = min(target, max(writePoint, mixPoint))
        while (limiterOut < target) {
            // A block is mixed only while every playing deck already holds it
            // and a dry-out ramp, or, up to what the real output takes now,
            // once the output's lead has run down to what a short deck may
            // wait for: running into a ring its deck is still filling would
            // fade the deck out for nothing.
            val wait = shortWaitFrames()
            if (wait >= 0 && (mixPoint >= writeTo || limiterOut - headNow > wait)) break
            val b = mixBlock() ?: break
            blocks.addLast(b)
            afterBlock(b)
        }
        if (writable && r != null) write(r, writeTo, now)
        wantsFrames = !writable || r == null || r.written - r.head < realBufferFrames - realBufferFrames / 4
    }

    // How low the output's lead may run while a playing deck is short of its
    // next block and a dry-out ramp, or -1 when none is. Beside another
    // playing deck that holds its block, only to the refill floor: the
    // healthy deck never plays from a thin lead, and the short one dries
    // with its fade. Alone, to one cycle and a block of the route, so a deck
    // fed at the rate it plays is not faded out and back in at every wake.
    private fun shortWaitFrames(): Long {
        var short = false
        var ready = false
        for (d in decks) {
            val o = d.out ?: continue
            if (!contributes(d) || !o.playing || d.dry != DRY_NORMAL) continue
            if (o.ended || o.ring.written - d.mixIdx >= blockFrames + rampFrames) ready = true else short = true
        }
        return when {
            !short -> -1L
            ready -> (realBufferFrames - realBufferFrames / 4).toLong()
            else -> lowWaterFrames()
        }
    }

    // The output's start threshold as set, or as it will be set: one head
    // quantum plus a block, or the whole buffer before the route's quantum
    // is known.
    private fun startThresholdFrames(): Long {
        val r = cur ?: fresh
        if (r != null && r.thresholdSet > 0) return r.thresholdSet.toLong()
        val m = r?.measure ?: measure()
        return if (m != null && m.known) m.quantum + blockFrames else realBufferFrames.toLong()
    }

    // What a deck's ring holds before a dry deck comes back, and before a
    // fresh output's first music: the start threshold plus one fill wake,
    // within what the ring may hold.
    private fun refillFrames(d: DeckState): Long {
        val o = d.out ?: return 0
        return min(startThresholdFrames() + realBufferFrames / 4, fillLimitFrames(o.ring.capacity) - blockFrames)
    }

    // Whether the deck's ring has stopped filling short of its refill
    // amount: nothing written for one fill wake. What it holds then plays,
    // so its position reaches its last frame and its player rebuffers,
    // instead of holding it back for good.
    private fun stoppedFilling(d: DeckState, o: MixAudioOutput): Boolean {
        val now = loop.nanoTime()
        val written = o.ring.written
        if (d.seenOut !== o || d.seenWritten != written) {
            d.seenOut = o
            d.seenWritten = written
            d.grewAt = now
            return false
        }
        return now - d.grewAt >= realBufferFrames / 4 * 1_000_000_000L / mixRate
    }

    // The written lead under which a short ring is mixed anyway: one cycle
    // and a block of the route, or a quarter of the buffer before its
    // quantum is known.
    private fun lowWaterFrames(): Long {
        val m = measure()
        return if (m != null && m.known) m.quantum + blockFrames else (realBufferFrames / 4).toLong()
    }

    // A fresh output takes its first music once every playing deck holds
    // its refill amount past what is mixed already, or has ended: a deck
    // decoding slower than it plays delays the start instead of dipping
    // just after it.
    private fun firstFillReady(): Boolean {
        if (fallback != null) return true
        val mixedEnd = blocks.lastOrNull()?.let { it.outStart + it.outCount } ?: writePoint
        val mixed = max(0L, mixedEnd - writePoint)
        for (d in decks) {
            val o = d.out ?: continue
            if (!contributes(d) || !o.playing || o.ended || d.dry == DRY_DRY) continue
            if (mixed + (o.ring.written - d.mixIdx) < refillFrames(d) && !stoppedFilling(d, o)) return false
        }
        return true
    }

    // Writes stored block frames into `r` from the write point up to
    // `upTo` (and never at or past the cap while the start is not acked).
    private fun write(r: Real, upTo: Long, now: Long) {
        var limit = upTo
        if (capAt != Long.MAX_VALUE && commitWord.stateOf(blendGen) != CommitState.ACKED) limit = min(limit, capAt)
        if (fallback != null && !writeFallback(r)) return
        if (fallback != null) limit = min(limit, r.base + r.head + realBufferFrames)
        for (b in blocks) {
            if (writePoint >= limit) break
            val end = b.outStart + b.outCount
            if (end <= writePoint || b.outCount == 0) continue
            if (b.outStart > writePoint) break
            val from = (writePoint - b.outStart).toInt()
            val n = (min(end, limit) - writePoint).toInt()
            if (n <= 0) break
            if (r.onsetLeft > 0) onset(r, b.out, from, n)
            // Frames written while the head stands at the written count (the
            // first music, or after an underrun) start the head-still timer.
            val stood = r.head >= r.written
            val took = try {
                r.out.write(b.out, from, n)
            } catch (e: RealOutputDead) {
                rebuild(r, "dead ${e.code}")
                return
            }
            if (took > 0) {
                if (stood && r.stillSince < 0) r.stillSince = now
                if (!r.music) {
                    r.music = true
                    r.firstMusicNanos = now
                    r.learning = null
                }
                probe?.written(r.id, writePoint, b.out, from, took)
                r.written += took
                writePoint += took
            }
            if (took < n) break
        }
        lowerStartWhenStill(r, now)
    }

    // The 20 ms ramp-in of a fresh output whose first frame continues a
    // stream, applied to the frames as they are written.
    private fun onset(r: Real, out: FloatArray, from: Int, n: Int) {
        for (j in 0 until n) {
            if (r.onsetLeft <= 0) break
            val done = rampFrames - r.onsetLeft
            val g = done.toFloat() / rampFrames
            out[(from + j) * 2] *= g
            out[(from + j) * 2 + 1] *= g
            r.onsetLeft--
        }
    }

    // One block: the actions due at the mix point, then each deck's next
    // frames, the sum, and the limiter. Ends early at the next action, at a
    // pass boundary of the outgoing deck, and where every deck runs out.
    private fun mixBlock(): Block? {
        while (pending.isNotEmpty() && pending[0].frame <= mixPoint) apply(pending.removeAt(0))
        var n = blockFrames
        if (pending.isNotEmpty()) n = min(n.toLong(), pending[0].frame - mixPoint).toInt()
        val boundary = passBoundaryAhead()
        if (boundary in (mixPoint + 1)..(mixPoint + n)) n = (boundary - mixPoint).toInt()
        if (boundary == mixPoint) {
            onPassBoundary()
            while (pending.isNotEmpty() && pending[0].frame <= mixPoint) apply(pending.removeAt(0))
        }
        if (n <= 0) n = blockFrames
        val b = obtain()
        b.g0 = mixPoint
        for (i in 0..1) b.state[i].copyFrom(decks[i])
        b.limiterState.copyFrom(limiter)
        b.limiterOutAt = limiterOut
        b.windowOnAt = windowOn
        b.windowOffAt = windowOff
        b.extension = extensionMs
        b.start = startS
        b.cap = capAt
        java.util.Arrays.fill(sum, 0, n * 2, 0f)
        var reach = 0
        var anyOn = false
        for (i in 0..1) {
            val d = decks[i]
            startIfDue(d)
            val took = take(d, mixPoint, n)
            if (took > reach) reach = took
            if (contributes(d)) anyOn = true
        }
        if (reach == 0) {
            // Nothing to mix: the limiter's held frames go out once every
            // deck has stopped.
            recycle(b)
            if (!anyOn || decks.all { !hasFrames(it) }) drainLimiter()
            return null
        }
        if (reach < n && decks.none { hasFrames(it) }) n = reach
        b.n = n
        b.outStart = limiterOut
        b.outCount = limit(n, b.out)
        mixPoint += n
        return b
    }

    private fun drainLimiter() {
        if (limiterOut >= mixPoint) return
        val b = obtain()
        b.g0 = mixPoint
        for (i in 0..1) b.state[i].copyFrom(decks[i])
        b.limiterState.copyFrom(limiter)
        b.limiterOutAt = limiterOut
        b.windowOnAt = windowOn
        b.windowOffAt = windowOff
        b.extension = extensionMs
        b.start = startS
        b.cap = capAt
        b.n = 0
        b.outStart = limiterOut
        val tail = FloatArray(latency * 2)
        val got = limiter.drain(tail)
        System.arraycopy(tail, 0, b.out, 0, got * 2)
        b.outCount = got
        limiterOut += got
        blocks.addLast(b)
    }

    // The limiter over `n` summed frames, limiting only the frames inside
    // the blend's window: it is switched as each frame it lets out crosses
    // the window's edges.
    private fun limit(n: Int, into: FloatArray): Int {
        var at = 0
        var outAt = 0
        while (at < n) {
            val emitting = limiterOut
            val on = emitting >= windowOn && emitting < windowOff
            limiter.enabled = on
            val edge = when {
                emitting < windowOn -> windowOn
                emitting < windowOff -> windowOff
                else -> Long.MAX_VALUE
            }
            val primed = limiterOut + latency <= mixPoint + at
            val chunk = if (!primed || edge == Long.MAX_VALUE) n - at else min((n - at).toLong(), max(1L, edge - emitting)).toInt()
            System.arraycopy(sum, at * 2, tmp, 0, chunk * 2)
            val got = limiter.process(tmp, chunk, limited)
            System.arraycopy(limited, 0, into, outAt * 2, got * 2)
            outAt += got
            limiterOut += got
            at += chunk
        }
        return outAt
    }

    private fun contributes(d: DeckState): Boolean = d.out != null && d.on && !d.parked && !d.held

    // Whether the deck has frames the next block will mix: a dry deck only
    // once it holds enough to come back in.
    private fun hasFrames(d: DeckState): Boolean {
        val o = d.out ?: return false
        if (!contributes(d) || (!o.playing && !premixing)) return false
        val avail = o.ring.written - d.mixIdx
        if (d.dry == DRY_DRY) return !d.holdNext && (avail >= refillFrames(d) || (avail > 0 && (o.ended || stoppedFilling(d, o))))
        return avail > 0 && !heldAtBoundary(d, o)
    }

    // B's start at its frame: its first frame at or after entry is mixed
    // there (at its blend gain), the frames before dropped and counted heard.
    private fun startIfDue(d: DeckState) {
        if (d.startAt < 0 || d.startAt != mixPoint || d.started) return
        val o = d.out ?: return
        val first = d.startRing
        if (first >= d.mixIdx) {
            o.addDrop(mixPoint, d.mixIdx, first - d.mixIdx)
            d.mixIdx = first
        }
        d.started = true
        d.parked = false
        d.on = true
        d.dry = DRY_NORMAL
        d.startAt = -1
        // A cut's ramp in, set with the start, is kept; no leave or fade is.
        d.leaveLeft = 0
        d.leaveTotal = 0
        d.fadeLeft = 0
    }

    // Takes deck `d`'s next frames for mix frames [g0, g0 + n) into the sum.
    // Returns how far into the block it reached.
    private fun take(d: DeckState, g0: Long, n: Int): Int {
        val o = d.out
        if (o == null) return takeTail(d, g0, n)
        if (d.held && d.on && !d.parked && (o.playing || premixing)) {
            // Held for placement it consumes nothing; once it holds what a dry
            // deck needs to come back, the session places it on a bar or cuts
            // it in.
            val avail = o.ring.written - d.mixIdx
            if (avail >= max(n.toLong() + rampFrames, refillFrames(d)) || (o.ended && avail > 0)) postReady(d, g0)
            return 0
        }
        if (!contributes(d) || (!o.playing && !premixing)) return 0
        o.ring.marks.view(view)
        var pos = 0
        while (pos < n && contributes(d)) {
            if (d.continuing && !continueStream(d, o, g0 + pos)) return pos
            val avail = o.ring.written - d.mixIdx
            val mark = view.covering(d.mixIdx)
            if (mark >= 0 && view.ringAt(mark) == d.mixIdx && startsPass(d, mark)) {
                if (d.holdNext || (d.endDrop && d.role == ROLE_A)) return pos
                // A leaving deck's song ends here: its next one is never
                // mixed under what is left of the ramp.
                if (d.leaveLeft > 0) {
                    probe?.note("left ${d.slot} at its song's end", g0 + pos)
                    d.stop()
                    return pos
                }
                // After a held end, the held pass plays only when the answer
                // named it; none, or another pass, leaves its frames unmixed
                // and unheard, and the player's own restart brings the named one.
                if (d.follow != BLEND_NO_FOLLOW && !names(d.follow, d, view, mark)) return pos
                d.newPass(view.entry(mark), view.flags(mark) and MARK_LIVE != 0, g0 + pos)
                d.follow = BLEND_NO_FOLLOW
            }
            if (d.dry == DRY_DRY) {
                // A dry deck comes back only with the start threshold and a
                // fill wake in its ring, so a deck fed at the rate it plays
                // goes on with no dry-out at the next wake.
                if (avail >= max((n - pos).toLong() + rampFrames, refillFrames(d)) || (avail > 0 && (o.ended || stoppedFilling(d, o)))) {
                    d.dry = DRY_NORMAL
                    d.rampLeft = rampFrames
                    d.rampTotal = rampFrames
                    probe?.note("back ${d.slot}", g0 + pos)
                    postReady(d, g0 + pos)
                } else {
                    return pos
                }
            }
            if (avail <= 0) {
                // A leave that runs out of frames is over: the deck never
                // comes back to finish it.
                if (d.leaveLeft > 0) {
                    probe?.note("left ${d.slot} dry", g0 + pos)
                    d.stop()
                    return pos
                }
                if (!o.ended && d.dry == DRY_NORMAL) {
                    d.dry = DRY_DRY
                    postDry(d, g0 + pos)
                }
                if (o.ended && !d.endPosted) {
                    d.endPosted = true
                    if (d.role == ROLE_A) postInput(BlendDeck.A, BlendInput.ENDED, g0 + pos)
                }
                return pos
            }
            var want = n - pos
            if (d.dry == DRY_NORMAL && !o.ended && avail < want + rampFrames) {
                d.dry = DRY_DRYING
                d.fadeLeft = avail.toInt()
                d.fadeTotal = min(avail.toInt(), rampFrames)
            }
            if (d.dry == DRY_DRYING) want = min(want, d.fadeLeft)
            val markEnd = if (mark >= 0) view.endOf(mark) else Long.MAX_VALUE
            val seg = minOf(want.toLong(), avail, markEnd - d.mixIdx).toInt()
            if (seg <= 0) return pos
            if (d.endDrop) {
                // A's frames of its own song after the blend's end: dropped
                // and counted heard, never mixed.
                o.addDrop(g0 + pos, d.mixIdx, seg.toLong())
                d.mixIdx += seg
                continue
            }
            mixSegment(d, o, mark, seg, pos, g0)
            pos += seg
            if (d.dry == DRY_DRYING) {
                d.fadeLeft -= seg
                if (d.fadeLeft <= 0) {
                    d.dry = DRY_DRY
                    postDry(d, g0 + pos)
                }
            }
        }
        return pos
    }

    // A released output's unmixed frames still play on until the slot's new
    // output takes over at the same song time.
    private fun takeTail(d: DeckState, g0: Long, n: Int): Int {
        val t = d.tail ?: return 0
        if (!d.on || d.parked || d.held) return 0
        val avail = t.out.ring.written - t.at
        if (avail <= 0) {
            d.tail = null
            return 0
        }
        t.out.ring.marks.view(view)
        val mark = view.covering(t.at)
        val seg = min(n.toLong(), avail).toInt()
        val saved = d.mixIdx
        d.mixIdx = t.at
        mixSegment(d, t.out, mark, seg, 0, g0)
        t.at = d.mixIdx
        d.mixIdx = saved
        return seg
    }

    private fun postDry(d: DeckState, frame: Long) {
        probe?.note("dry ${d.slot}", frame)
        when (d.role) {
            ROLE_A -> postInput(BlendDeck.A, BlendInput.DRY, frame)
            ROLE_B -> postInput(BlendDeck.B, BlendInput.DRY, frame)
        }
    }

    private fun postReady(d: DeckState, frame: Long) {
        if (d.readyPosted == frame) return
        d.readyPosted = frame
        when (d.role) {
            ROLE_A -> postInput(BlendDeck.A, BlendInput.READY, frame)
            ROLE_B -> postInput(BlendDeck.B, BlendInput.READY, frame)
        }
    }

    // Mixes `seg` frames of `o` from the deck's mix index at block offset
    // `pos`: its blend stage on each frame's song time, then its share and
    // ramps, into the sum.
    private fun mixSegment(d: DeckState, o: MixAudioOutput, mark: Long, seg: Int, pos: Int, g0: Long) {
        val ring = o.ring
        copyRaw(ring, d.mixIdx, seg, tmp)
        d.join?.let { j -> joinInto(j, seg) }
        if (d.join?.let { it.done >= it.frames } == true) d.join = null
        val per = if (mark >= 0) view.msPerFrame(mark) else Double.NaN
        val song = if (mark >= 0) view.songMs(mark) + (d.mixIdx - view.ringAt(mark)) * per else Double.NaN
        if (pacePending > 0 && d.role == ROLE_A && !per.isNaN() && abs(per * mixRate / 1_000.0 - pacePending) <= pacePending * 1e-6) {
            session.paceChanged(pacePending, g0 + pos)
            pacePending = 0.0
        }
        val s = d.shaper
        if (s != null && !song.isNaN() && (d.transition != null || s.shaping)) {
            val at = d.blendMs(song)
            s.arm(d.transition, at)
            s.process(tmp, seg, at, if (d.frozen) 0.0 else per)
        }
        for (j in 0 until seg) {
            var g = d.share
            if (d.shareLeft > 0) {
                d.share += (d.shareTo - d.shareFrom) / rampFrames
                d.shareLeft--
                if (d.shareLeft == 0) d.share = d.shareTo
                g = d.share
            }
            if (d.rampLeft > 0) {
                g *= 1f - d.rampLeft.toFloat() / d.rampTotal
                d.rampLeft--
            }
            if (d.dry == DRY_DRYING) {
                val left = d.fadeLeft - j
                if (left <= d.fadeTotal) g *= (left - 1).coerceAtLeast(0).toFloat() / d.fadeTotal
            }
            if (d.leaveTotal > 0) {
                g *= d.leaveLeft.toFloat() / d.leaveTotal
                if (d.leaveLeft > 0) d.leaveLeft--
            }
            if (!g.isFinite()) g = 0f
            sum[(pos + j) * 2] += tmp[j * 2] * g
            sum[(pos + j) * 2 + 1] += tmp[j * 2 + 1] * g
        }
        o.addRun(g0 + pos, d.mixIdx, seg)
        if (!song.isNaN()) {
            d.lastSongMs = song + (seg - 1) * per
            d.lastMsPerFrame = per
            d.blendMsNow = if (d.frozen) d.frozenMs else d.blendMs(song + seg * per)
        }
        if (mark >= 0) d.lastSongEndMs = view.songEndMs(mark)
        d.mixIdx += seg
        d.watchNanos = loop.nanoTime()
        if (d.leaveTotal > 0 && d.leaveLeft == 0) d.stop()
    }

    // The 5 ms equal-gain fade from a released ring's frames into the new
    // output's (already in `tmp`) at a Sonic restart.
    private val joinOld = FloatArray(blockFrames * 2)

    private fun joinInto(j: Join, seg: Int) {
        val n = min(seg, j.frames - j.done)
        if (n <= 0) return
        val avail = (j.out.ring.written - j.at).coerceAtLeast(0).toInt()
        val take = min(n, avail)
        copyRaw(j.out.ring, j.at, take, joinOld)
        for (k in 0 until n) {
            val w = (j.done + k + 1).toFloat() / j.frames
            val l = if (k < take) joinOld[k * 2] else 0f
            val r = if (k < take) joinOld[k * 2 + 1] else 0f
            tmp[k * 2] = l * (1 - w) + tmp[k * 2] * w
            tmp[k * 2 + 1] = r * (1 - w) + tmp[k * 2 + 1] * w
        }
        j.at += n
        j.done += n
    }

    private fun copyRaw(ring: DeckRing, from: Long, n: Int, into: FloatArray) {
        val first = ring.at(from)
        val cap2 = ring.capacity * 2
        val count = n * 2
        if (first + count <= cap2) {
            System.arraycopy(ring.raw, first, into, 0, count)
        } else {
            val head = cap2 - first
            System.arraycopy(ring.raw, first, into, 0, head)
            System.arraycopy(ring.raw, 0, into, head, count - head)
        }
    }

    // Passes

    // Whether the deck stands at the start of a next pass it holds, or that
    // the answer did not name.
    private fun heldAtBoundary(d: DeckState, o: MixAudioOutput): Boolean {
        if (d.holdNext) return true
        if (d.follow == BLEND_NO_FOLLOW || d.passEntry == null) return false
        val v = o.ring.marks.view(heldView)
        val m = v.covering(d.mixIdx)
        if (m < 0 || v.ringAt(m) != d.mixIdx || v.flags(m) and MARK_STREAM_START == 0) return false
        val later = v.entry(m) != d.passEntry || startsPassIn(v, d, m)
        return later && !names(d.follow, d, v, m)
    }

    private val heldView = MarkView()

    // Whether a follow-up answer names the pass starting at mark `mark` on
    // deck `d`: the pass it holds, or that entry's play by its number (its
    // own next play when it is the entry playing now).
    private fun names(answer: Long, d: DeckState, v: MarkView, mark: Long): Boolean {
        if (answer == BLEND_PASS_HELD) return true
        if (answer == BLEND_PASS_NONE) return false
        val entry = v.entry(mark) ?: return false
        return answer == blendPassOf(if (entry == d.passEntry) "$entry#again" else entry)
    }

    // Whether the stream starting at mark `mark` is a new play of a song for
    // this slot: another entry, or the same entry from its start right after
    // its end (a repeat); a flush that continues the song time is not.
    private fun startsPass(d: DeckState, mark: Long): Boolean {
        if (view.flags(mark) and MARK_STREAM_START == 0) return false
        val entry = view.entry(mark)
        if (d.passEntry == null) {
            d.newPass(entry, view.flags(mark) and MARK_LIVE != 0)
            return false
        }
        if (entry != d.passEntry) return true
        val songMs = view.songMs(mark)
        val blockMs = blockFrames * max(1e-9, abs(view.msPerFrame(mark)))
        if (songMs.isNaN() || d.lastSongMs.isNaN()) return false
        if (songMs > blockMs) return false
        val end = d.lastSongEndMs
        return if (!end.isNaN()) d.lastSongMs >= end - 2 * blockMs else d.lastSongMs > songMs + 2 * blockMs
    }

    // The mix frame of the outgoing deck's next pass boundary within the
    // next block, so the block ends there and the session hears it first.
    private fun passBoundaryAhead(): Long {
        if (lastA < 0) return -1
        val d = decks[lastA]
        val o = d.out ?: return -1
        if (!contributes(d) || d.endDrop) return -1
        o.ring.marks.view(view)
        var m = view.covering(d.mixIdx)
        if (m < 0) return -1
        val reach = d.mixIdx + blockFrames
        while (m < view.count) {
            val at = view.ringAt(m)
            if (at >= reach) break
            if (at >= d.mixIdx && view.flags(m) and MARK_STREAM_START != 0 && at < o.ring.written) {
                val probeState = d.passEntry
                val isNew = if (probeState == null) false else {
                    val entry = view.entry(m)
                    entry != probeState || startsPassAt(d, m)
                }
                if (isNew) return mixPoint + (at - d.mixIdx)
            }
            m++
        }
        return -1
    }

    private fun startsPassAt(d: DeckState, mark: Long): Boolean = startsPassIn(view, d, mark)

    private fun startsPassIn(v: MarkView, d: DeckState, mark: Long): Boolean {
        val songMs = v.songMs(mark)
        val blockMs = blockFrames * max(1e-9, abs(v.msPerFrame(mark)))
        if (songMs.isNaN() || songMs > blockMs) return false
        val end = d.lastSongEndMs
        val lastMs = d.lastSongMs
        if (lastMs.isNaN()) return false
        return if (!end.isNaN()) lastMs >= end - 2 * blockMs - blockFrames * abs(v.msPerFrame(mark)) else lastMs > songMs + 2 * blockMs
    }

    // The outgoing deck's next frame begins a new pass: the session hears
    // it before it is mixed, and decides whether it plays.
    private fun onPassBoundary() {
        if (lastA < 0) return
        if (decks[lastA].boundaryPosted == mixPoint) return
        decks[lastA].boundaryPosted = mixPoint
        if (blend != null || session.state != BlendState.IDLE || session.generation >= 0) {
            session.post(BlendDeck.A, BlendInput.MOVED_ON, mixPoint)
            sessionPoint = max(sessionPoint, mixPoint + 1)
            session.advance(sessionPoint, lastHeadMix, outgoingMs())
            afterSession(fromMain = false)
        }
    }

    // A new output on a slot that plays on in the same pass: its frames up
    // to the last one mixed are dropped and counted heard, so nothing
    // repeats. At speed 1 its frames lie on the old output's grid and the
    // join is exact; through Sonic the old ring's frames fade into the new
    // ones over 5 ms, aligned by their cross-correlation. Returns false
    // while it still waits for those frames.
    private fun continueStream(d: DeckState, o: MixAudioOutput, g: Long): Boolean {
        o.ring.marks.view(view)
        val w = o.ring.written
        if (w <= d.mixIdx) return false
        val m = view.covering(d.mixIdx)
        if (m < 0) return false
        val entry = view.entry(m)
        val first = view.songAt(d.mixIdx)
        val per = view.msPerFrame(m)
        if (entry != null && entry != d.passEntry && entry == d.prevEntry && d.passStartMix > lastPresented) {
            restartEarlierPass(d, o)
            return false
        }
        if (entry == null || entry != d.passEntry || first.isNaN() || first > d.lastSongMs + abs(per) / 2 || view.flags(m) and MARK_LIVE != 0) {
            d.continuing = false
            d.tail = null
            return true
        }
        val wantMs = d.lastSongMs + per
        // The pass's song time is looked for only up to the next stream of
        // another entry: a pass heard to its end goes on with that one.
        val next = nextStreamOf(d.mixIdx, w, d.passEntry)
        var at = firstAtOrAfter(d.mixIdx, min(w, next), wantMs - abs(per) / 2)
        if (at < 0 && next < w) at = next
        val alignFrames = (tuning.alignMs * mixRate / 1_000).roundToInt()
        val joinFrames = max(1, (tuning.joinMs * mixRate / 1_000).roundToInt())
        if (at < 0) {
            val drop = w - d.mixIdx
            o.addDrop(g, d.mixIdx, drop)
            d.mixIdx = w
            releaseDropped(o, w, alignFrames, joinFrames)
            return false
        }
        val sonic = abs(per - unitMs) > unitMs * 1e-6
        val t = d.tail
        if (sonic && t != null && at < next && t.out.ring.written - t.at >= joinFrames) {
            if (w - at < alignFrames + joinFrames) return false
            at = align(t, o, at, alignFrames, joinFrames)
            d.join = Join(t.out, t.at, joinFrames)
        }
        if (at > d.mixIdx) {
            o.addDrop(g, d.mixIdx, at - d.mixIdx)
            d.mixIdx = at
        }
        releaseDropped(o, at, alignFrames, joinFrames)
        d.tail = null
        d.continuing = false
        probe?.note("continued", g)
        return true
    }

    // A continuation's dropped frames are never mixed: their room goes back
    // to the deck. A re-mix from before the join reads their marks, and raw
    // frames only from an alignment's reach before the join.
    private fun releaseDropped(o: MixAudioOutput, upTo: Long, alignFrames: Int, joinFrames: Int) {
        val keep = upTo - 2L * alignFrames - joinFrames
        if (keep > o.ring.freed) o.ring.freed = keep
    }

    // A new output that plays on in the pass is placed as soon as its first
    // frames are in, not when the mix next needs it: a restart of an earlier
    // pass pauses the old output then, before more of the later pass is heard.
    private fun continueAtOnce() {
        if (held != null) return
        for (d in decks) {
            val o = d.out ?: continue
            if (!d.continuing || !contributes(d) || o.ring.written <= d.mixIdx) continue
            o.ring.marks.view(view)
            continueStream(d, o, mixPoint)
            if (held != null) return
        }
    }

    // The player restarted the pass still being heard, after the slot had
    // gone on into a later one (its next song, read ahead, removed by an
    // edit). While nothing of the later pass is written it is mixed again
    // from where it began; once written, the old output pauses and settles,
    // and a fresh one plays from its settled head, as Media3's own restart
    // did with its track.
    private fun restartEarlierPass(d: DeckState, o: MixAudioOutput) {
        val at = d.passStartMix
        val k = blocks.indexOfFirst { it.g0 == at }
        if (at >= writePoint && k >= 0) {
            val b = blocks[k]
            for (i in 0..1) decks[i].copyFrom(b.state[i])
            limiter.copyFrom(b.limiterState)
            limiterOut = b.limiterOutAt
            windowOn = b.windowOnAt
            windowOff = b.windowOffAt
            mixPoint = b.g0
            while (blocks.size > k) recycle(blocks.removeLast())
            for (x in decks) x.out?.clipRuns(at)
            log.removeAll { it.frame >= at }
            pending.clear()
            d.out = o
            d.mixIdx = 0
            d.tail = null
            d.continuing = true
            probe?.note("restart unwritten", at)
            return
        }
        restartSlot = d.slot
        restartOut = o
        d.continuing = false
        pauseReal(loop.nanoTime(), willResume = true)
        if (held == null) {
            restartSlot = -1
            restartOut = null
        }
        probe?.note("restart", writePoint)
    }

    private var restartSlot = -1
    private var restartOut: MixAudioOutput? = null

    // The new ring's frame within `reach` of `at` whose next `n` frames
    // best match the old ring's next `n`.
    private fun align(t: Tail, o: MixAudioOutput, at: Long, reach: Int, n: Int): Long {
        val old = FloatArray(n * 2)
        copyRaw(t.out.ring, t.at, n, old)
        val cand = FloatArray((n + 2 * reach) * 2)
        val from = max(0L, at - reach)
        val count = min((o.ring.written - from).toInt(), n + 2 * reach)
        if (count < n) return at
        copyRaw(o.ring, from, count, cand)
        var best = at
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in 0..count - n) {
            var dot = 0.0
            var energy = 0.0
            for (j in 0 until n * 2) {
                val x = cand[lag * 2 + j].toDouble()
                dot += x * old[j]
                energy += x * x
            }
            val score = if (energy > 0) dot / kotlin.math.sqrt(energy) else 0.0
            if (score > bestScore) {
                bestScore = score
                best = from + lag
            }
        }
        return best
    }

    // The ring frame where a stream of an entry other than `entry` starts,
    // at or after `from` and before `to`, or Long.MAX_VALUE.
    private fun nextStreamOf(from: Long, to: Long, entry: String?): Long {
        var m = view.covering(from)
        if (m < 0) return Long.MAX_VALUE
        while (m < view.count) {
            val at = view.ringAt(m)
            if (at >= to) break
            if (at >= from && view.flags(m) and MARK_STREAM_START != 0 && view.entry(m) != entry) return at
            m++
        }
        return Long.MAX_VALUE
    }

    private fun firstAtOrAfter(from: Long, to: Long, songMs: Double): Long {
        var at = from
        while (at < to) {
            val m = view.covering(at)
            if (m < 0) return -1
            val end = min(view.endOf(m), to)
            val per = view.msPerFrame(m)
            val base = view.songMs(m)
            if (per > 0 && !base.isNaN()) {
                val want = view.ringAt(m) + ceil((songMs - base) / per - 1e-9).toLong()
                val hit = max(at, want)
                if (hit < end) return hit
            }
            at = end
        }
        return -1
    }

    // After a block is first mixed: what the decks showed goes to the
    // session, with the start and the blend's end ahead of it.
    private fun afterBlock(b: Block) {
        if (b.g0 < sessionPoint) return
        val bl = blend
        if (bl != null && session.state != BlendState.IDLE) {
            postIncomingReadiness(bl)
            lookAhead(bl)
        }
        advanceSession(mixPoint)
    }

    private fun postIncomingReadiness(bl: MixBlend) {
        val bd = decks[bl.incomingSlot]
        if (bd.started) return
        val o = bd.out
        var ready = false
        if (o != null && o.playing) {
            o.ring.marks.view(view)
            val entry = bl.plan.entryMs.toDouble()
            val first = firstAtOrAfter(bd.mixIdx, o.ring.written, entry - unitMs / 2)
            ready = first >= 0 || o.ended
            if (first >= 0) bd.startRing = first
        }
        val state = if (ready) 1 else 0
        if (state != bd.readyState) {
            bd.readyState = state
            postInput(BlendDeck.B, if (ready) BlendInput.READY else BlendInput.DRY, mixPoint - 1)
        }
    }

    // A's frame at the plan's start (moved by B's first frame for the exact
    // start) and at the blend's end, once either lies within the next two
    // blocks of A's ring.
    private fun lookAhead(bl: MixBlend) {
        val a = decks[bl.outgoingSlot]
        val o = a.out ?: return
        if (!contributes(a) || a.dry != DRY_NORMAL) return
        o.ring.marks.view(view)
        // A's frames are searched only up to its song's end: frames of its
        // next pass never stand in for its own. Once that end is in its ring
        // the session hears where it falls.
        val songEnd = songEndRing(a, o)
        if (songEnd != Long.MAX_VALUE && endKnownGen != blendGen) {
            endKnownGen = blendGen
            postInput(BlendDeck.A, BlendInput.DECODED_END, mixPoint - 1, mixPoint + (songEnd - a.mixIdx))
            probe?.note("end known ${mixPoint + (songEnd - a.mixIdx)} (ring $songEnd, ${view.songAt(songEnd - 1)} ms; mixing ${a.mixIdx} at ${view.songAt(a.mixIdx)} ms, session at $sessionPoint, start ${session.startMs})", mixPoint)
        }
        val reach = min(a.mixIdx + 2 * blockFrames, songEnd)
        val rate = bl.plan.beatMatchRate ?: 1.0
        val startMs = session.startMs
        if (session.state == BlendState.ARMED && startPostedMs != startMs) {
            val bd = decks[bl.incomingSlot]
            val bo = bd.out
            var target = startMs
            if (bd.readyState == 1 && bo != null && bd.startRing >= 0) {
                bo.ring.marks.view(view)
                val e = view.songAt(bd.startRing)
                if (!e.isNaN()) target = startMs + (e - bl.plan.entryMs) / rate
                o.ring.marks.view(view)
            }
            val at = firstAtOrAfter(a.mixIdx, min(reach, o.ring.written), target - unitMs / 2)
            if (at >= 0) {
                startPostedMs = startMs
                postInput(BlendDeck.A, BlendInput.START_AT, mixPoint - 1, mixPoint + (at - a.mixIdx))
            }
        }
        val endMs = startMs + bl.plan.overlapMs + extensionMs
        val running = session.state == BlendState.MIXING || session.state == BlendState.STALLED
        if (running && endPostedMs != endMs) {
            val at = firstAtOrAfter(a.mixIdx, min(reach, o.ring.written), endMs - unitMs / 2)
            if (at >= 0) {
                endPostedMs = endMs
                postInput(BlendDeck.A, BlendInput.END_REACHED, mixPoint - 1, mixPoint + (at - a.mixIdx))
            }
        }
    }

    // The generation whose outgoing song's decoded end the session has heard.
    private var endKnownGen = -1L

    // The ring frame where the song deck `d` is mixing ends in its ring: the
    // first frame of its next pass, or its stream's end; Long.MAX_VALUE
    // while neither is in hand.
    private fun songEndRing(d: DeckState, o: MixAudioOutput): Long {
        var m = view.covering(d.mixIdx)
        if (m < 0) return Long.MAX_VALUE
        val w = o.ring.written
        if (view.ringAt(m) < d.mixIdx) m++
        while (m < view.count) {
            val at = view.ringAt(m)
            if (at >= w) break
            if (view.flags(m) and MARK_STREAM_START != 0) {
                if (view.entry(m) != d.passEntry) return at
                // The same entry again from its start right after its end: a
                // repeat.
                val song = view.songMs(m)
                val before = if (m - 1 >= view.floor && at > d.mixIdx) {
                    view.songMs(m - 1) + (at - view.ringAt(m - 1)) * view.msPerFrame(m - 1)
                } else {
                    d.lastSongMs
                }
                val blockMs = blockFrames * max(1e-9, abs(view.msPerFrame(m)))
                if (!song.isNaN() && song <= blockMs && before > song + 2 * blockMs) return at
            }
            m++
        }
        return if (o.ended) w else Long.MAX_VALUE
    }

    // Positions

    private fun publishPositions(now: Long) {
        val r = cur
        // With no output playing, what the last one took still plays out:
        // presented runs on from the last reading, never past the settled
        // head.
        val runOn = lastPresented + (now - lastPresentedNanos).coerceAtLeast(0) * mixRate / 1_000_000_000L
        var presented = if (settledHead >= 0) max(lastPresented, min(runOn, settledHead)) else lastPresented
        var advancing = false
        if (r != null && r.started && !r.paused && r.music) {
            if (r.out.timestamp(ts) && ts.frame >= 0) {
                if (ts.frame != r.stampFrame) {
                    r.stampFrame = ts.frame
                    r.stampNanos = ts.nanos
                    if (r.clamping && r.firstCycleNanos >= 0 && ts.nanos - r.firstCycleNanos >= tuning.clampMs * 1_000_000) r.clamping = false
                }
                val run = ((now - ts.nanos) * mixRate / 1_000_000_000L).coerceAtLeast(0)
                presented = r.base + ts.frame + run
                advancing = true
            } else {
                presented = r.base
            }
            if (r.clamping) presented = min(presented, r.headMix - latencyFrames(r))
            presented = min(presented, r.base + r.written)
        }
        // The silent fallback plays the paused output's held frames and then
        // silence before the music written again from h: none of it is heard.
        val fb = fallback
        val beforeMusic = fb != null && !fb.musicStarted
        if (beforeMusic) advancing = false
        if (presentedHold >= 0) {
            // Held at h until the fresh output's own timestamp advances.
            if (!beforeMusic && presented >= presentedHold && r != null && r.stampFrame >= 0) presentedHold = -1 else presented = min(presented, presentedHold)
        }
        presented = max(presented, lastPresented)
        lastPresented = presented
        lastPresentedNanos = now
        for (d in decks) {
            val o = d.out ?: continue
            publish(d, o, presented, now, advancing && presentedHold < 0)
        }
        publishHeadSongs(r)
    }

    // Each slot's song time at the real output's head, with the queue entry
    // it belongs to and when it was read on the mixer's clock: where the
    // mixer decides a start, ahead of what is heard by the route's latency.
    class HeadSong(val entry: String?, val ms: Double, val nanos: Long)

    // Two slots written on the mix thread with nothing allocated, under a
    // sequence count that is odd while a write is in progress; main reads
    // them.
    private val headSeq = java.util.concurrent.atomic.AtomicIntegerArray(2)
    private val headKnown = java.util.concurrent.atomic.AtomicIntegerArray(2)
    private val headEntry = java.util.concurrent.atomic.AtomicReferenceArray<String?>(2)
    private val headMsBits = java.util.concurrent.atomic.AtomicLongArray(2)
    private val headAt = java.util.concurrent.atomic.AtomicLongArray(2)

    // The slot's song time at the head, or null while none is known.
    fun headSong(slot: Int): HeadSong? {
        while (true) {
            val seq = headSeq.get(slot)
            if (seq and 1 != 0) continue
            val known = headKnown.get(slot) != 0
            val entry = headEntry.get(slot)
            val ms = Double.fromBits(headMsBits.get(slot))
            val at = headAt.get(slot)
            if (headSeq.get(slot) == seq) return if (known) HeadSong(entry, ms, at) else null
        }
    }

    private fun writeHead(slot: Int, known: Boolean, entry: String?, ms: Double, at: Long) {
        headSeq.incrementAndGet(slot)
        headKnown.set(slot, if (known) 1 else 0)
        headEntry.set(slot, entry)
        headMsBits.set(slot, ms.toRawBits())
        headAt.set(slot, at)
        headSeq.incrementAndGet(slot)
    }

    // A slot's move (a seek, a load): its song time at the head is not known
    // until its new output's frames reach the head.
    private fun forgetHead(slot: Int) {
        if (headKnown.get(slot) != 0) writeHead(slot, false, null, Double.NaN, 0L)
    }

    // While the real output plays, each slot's song time where its frames
    // are at the head. Paused, the time stands (its moment moves on with
    // the clock). Playing with the slot's frames not yet at the head (a new
    // output after the player's own restart), the last time is kept for
    // main to run on.
    private fun publishHeadSongs(r: Real?) {
        val playing = r != null && r.started && !r.paused && r.music
        for (d in decks) {
            val o = d.out
            if (!playing) {
                if (headKnown.get(d.slot) != 0) writeHead(d.slot, true, headEntry.get(d.slot), Double.fromBits(headMsBits.get(d.slot)), loop.nanoTime())
                continue
            }
            if (o == null || !o.coversMixFrame(lastHeadMix)) continue
            o.ring.marks.view(view)
            val ring = o.ringAt(lastHeadMix)
            val m = view.covering(ring)
            val ms = view.songAt(ring)
            if (m >= 0 && !ms.isNaN()) writeHead(d.slot, true, view.entry(m), ms, r?.headNanos ?: loop.nanoTime())
        }
    }

    // The route's head-to-presented latency at a head step: as measured,
    // else its kind of device's own figure, else (a device of no known
    // kind) the largest any route has stored and at least the floor.
    private fun latencyFrames(r: Real): Long {
        val known = r.measure.latencyNanos
        if (known > 0) return known * mixRate / 1_000_000_000L
        val ms = kindLatencyMs(r.route) ?: run {
            var most = tuning.unknownLatencyMs
            for (m in routes.values) most = max(most, m.latencyNanos / 1e6)
            most
        }
        return (ms * mixRate / 1_000).toLong()
    }

    // A route key starts with its device's AudioDeviceInfo type.
    private fun kindLatencyMs(route: String?): Double? = when (route?.substringBefore(':')?.toIntOrNull()) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> tuning.wiredLatencyMs
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST,
        -> tuning.bluetoothLatencyMs
        // Android Auto's projection takes media through a policy mix (a
        // remote submix), or a car's own bus.
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX, AudioDeviceInfo.TYPE_BUS -> tuning.carLatencyMs
        else -> null
    }

    private fun publish(d: DeckState, o: MixAudioOutput, presented: Long, now: Long, advancing: Boolean) {
        val r = o.resampler ?: return
        val ringPresented = o.ringAt(presented)
        var pres = r.inputAt(ringPresented)
        var maxIn = r.inputAt(d.mixIdx)
        val lastEnd = o.lastRunEnd()
        val allPresented = lastEnd < 0 || presented >= lastEnd
        // Every written frame is heard once input stops: a dry ring whose
        // mixed frames are all heard counts what the resampler holds too.
        if (d.mixIdx >= o.ring.written && allPresented && (contributes(d) || o.ended)) {
            pres = o.inWritten
            maxIn = o.inWritten
        }
        pres = max(pres, o.snap.presentedIn)
        maxIn = max(maxIn, pres)
        val adv = advancing && contributes(d) && o.coversMixFrame(presented)
        o.snap = OutputSnap(pres, now, adv, maxIn, o.songRate)
        if (pres > 0) o.firePositionAdvancing()
    }

    // Frees ring space behind the block holding the consumed point, and the
    // records and blocks before it.
    private fun freeRings() {
        val consumed = if (held != null && settledHead >= 0) settledHead else min(lastHeadMix, writePoint)
        while (blocks.size > 1) {
            val b = blocks.first()
            val next = blocks[1]
            if (next.outStart > consumed || b.outStart + b.outCount > writePoint) break
            recycle(blocks.removeFirst())
        }
        val keepFrom = blocks.firstOrNull()?.g0 ?: mixPoint
        log.removeAll { it.frame < keepFrom }
        for (d in decks) {
            val o = d.out ?: continue
            val stateIdx = blocks.firstOrNull()?.state?.get(d.slot)?.takeIf { it.out === o }?.mixIdx ?: d.mixIdx
            val keep = min(stateIdx, min(d.mixIdx, o.ringAt(keepFrom)))
            if (keep > o.ring.freed) o.ring.freed = keep
            o.pruneRuns(min(lastPresented, keepFrom) - 2 * blockFrames)
            o.ring.marks.floor = max(o.ring.marks.floor, markFloor(o, keep))
        }
    }

    private fun markFloor(o: MixAudioOutput, ringFrame: Long): Long {
        o.ring.marks.view(view)
        val m = view.covering(ringFrame)
        return if (m < 0) view.floor else m
    }

    // A playing deck whose unmixed frames are not taken for the watchdog's
    // span fails through its next write; a dry ring is buffering.
    private fun watchdog(now: Long) {
        for (d in decks) {
            val o = d.out ?: continue
            if (!o.playing || !d.on || d.parked || d.held || holding || d.endDrop || o.ended || heldAtBoundary(d, o)) {
                d.watchNanos = now
                continue
            }
            if (o.ring.written <= d.mixIdx) {
                d.watchNanos = now
                continue
            }
            if (now - d.watchNanos > tuning.watchdogMs * 1_000_000 && o.failure == 0) {
                o.failure = WRITE_FAILED
                probe?.note("watchdog slot ${d.slot}", mixPoint)
            }
        }
    }

    // Main's message lag, probed while a deck plays.
    private fun probeMain(now: Long) {
        if (now < nextProbeAt) return
        if (decks.none { it.out?.playing == true }) return
        nextProbeAt = now + tuning.probeMs * 1_000_000
        val sent = now
        toMain(Runnable {
            val lag = (loop.nanoTime() - sent) / 1e6
            loop.post { mainLag.add(loop.nanoTime(), lag) }
        })
    }

    // Wakes: the fill one at the quarter-buffer point, and head-tied ones
    // for the proposal, the floor, each event's crossing and the dry rule.
    private fun scheduleNext(now: Long) {
        val r = cur
        var next = Long.MAX_VALUE
        if (r != null && r.started && !r.paused && r.gateState == GATE_OPEN) {
            val lead = r.base + r.written - r.headMix
            val refill = realBufferFrames - realBufferFrames / 4
            val untilRefill = max(0L, lead - refill) * 1_000_000_000L / mixRate
            next = now + max(tuning.blockMs * 1_000_000L, min(untilRefill, realBufferFrames.toLong() / 4 * 1_000_000_000L / mixRate))
        } else if (decks.any { it.out != null }) {
            next = now + tuning.probeMs * 1_000_000
        }
        if (decks.any { it.out?.playing == true }) next = min(next, nextProbeAt)
        if (next != Long.MAX_VALUE) {
            wakeAt = next
            fillDue = next
            loop.postAt(next, fillTask)
        }
        scheduleHeadWake(now)
    }

    private var fillDue = 0L
    private val fillTask = Runnable { wake(fillDue) }

    private fun scheduleHeadWake(now: Long) {
        val r = cur ?: return
        if (!r.started || r.paused || r.gateState != GATE_OPEN) return
        var target = Long.MAX_VALUE
        val s = startS
        if (s >= 0 && blend != null) {
            val state = commitWord.stateOf(blendGen)
            if (state == CommitState.INITIAL) target = min(target, s - timing.proposeAtFrames)
            if (state != CommitState.ACKED || s - lastHeadMix > timing.commitAtFrames) target = min(target, s - timing.commitAtFrames)
        }
        var w = 0
        for (i in 0 until watchCount) {
            if (watch[i] > lastHeadMix) {
                watch[w++] = watch[i]
                target = min(target, watch[i])
            }
        }
        watchCount = w
        // The dry rule: 2 ms reads once the written lead falls under a quantum.
        val lead = r.base + r.written - r.headMix
        val nothingMore = decks.none { hasFrames(it) }
        if (r.music && nothingMore && lead < quantumFrames()) target = min(target, r.headMix + 1)
        if (target == Long.MAX_VALUE) return
        val frames = max(0L, target - lastHeadMix)
        val due = now + max(tuning.headReadMs * 1_000_000, frames * 1_000_000_000L / mixRate)
        // A head wake already due sooner keeps its time: a later wake never
        // pushes it on.
        if (headDue in now..due) return
        headDue = due
        loop.postAt(due, headTask)
    }

    private fun headWake() {
        val due = headDue
        headDue = -1
        wake(due)
    }

    // Lifecycle of the real output

    // Whether a deck's sink plays, unparked, while the person wants music. A
    // slot whose playing output the player released on its own (a restart,
    // its new output still to come) counts as playing.
    private fun wantsSound(): Boolean {
        if (playerPaused || holding || !intentPlaying) return false
        return decks.any { d -> (d.out?.let { it.playing && !it.released } == true || (d.out == null && d.restarting)) && !d.parked }
    }

    private fun lifecycle(now: Long) {
        if (quitting) return
        val alive = decks.any { it.out != null }
        if (!nothingLeft()) {
            nothingSince = -1L
        } else if (nothingSince < 0) {
            nothingSince = now
        }
        val want = wantsSound()
        val r = cur
        if (!want) {
            if (r != null && r.started && !r.paused) pauseReal(now, willResume = true)
            if (!alive && held == null && cur != null && cur?.started != true) {
                releaseReal(cur!!)
                cur = null
            }
            if (nothingSince >= 0) {
                val due = nothingSince + tuning.idleReleaseMs * 1_000_000
                if (now >= due) {
                    releaseSpareOutputs()
                } else {
                    loop.remove(idleTask)
                    loop.postAt(due, idleTask)
                }
            }
            return
        }
        if (held != null && settledHead < 0) return
        if (fallback != null) return
        if (r == null) {
            val f = fresh
            // A fresh output is taken up only for something to play: until
            // then what the last one took still runs up to its settled head.
            if (f != null && !hasSomethingToPlay()) return
            if (f != null) {
                fresh = null
                cur = f
                prepareStart(f)
            } else {
                val p = held
                if (p != null && buildsFailed && !building) {
                    // The builds failed through the pause; they are tried
                    // once more at the resume before the silent fallback.
                    if (resumeRetried) {
                        startFallback(p)
                        return
                    }
                    resumeRetried = true
                    buildsFailed = false
                    noPendingSince = -1
                    buildFailedAt = -1
                }
                needOutput(now)
                return
            }
        }
        val c = cur ?: return
        // The gate starts once the first music can go in right after it, so
        // no output waits started and empty for a slow deck.
        if (c.gateState == GATE_IDLE && hasSomethingToPlay() && firstFillReady()) startGate(c, now)
    }

    private fun hasSomethingToPlay(): Boolean = decks.any { hasFrames(it) } || blocks.any { it.outStart + it.outCount > writePoint }

    // No deck output left that can play again (Media3 stops its renderers
    // at the queue's end without letting the output go), none coming from a
    // player's own restart and no released frames still to play: a stop, a
    // station's pause, or the queue's end.
    private fun nothingLeft(): Boolean = decks.none { d ->
        d.out?.let { !(it.ended && !it.playing) } == true || d.restarting || d.tail != null
    }

    // Since when nothing has been left to play, or -1.
    private var nothingSince = -1L

    private val idleTask = Runnable { lifecycle(loop.nanoTime()) }

    // With nothing left to play, the output built for a resume goes, and a
    // paused one once settled. Not while the player means to resume (a
    // station's pause lets its stream go and comes back): what it holds
    // then is the resume's, so a build failing there is never met with no
    // output at all. They go at a stop or the queue's end.
    private fun releaseSpareOutputs() {
        if (playerPaused && !stopped) return
        loop.remove(idleTask)
        fresh?.let {
            releaseReal(it)
            fresh = null
        }
        val p = held
        if (p != null && settledHead >= 0 && fallback == null && pendingDisarm == null) {
            releaseReal(p)
            held = null
        }
    }

    // The player stopped: no resume is coming.
    private var stopped = false

    // A fresh output becomes the one written from the write point; when
    // that continues a stream (after a pause, a rebuild, a restart) its
    // first 20 ms ramp in.
    private fun prepareStart(r: Real) {
        r.base = writePoint
        r.written = 0
        settledHead = -1
        // What a re-mix kept is now written into this output.
        keptA = -1
        if (resumeOnset) {
            r.onsetLeft = rampFrames
            resumeOnset = false
        }
        r.out.setVolume(playerVolume)
        r.out.setPreferredDevice(preferred)
        r.route = r.out.routedDevice()
        r.measure = r.route?.let { routes.getOrPut(it) { RouteMeasure() } } ?: RouteMeasure()
        realBufferFrames = r.out.bufferFrames
        ringCapacity = ringCapacityFor(r.out.bufferFrames)
    }

    // The duck gate: the start threshold set, the baseline read here, play()
    // with nothing written, and music only the slack after the list shows
    // one more music entry than the lowest it showed (or the bound).
    private fun startGate(r: Real, now: Long) {
        setThreshold(r)
        r.route = r.out.routedDevice()
        if (playerVolume <= 0f) {
            r.out.play()
            r.started = true
            r.playNanos = now
            r.gateState = GATE_OPEN
            return
        }
        val base = gate.baseline()
        r.out.play()
        r.started = true
        r.playNanos = loop.nanoTime()
        // A route that went away while the output waited is restored by
        // play(): its device and threshold are read again before music.
        val after = r.out.routedDevice()
        if (after != r.route) routed(r)
        r.gateState = GATE_WAITING
        r.gateGen = gate.start(r.id, base, r.playNanos, { signalled, nanos, record ->
            loop.post { gateAnswer(r, signalled, nanos, record) }
        }, { play, bound -> ownEventBetween(play - tuning.gateBoundMs * 1_000_000, bound) }, ::otherArrivedBetween)
    }

    private fun gateAnswer(r: Real, signalled: Boolean, nanos: Long, record: GateRecord?) {
        if (record != null) {
            toMain(Runnable { listener.onGateBound(record) })
            return
        }
        if (cur !== r || r.gateState != GATE_WAITING) return
        r.signalNanos = nanos
        r.gateState = GATE_SLACK
        if (!signalled) {
            // The bound: music now, with the onset ramp.
            r.onsetLeft = rampFrames
            openGate(r)
            return
        }
        loop.postAt(nanos + tuning.gateSlackMs * 1_000_000, Runnable { if (cur === r && r.gateState == GATE_SLACK) openGate(r) })
    }

    private fun openGate(r: Real) {
        r.gateState = GATE_OPEN
        r.signalled = true
        wake(-1L)
    }

    // One head quantum plus a block, or the whole buffer before the route's
    // quantum is known. API 29 and 30, which have no start threshold, set
    // the buffer size to that count until the head's first step, then the
    // whole buffer again.
    private fun setThreshold(r: Real) {
        val m = r.measure
        val frames = if (m.known) (m.quantum + blockFrames).toInt() else r.out.bufferFrames
        r.thresholdSet = frames
        r.lowered = false
        if (sdk >= 31) {
            r.out.setStartThreshold(frames)
        } else if (r.firstCycleNanos < 0) {
            r.out.setBufferSize(max(frames, builder.minimumFrames()))
            r.smallSize = true
        } else {
            r.out.setBufferSize(r.out.bufferFrames)
            r.smallSize = false
        }
    }

    // A playing output whose head has stood for one change interval since
    // its first music frame, or since the first frame written after it
    // stopped, holds less than it needs to start: its start is lowered to
    // what it holds (API 29 and 30: its buffer size), and put back once the
    // head has stepped.
    private fun lowerStartWhenStill(r: Real, now: Long) {
        if (!r.started || !r.music || r.paused) return
        if (sdk < 31 && r.smallSize && !r.lowered && r.firstCycleNanos >= 0) {
            // Started: the whole buffer from here on.
            r.out.setBufferSize(r.out.bufferFrames)
            r.smallSize = false
        }
        if (r.lowered) {
            if (r.head > r.loweredAt) {
                r.lowered = false
                if (sdk >= 31) {
                    r.out.setStartThreshold(r.thresholdSet)
                } else {
                    r.out.setBufferSize(r.out.bufferFrames)
                    r.smallSize = false
                }
            }
            return
        }
        val since = r.stillSince
        if (since < 0 || r.head >= r.written || now - since < changeIntervalNanos(r)) return
        val unplayed = (r.written - r.head).toInt()
        if (sdk >= 31) r.out.setStartThreshold(max(1, unplayed)) else r.out.setBufferSize(max(unplayed, builder.minimumFrames()))
        r.lowered = true
        r.loweredAt = r.head
    }

    private fun changeIntervalNanos(r: Real?): Long {
        val m = r?.measure
        if (m != null && m.intervalNanos > 0) return m.intervalNanos
        var most = 0L
        for (x in routes.values) most = max(most, x.intervalNanos)
        return if (most > 0) most else (tuning.defaultIntervalMs * 1_000_000).toLong()
    }

    // The dry rule: an output with music written whose head has stood at
    // its written count for one change interval, counted from the last read
    // below it, is paused and never written again; the next frames go to a
    // fresh output through the gate.
    private fun dryRule(now: Long) {
        val r = cur ?: return
        if (!r.started || r.paused || r.gateState != GATE_OPEN || !r.music) return
        if (r.head < r.written) return
        val since = r.lastBelowNanos
        if (since < 0) return
        if (now - since < changeIntervalNanos(r)) return
        if (decks.any { hasFrames(it) }) return
        r.out.pause()
        r.paused = true
        noteOwn(r)
        // Its head stood at its written count: everything it took plays out,
        // so positions reach the written count and a deck that has stopped
        // feeding reads as buffering or ended.
        settledHead = r.base + r.written
        writePoint = settledHead
        presentedHold = settledHead
        held = r
        cur = null
        buildFresh()
        probe?.note("dry", settledHead)
    }

    // A pause: the output pauses at once and is never played again. One
    // that will resume settles (its h) while a fresh output is built for the
    // resume; a gate that had not opened just goes.
    private fun pauseReal(now: Long, willResume: Boolean) {
        val r = cur ?: return
        if (!r.started || r.paused) return
        r.out.pause()
        r.paused = true
        noteOwn(r)
        gate.cancel()
        cur = null
        resumeRetried = false
        if (!r.music) {
            // Nothing written: the next play starts the gate over on a
            // fresh output, from the same point.
            releaseReal(r)
            if (willResume) buildFresh()
            return
        }
        held?.let { releaseReal(it) }
        held = r
        settledHead = -1
        settleStartNanos = now
        settleValue = r.readHead(now)
        settleChanged = now
        settleLastRead = r.head
        settleDue = now + tuning.headReadMs * 1_000_000
        loop.postAt(settleDue, settleTask)
        if (willResume) buildFresh()
    }

    private var settleStartNanos = 0L
    private var settleValue = 0L
    private var settleChanged = 0L
    private var settleLastRead = 0L

    // 2 ms reads of the paused output's head until it has stood for one
    // change interval plus the jitter. A read that jumps past what the
    // cycles since could play (a restored, invalidated track returning its
    // written count) is not taken: h is the read before it.
    private fun settleRead() {
        val p = held ?: return
        val now = loop.nanoTime()
        val m = p.measure
        val before = p.head
        val read = p.readHead(now)
        val step = if (m.known) m.largestStep else quantumFrames()
        val cycles = max(1L, (now - p.headNanos + changeIntervalNanos(p)) / max(1L, changeIntervalNanos(p)))
        val jumped = read - settleLastRead > (cycles + 1) * step || (read >= p.written && before < p.written - step)
        if (jumped) {
            settled(p, settleLastRead)
            return
        }
        if (read != settleLastRead) {
            settleLastRead = read
            settleChanged = now
        }
        val jitterNanos = tuning.headReadMs * 1_000_000
        if (now - settleChanged >= changeIntervalNanos(p) + jitterNanos) {
            settled(p, settleLastRead)
            return
        }
        if (now - settleStartNanos >= tuning.settleBoundMs * 1_000_000) {
            // Stuck: rebuilt from what it had taken.
            settled(p, settleLastRead)
            return
        }
        settleDue = now + tuning.headReadMs * 1_000_000
        loop.postAt(settleDue, settleTask)
    }

    private fun settled(p: Real, head: Long) {
        settledHead = p.base + head
        writePoint = settledHead
        presentedHold = settledHead
        resumeOnset = true
        // The paused output goes once its replacement is built.
        if (fresh != null) {
            releaseReal(p)
            held = null
        }
        // In the order main sent them: an interrupt (or a deck's own pause)
        // before the release that followed it.
        pendingDisarm?.let { reason ->
            pendingDisarm = null
            onDisarm(reason, pendingDisarmGen)
            val answer = pendingFollowUp
            if (answer != BLEND_NO_FOLLOW) {
                pendingFollowUp = BLEND_NO_FOLLOW
                session.followUp(pendingDisarmGen, answer)
                afterSession(fromMain = true)
            }
        }
        pendingPause?.let { deck ->
            pendingPause = null
            postPaused(deck, settledHead)
        }
        if (clearAfterSettle) {
            clearAfterSettle = false
            clearAll(keep = null)
        }
        val slot = restartSlot
        val out = restartOut
        if (slot >= 0 && out != null) {
            restartSlot = -1
            restartOut = null
            restartFromHead(decks[slot], out)
        }
        wake(-1L)
    }

    private var clearAfterSettle = false
    private var pendingDisarm: BlendDisarm? = null
    private var pendingDisarmGen = -1L
    private var resumeRetried = false

    // Builds outputs on the builder thread by Media3's creation rules: at
    // once; after a failure no retry while a release is pending, then
    // retries at least 50 ms apart, failing 200 ms after the first moment
    // with no release pending.
    private fun needOutput(now: Long) {
        if (fresh != null || building || buildsFailed) return
        buildFresh()
    }

    private fun buildFresh() {
        if (building || fresh != null) return
        val now = loop.nanoTime()
        if (buildFailedAt >= 0 && now - buildFailedAt < tuning.retryGapMs * 1_000_000) {
            loop.postAt(buildFailedAt + tuning.retryGapMs * 1_000_000, Runnable { buildFresh() })
            return
        }
        building = true
        val size = realTargetFrames
        builderLoop.post {
            val pendingRelease = builder.hasPendingReleases()
            val made = if (buildFailedAt >= 0 && pendingRelease) {
                null
            } else {
                try {
                    builder.build(size)
                } catch (e: Exception) {
                    null
                }
            }
            loop.post { built(made, pendingRelease) }
        }
    }

    private fun built(out: RealOutput?, pendingRelease: Boolean) {
        building = false
        val now = loop.nanoTime()
        if (quitting) {
            out?.release()
            return
        }
        if (out == null) {
            buildFailedAt = now
            if (!pendingRelease) {
                if (noPendingSince < 0) noPendingSince = now
            } else {
                noPendingSince = -1
            }
            if (noPendingSince >= 0 && now - noPendingSince >= tuning.retryForMs * 1_000_000) {
                buildsFailed = true
                failBuilds()
                return
            }
            buildFresh()
            return
        }
        buildFailedAt = -1
        noPendingSince = -1
        buildsFailed = false
        resumeRetried = false
        val r = Real(out, ++realIds)
        // A fresh output carries its own routing listener and preferred
        // device from its build, while a paused one is still alive.
        out.setPreferredDevice(preferred)
        out.listenForRoutes(loop.handler()) { post { routed(r) } }
        fresh = r
        // A paused output that has settled goes once its replacement exists.
        val p = held
        if (p != null && settledHead >= 0) {
            releaseReal(p)
            held = null
        }
        wake(-1L)
    }

    // Every build failed: with a paused output kept the resume falls back to
    // it, silent for a fixed span; otherwise the playing decks fail through
    // their writes, where 1.12.0's sink would have.
    private fun failBuilds() {
        val p = held
        if (p != null && settledHead >= 0 && wantsSound()) {
            startFallback(p)
            return
        }
        if (p != null) return
        for (d in decks) d.out?.let { if (it.playing) it.failure = WRITE_FAILED }
    }

    // The paused output resumed at track volume 0: its held frames play out
    // unheard, silence carrying no deck frames makes the span from its first
    // mix cover the gate's bound and the duck ramp, the volume comes back
    // once a head read shows it past its held frames, and music goes on from
    // h with the onset ramp, positions held at h until it is heard.
    private fun startFallback(p: Real) {
        held = null
        cur = p
        p.paused = false
        p.out.setVolume(0f)
        val heldUnplayed = max(0L, p.written - p.head)
        val span = (tuning.gateBoundMs + tuning.duckRampMs) * mixRate / 1_000
        fallback = Fallback(p.written, p.written + max(0L, span - heldUnplayed), settledHead)
        p.out.play()
        p.gateState = GATE_OPEN
        p.started = true
        presentedHold = settledHead
        probe?.note("fallback", settledHead)
    }

    // Returns true once music may be written.
    private fun writeFallback(r: Real): Boolean {
        val fb = fallback ?: return true
        if (r.written < fb.silenceEnd) {
            val n = min((fb.silenceEnd - r.written).toInt(), blockFrames)
            java.util.Arrays.fill(tmp, 0, n * 2, 0f)
            val took = try {
                r.out.write(tmp, 0, n)
            } catch (e: RealOutputDead) {
                rebuild(r, "dead ${e.code}")
                return false
            }
            r.written += took
            if (r.written < fb.silenceEnd) return false
        }
        if (!fb.musicStarted) {
            // The next written frame carries h: the held frames and the
            // silence sit before it on this output's own count.
            fb.musicStarted = true
            r.base = fb.musicFrom - r.written
            writePoint = fb.musicFrom
            r.onsetLeft = rampFrames
            presentedHold = fb.musicFrom
        }
        if (!fb.restored && r.head >= fb.heldEnd) {
            r.out.setVolume(playerVolume)
            fb.restored = true
            fallback = null
        }
        return true
    }

    // A dead, stalled or stuck output: released and rebuilt, every live
    // deck mixed again from its consumed point (the block frames are kept),
    // which counts as heard; the new one starts through the gate with the
    // onset ramp.
    private fun rebuild(r: Real, why: String) {
        if (r.dead) return
        r.dead = true
        probe?.note("rebuild $why", r.headMix)
        // The silent fallback's output died: its held frames and silence
        // were never heard, so the music goes on from h on the new output,
        // and the fallback is over.
        val fb = fallback?.takeIf { cur === r }
        val consumed = if (fb != null && !fb.musicStarted) fb.musicFrom else r.headMix
        if (fb != null) fallback = null
        releaseReal(r)
        if (cur === r) cur = null
        writePoint = consumed
        lastPresented = max(lastPresented, consumed)
        resumeOnset = true
        session.cleared(consumed)
        // An output rebuilt for a stuck head that has never moved and is
        // stuck too: the playing decks fail, as 1.12.0's sink would.
        if (why == "stuck" && rebuiltStuck) {
            for (d in decks) d.out?.let { if (it.playing) it.failure = WRITE_FAILED }
            return
        }
        rebuiltStuck = why == "stuck"
        buildFresh()
    }

    private var rebuiltStuck = false

    private fun releaseReal(r: Real) {
        if (r.released) return
        r.released = true
        noteOwn(r)
        r.out.release()
    }

    // Learns the route's head quantum and change interval from 2 ms reads
    // in a window that starts 300 ms after the first music cycle.
    private fun learn(r: Real, now: Long, due: Long) {
        if (!r.music) return
        var l = r.learning
        if (l == null) {
            if (r.measure.known && r.measure.intervalNanos > 0) return
            // The window starts after the first music cycle, not the first
            // write (the track may hold its start back a while).
            if (r.firstCycleNanos < 0) return
            l = headLearning(r.firstCycleNanos)
            r.learning = l
        }
        if (l.done) return
        // Only the scheduled 2 ms reads are learned from; any other wake just
        // keeps the reads going.
        if (due >= 0) {
            val presented = if (r.out.timestamp(ts) && ts.frame >= 0) ts.frame + (now - ts.nanos) * mixRate / 1_000_000_000L else -1L
            val ended = l.read(r.head, now, due, r.written - r.head, r.out.bufferFrames, r.out.underruns(), r.measure, r.measure, presented)
            if (ended) {
                r.route?.let { routes[it] = r.measure }
                if (r.measure.known) setThreshold(r)
                return
            }
        } else if (r.learnDue > now) {
            return
        }
        r.learnDue = now + tuning.headReadMs * 1_000_000
        loop.postAt(r.learnDue, r.learnTask)
    }

    private fun learnTick(r: Real) {
        if (cur !== r || r.paused) return
        val now = loop.nanoTime()
        r.readHead(now)
        learn(r, now, r.learnDue)
    }

    // A re-route: the new device keys the route's measures and the start
    // threshold is set again; learning starts over 300 ms later.
    private fun routed(r: Real) {
        val key = r.out.routedDevice()
        if (key == r.route) return
        r.route = key
        r.measure = key?.let { routes.getOrPut(it) { RouteMeasure() } } ?: RouteMeasure()
        r.learning = headLearning(loop.nanoTime())
        setThreshold(r)
    }

    // A learning window starting 300 ms after `fromNanos`.
    private fun headLearning(fromNanos: Long) = HeadLearning(
        fromNanos + tuning.learnDelayMs * 1_000_000,
        tuning.learnMinMs * 1_000_000,
        tuning.learnMaxMs * 1_000_000,
        tuning.learnSpans,
        (tuning.lateReadMs * 1_000_000).toLong(),
        mixRate,
    )

    // Octo's own output events (a pause, stop or release of an output the
    // gate had signalled), for the gate's bound classifier.
    // Written on the mix thread, read on the gate thread.
    private fun noteOwn(r: Real) {
        if (!r.signalled) return
        val now = loop.nanoTime()
        synchronized(ownEvents) {
            ownEvents[ownEventCount % ownEvents.size] = now
            ownEventCount++
        }
    }

    private fun ownEventBetween(from: Long, to: Long): Boolean = synchronized(ownEvents) {
        val n = min(ownEventCount, ownEvents.size)
        (0 until n).any { ownEvents[it] in from..to }
    }

    private fun otherArrivedBetween(from: Long, to: Long): Boolean = synchronized(otherArrivals) {
        val n = min(otherArrivalCount, otherArrivals.size)
        (0 until n).any { otherArrivals[it] in from..to }
    }

    // Pools

    private fun obtain(): Block = spareBlocks.removeLastOrNull() ?: Block()

    private fun recycle(b: Block) {
        b.outCount = 0
        for (s in b.state) s.clearRefs()
        spareBlocks.addLast(b)
    }

    private fun ringCapacityFor(realFrames: Int): Int {
        val leadFrames = (realFrames - realFrames / 4) + (tuning.marginCapMs * mixRate / 1_000).toInt() + realFrames / 4 + blockFrames
        return realTargetFrames + realFrames + leadFrames + 8 * blockFrames
    }

    // For tests and the debug rig, on the mix thread.
    internal val mixFrame: Long get() = mixPoint
    internal val written: Long get() = writePoint
    internal val sessionState: BlendState get() = session.state
    internal val marginFrames: Long get() = timing.proposeAtFrames - timing.commitAtFrames

    private companion object {
        const val ROLE_NONE = 0
        const val ROLE_A = 1
        const val ROLE_B = 2
        const val DRY_NORMAL = 0
        const val DRY_DRYING = 1
        const val DRY_DRY = 2
        const val GATE_IDLE = 0
        const val GATE_WAITING = 1
        const val GATE_SLACK = 2
        const val GATE_OPEN = 3
        const val WRITE_FAILED = -1
        const val BLEND_NO_FOLLOW = Long.MIN_VALUE
    }
}

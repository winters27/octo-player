package app.winters.octo.playback

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// One blend's life on a phone, decided on the thread that mixes (Android)
// or renders (iPhone) the audio. Everything it hears carries an output
// frame; everything it decides is an action for that thread, carrying the
// output frame it lands on, or an event for the main thread.
//
// The audio thread posts each deck's inputs as it finds them (the two
// decks in any order, each deck's own in its order) and calls advance once
// per mix block (on iPhone once both decks have rendered at least that
// far), also when nothing was mixed: one call is one block. Inputs are
// taken in frame order up to the frame advance names. The main thread's
// side (arm, disarm, a failure, a follow-up answer, A stopped) reaches the
// same thread as messages and is applied between advances. Actions are
// taken with takeAction and applied at once, each at the later of its
// frame and the caller's own position; events are taken with takeEvent and
// posted to main.
//
// Nothing allocates after construction: inputs, actions and events live in
// fixed arrays, so the iPhone copy can run on the render thread.
class BlendSession(private val word: CommitWord, val timing: BlendTiming) {
    var state: BlendState = BlendState.IDLE
        private set

    var generation: Long = -1
        private set

    // The plan's start in A's song time, moves included.
    var startMs: Double = 0.0
        private set

    // What holds have added to the blend, in A's song time.
    var extensionMs: Double = 0.0
        private set

    // Output frame S, B's first frame, once decided; -1 before.
    var startFrame: Long = -1
        private set

    // Inputs and events that found their fixed buffers full.
    var lost: Int = 0
        private set

    private var overlapMs = 0.0
    private var barMs = Double.NaN
    private var holdShare = 0.0
    private var bothHeardStartMs = 0.0
    private var bothHeardEndMs = 0.0
    private var lockMs = 0.0
    private var driftGap = 0.0
    private var latestEndMs = 0.0
    private var outgoingEndMs = 0.0
    private var speed = 1.0
    private var commitBeforeAck = false
    private var lateMoveBars = 0

    // Whole bars the blend has moved later so far, and the bars to a hold's
    // room deadline.
    private var movedBars = 0
    private var deadlineBars = 0

    // A's song time per output frame.
    private var msPerFrame = 1.0
    private val locked: Boolean get() = !barMs.isNaN()

    // Where the session stands: every frame before `point` is decided; the
    // head and A's song time come with each advance.
    private var point = 0L
    private var head = 0L
    private var outgoingMs = Double.NaN

    // The earliest frame an action can still land on in the current call.
    private var soonest = 0L
    private var calls = 0L

    private var incomingReady = false
    private var committed = false
    private var acked = false
    private var holdFrom = -1L
    private var deadline = -1L
    private var endAt = -1L
    private var endFrame = -1L
    private var heldNextAt = -1L

    // The output frame just past A's last frame of its song, once known.
    private var decodedEnd = -1L

    // B's song time since entry over the rate (in A's ms) is runMs at
    // runFrom, growing with the frames after it unless B is stopped.
    private var runFrom = 0L
    private var runMs = 0.0
    private var stopped = false

    // A's song time since the plan's start is anchorSongMs at anchorFrame,
    // growing by msPerFrame a frame (rebased at each pace change). Its blend
    // time is that less the extensions, never below where a hold froze it.
    private var anchorFrame = 0L
    private var anchorSongMs = 0.0
    private var frozenBlendMs = 0.0

    // The blend time a speed drop cuts the blend at; NaN when none.
    private var dropCutMs = Double.NaN

    // An undone start waiting to hear what A plays after its end.
    private var askGen = -1L
    private var askHeldAt = -1L
    private var askReadyAt = -1L
    private var asked = false

    private val inDeck = Array(INPUT_SLOTS) { BlendDeck.A }
    private val inKind = Array(INPUT_SLOTS) { BlendInput.READY }
    private val inFrame = LongArray(INPUT_SLOTS)
    private val inValue = LongArray(INPUT_SLOTS)
    private var inCount = 0

    private val acKind = Array(ACTION_SLOTS) { BlendActionKind.START_B }
    private val acFrame = LongArray(ACTION_SLOTS)
    private val acValue = LongArray(ACTION_SLOTS)
    private val acMs = DoubleArray(ACTION_SLOTS)
    private var acRead = 0
    private var acCount = 0

    private val evKind = Array(EVENT_SLOTS) { BlendEventKind.PROPOSED }
    private val evGen = LongArray(EVENT_SLOTS)
    private val evFrame = LongArray(EVENT_SLOTS)
    private val evMs = DoubleArray(EVENT_SLOTS)
    private val evMs2 = DoubleArray(EVENT_SLOTS)
    private val evCause = Array(EVENT_SLOTS) { BlendCause.NONE }
    private val evNow = BooleanArray(EVENT_SLOTS)
    private var evCount = 0

    // Audio thread

    // Queues what a deck found at `frame`. False when the buffer is full.
    fun post(deck: BlendDeck, input: BlendInput, frame: Long, value: Long = 0): Boolean {
        if (inCount == INPUT_SLOTS) {
            lost++
            return false
        }
        var at = inCount
        while (at > 0 && inFrame[at - 1] > frame) {
            inDeck[at] = inDeck[at - 1]
            inKind[at] = inKind[at - 1]
            inFrame[at] = inFrame[at - 1]
            inValue[at] = inValue[at - 1]
            at--
        }
        inDeck[at] = deck
        inKind[at] = input
        inFrame[at] = frame
        inValue[at] = value
        inCount++
        return true
    }

    // Takes every input before `to`, in frame order, then decides what the
    // head allows. `head` is the real output's stepped head (the rendered
    // bus frame on iPhone) and `outgoingMs` A's song time of its next frame
    // to mix at `to`.
    fun advance(to: Long, head: Long, outgoingMs: Double) {
        calls++
        this.head = head
        soonest = to
        var taken = 0
        while (taken < inCount && inFrame[taken] < to) {
            val frame = inFrame[taken]
            timers(frame)
            take(inDeck[taken], inKind[taken], frame, inValue[taken])
            taken++
        }
        if (taken > 0) {
            for (i in taken until inCount) {
                inDeck[i - taken] = inDeck[i]
                inKind[i - taken] = inKind[i]
                inFrame[i - taken] = inFrame[i]
                inValue[i - taken] = inValue[i]
            }
            inCount -= taken
        }
        timers(to)
        point = to
        this.outgoingMs = outgoingMs
        if (askGen >= 0 && !asked && askReadyAt >= 0 && calls > askReadyAt) {
            event(BlendEventKind.FOLLOW_UP_NEEDED, to, now = true, gen = askGen)
            asked = true
        }
        headRules()
    }

    // Main thread's side, applied between advances

    // A new plan. The registering thread has opened its generation on the
    // word. A plan still registered is disarmed first.
    fun arm(arm: BlendArm) {
        soonest = point
        if (state != BlendState.IDLE) disarm(BlendDisarm.EDIT, point)
        generation = arm.generation
        startMs = arm.startMs
        overlapMs = arm.overlapMs
        barMs = if (arm.barMs > 0) arm.barMs else Double.NaN
        holdShare = arm.holdShare
        bothHeardStartMs = arm.bothHeardStartMs
        bothHeardEndMs = arm.bothHeardEndMs
        lockMs = arm.lockMs
        driftGap = arm.driftGap
        frozenBlendMs = 0.0
        dropCutMs = Double.NaN
        latestEndMs = arm.latestEndMs
        outgoingEndMs = arm.outgoingEndMs
        speed = arm.speed
        commitBeforeAck = arm.commitBeforeAck
        lateMoveBars = arm.lateMoveBars
        movedBars = 0
        deadlineBars = 0
        msPerFrame = arm.speed * 1_000.0 / timing.sampleRate
        state = BlendState.ARMED
        incomingReady = false
        startFrame = -1
        committed = false
        acked = false
        extensionMs = 0.0
        holdFrom = -1
        deadline = -1
        endAt = -1
        endFrame = -1
        heldNextAt = -1
        decodedEnd = -1
        runFrom = 0
        runMs = 0.0
        stopped = false
        // Any earlier undo's answer has been sent by now unless A holds an end.
        if (askGen >= 0 && askHeldAt < 0) askGen = -1
        if (!outgoingMs.isNaN()) {
            val predicted = point + frames((startMs - outgoingMs) / msPerFrame)
            if (predicted - head < timing.proposalLeadFrames) lateStart(predicted, point)
        }
    }

    // An interrupt by the phase rule: before B's start was mixed B never
    // starts; mixed but not handed over the start is undone; after the
    // handover A leaves and B plays on alone. `frame` is where it applies
    // (after the handover, the written point); `settledHead` is where a
    // pause's hardware stopped.
    fun disarm(reason: BlendDisarm, frame: Long = point, settledHead: Long = frame) {
        soonest = point
        if (state == BlendState.IDLE) return
        if (startFrame < 0) {
            action(BlendActionKind.A_DISARM, frame)
            retire()
            return
        }
        if (!handedOver()) word.cancel(generation)
        if (handedOver()) {
            if (reason == BlendDisarm.PAUSE) afterAckPause(settledHead) else afterAckEdit(frame)
        } else {
            undo(frame)
            openAsk()
        }
        retire()
    }

    // A deck's player error or stop, heard on main. B's failure carries the
    // follow-up answer.
    fun failed(deck: BlendDeck, followUp: Long = BLEND_NO_ANSWER) {
        soonest = point
        if (state == BlendState.IDLE) return
        if (startFrame < 0) {
            action(BlendActionKind.A_DISARM, point)
            retire()
            return
        }
        if (!handedOver()) word.cancel(generation)
        if (handedOver()) {
            if (state == BlendState.ENDED) return
            val at = max(point, startFrame)
            if (deck == BlendDeck.A) {
                val ramp = rampFrames(BLEND_DISARM_RAMP_MS)
                action(BlendActionKind.A_END, at)
                action(BlendActionKind.B_DISARM, at, ramp)
                endBlend(at, incomingMs(at), ramp)
            } else {
                val ramp = rampFrames(BLEND_LEAVE_RAMP_MS)
                action(BlendActionKind.A_LEAVE, at, ramp)
                endBlend(at, incomingMs(at), ramp)
            }
            return
        }
        undo(point)
        if (deck == BlendDeck.B) {
            openAsk()
            if (followUp != BLEND_NO_ANSWER) answer(followUp)
        }
        retire()
    }

    // What A plays after its end, for an undone start. The first answer for
    // a generation wins.
    fun followUp(generation: Long, pass: Long) {
        soonest = point
        if (askGen >= 0 && generation == askGen) answer(pass)
    }

    // The Crossfader stopped A: the generation retires.
    // The listener's pace became `pace` from `switchFrame`, the first frame
    // carrying it. A's and B's clocks run at the new pace from there, and
    // the blend is cut at the first moment of its blend time (holds mapped)
    // where the beats would drift past the bound at that pace; a pace that
    // keeps them within it clears such a cut.
    fun paceChanged(pace: Double, switchFrame: Long) {
        soonest = point
        if (state == BlendState.IDLE) return
        if (startFrame >= 0) {
            anchorSongMs = songSince(switchFrame)
            anchorFrame = switchFrame
            if (!stopped && switchFrame >= runFrom) {
                runMs = incomingMs(switchFrame)
                runFrom = switchFrame
            }
        }
        speed = pace
        msPerFrame = pace * 1_000.0 / timing.sampleRate
        if (startFrame < 0 || state == BlendState.ENDED) return
        dropCutMs = blendDropCutAtMs(bothHeardStartMs, bothHeardEndMs, holdShare * overlapMs, lockMs, driftGap, pace, blendAt(switchFrame))
    }

    fun outgoingStopped(generation: Long) {
        if (generation == this.generation && state != BlendState.IDLE) retire()
    }

    // The output was cleared or rebuilt from `fromFrame`: the events for
    // those frames post at once.
    fun cleared(fromFrame: Long) {
        for (i in 0 until evCount) if (evFrame[i] >= fromFrame) evNow[i] = true
    }

    // Outputs

    fun takeAction(out: BlendAction): Boolean {
        if (acRead == acCount) {
            acRead = 0
            acCount = 0
            return false
        }
        out.kind = acKind[acRead]
        out.frame = acFrame[acRead]
        out.value = acValue[acRead]
        out.ms = acMs[acRead]
        acRead++
        return true
    }

    // The next event main may hear with the output's head at `head`: per
    // generation in frame order, each once its frame has passed the head or
    // it is due at once (a proposal, a question, anything a clear removed),
    // which also lets out the generation's earlier ones.
    fun takeEvent(head: Long, out: BlendEvent): Boolean {
        for (i in 0 until evCount) {
            val gen = evGen[i]
            var first = true
            for (j in 0 until i) {
                if (evGen[j] == gen) {
                    first = false
                    break
                }
            }
            if (!first) continue
            var due = false
            for (j in i until evCount) {
                if (evGen[j] == gen && (evNow[j] || evFrame[j] <= head)) {
                    due = true
                    break
                }
            }
            if (!due) continue
            out.kind = evKind[i]
            out.generation = evGen[i]
            out.frame = evFrame[i]
            out.ms = evMs[i]
            out.ms2 = evMs2[i]
            out.cause = evCause[i]
            removeEvent(i)
            return true
        }
        return false
    }

    // Rules

    // Frame-timed rules: the room deadline of a hold and the blend end, each
    // decided while its frame is still a placement horizon away.
    private fun timers(frame: Long) {
        val horizon = timing.placementHorizonFrames
        if (state == BlendState.HELD && frame > deadline - horizon) deadlinePassed(frame)
        val running = state == BlendState.MIXING || state == BlendState.STALLED
        if (running && !dropCutMs.isNaN()) {
            val cutAt = frameAtBlend(dropCutMs)
            if (frame > cutAt - horizon) dropCut(max(cutAt, soonest))
        }
        if (endAt >= 0 && running && frame > endAt - horizon) blendEndReached()
    }

    // The speed drop's cut: after the handover A leaves over 20 ms and B
    // goes to its own level over 250 ms; before it, the start is undone as
    // by the session's own disarm.
    private fun dropCut(at: Long) {
        dropCutMs = Double.NaN
        if (!handedOver()) word.cancel(generation)
        if (handedOver()) {
            val ramp = rampFrames(BLEND_DISARM_RAMP_MS)
            action(BlendActionKind.A_LEAVE, at, rampFrames(BLEND_LEAVE_RAMP_MS))
            action(BlendActionKind.B_DISARM, at, ramp)
            endBlend(at, incomingMs(at), ramp)
            return
        }
        undo(at)
        event(BlendEventKind.CANCELLED, at, cause = BlendCause.PACE)
        openAsk()
        event(BlendEventKind.FOLLOW_UP_NEEDED, at, now = true)
        asked = true
        retire()
    }

    // Head-timed rules: A's real end, then the late start while armed; the
    // proposal, then the commit or the floor's cancel once a start is
    // decided.
    private fun headRules() {
        if (state == BlendState.ARMED) {
            if (!outgoingMs.isNaN() && word.stateOf(generation) == CommitState.INITIAL) {
                val predicted = point + frames((startMs - outgoingMs) / msPerFrame)
                if (decodedEnd >= 0 && predicted >= decodedEnd) {
                    // A's song ends before the start: A plays on into its
                    // next song, never a start it does not reach.
                    cancelBeforeStart(point, BlendCause.A_MOVED_ON)
                } else if (predicted - head < timing.proposalLeadFrames) {
                    lateStart(predicted, point)
                }
            }
            return
        }
        if (state == BlendState.IDLE || startFrame < 0) return
        if (!acked) handedOver()
        if (committed) return
        val ahead = startFrame - head
        if (ahead <= timing.commitAtFrames) {
            commitPoint()
        } else if (ahead <= timing.proposeAtFrames && word.propose(generation)) {
            event(BlendEventKind.PROPOSED, startFrame, now = true)
        }
    }

    private fun take(deck: BlendDeck, input: BlendInput, frame: Long, value: Long) {
        if (deck == BlendDeck.B) {
            when (input) {
                BlendInput.READY -> incomingReady = true
                BlendInput.DRY, BlendInput.PAUSED -> incomingReady = false
                else -> {}
            }
        }
        when (state) {
            BlendState.IDLE -> if (deck == BlendDeck.A && input == BlendInput.MOVED_ON) holdForAnswer(frame)
            BlendState.ARMED -> armedInput(deck, input, frame, value)
            BlendState.ENDED -> if (input == BlendInput.PAUSED) paused(frame, value)
            else -> mixedInput(deck, input, frame, value)
        }
    }

    private fun armedInput(deck: BlendDeck, input: BlendInput, frame: Long, value: Long) {
        if (input == BlendInput.PAUSED) {
            // A deck's own pause in the run-up: disarmed, and main hears it
            // at once while the output is paused.
            action(BlendActionKind.A_DISARM, frame)
            event(BlendEventKind.CANCELLED, frame, cause = BlendCause.PAUSED, now = true)
            retire()
            return
        }
        if (deck == BlendDeck.B) return
        when (input) {
            BlendInput.DECODED_END -> decodedEnd = value
            BlendInput.START_AT -> startAt(frame, value)
            BlendInput.MOVED_ON -> {
                holdForAnswer(frame)
                cancelBeforeStart(frame, BlendCause.A_MOVED_ON)
            }
            BlendInput.ENDED -> cancelBeforeStart(frame, BlendCause.A_ENDED)
            else -> {}
        }
    }

    private fun cancelBeforeStart(frame: Long, cause: BlendCause) {
        action(BlendActionKind.A_DISARM, frame)
        event(BlendEventKind.CANCELLED, frame, cause = cause)
        retire()
    }

    // A's frame at the start is known at output frame `s`.
    private fun startAt(frame: Long, s: Long) {
        if (word.stateOf(generation) != CommitState.INITIAL) return
        if (decodedEnd in 0..s) {
            cancelBeforeStart(frame, BlendCause.A_MOVED_ON)
            return
        }
        if (s - head < timing.proposalLeadFrames) {
            lateStart(s, frame)
            return
        }
        if (!incomingReady) {
            // A's gain at S is its first value, so the disarm is unheard.
            action(BlendActionKind.A_DISARM, s)
            event(BlendEventKind.INCOMING_NOT_READY, s, cause = BlendCause.NOT_READY)
            retire()
            return
        }
        startFrame = s
        state = BlendState.MIXING
        anchorFrame = s
        anchorSongMs = 0.0
        runFrom = s
        runMs = 0.0
        stopped = false
        action(BlendActionKind.START_B, s)
        action(BlendActionKind.LIMITER_ON, s)
    }

    // A start that can no longer be decided the proposal lead ahead of the
    // head moves to A's first bar line (or, with no bar grid, the first
    // frame) that far ahead, while the plan's bar budget and its latest end
    // allow (with no bar grid, any budget but 0 and the latest end);
    // otherwise B does not start. A move past A's known end is no move: A
    // plays on into its next song.
    private fun lateStart(predicted: Long, at: Long) {
        val nowMs = startMs + (head + timing.proposalLeadFrames - predicted) * msPerFrame
        val bars = if (locked) max(1.0, ceil((nowMs - startMs) / barMs)) else 0.0
        val target = if (locked) startMs + bars * barMs else nowMs
        if (decodedEnd >= 0 && predicted + frames((target - startMs) / msPerFrame) >= decodedEnd) {
            cancelBeforeStart(at, BlendCause.A_MOVED_ON)
            return
        }
        val fits = if (locked) {
            bars <= allowedBars().toDouble()
        } else {
            lateMoveBars != 0 && target + overlapMs + extensionMs <= latestEndMs
        }
        if (fits) {
            if (locked) movedBars += bars.toInt()
            startMs = target
            action(BlendActionKind.MOVE_START, at, ms = target)
            moved(at)
        } else {
            action(BlendActionKind.A_DISARM, at)
            event(BlendEventKind.INCOMING_NOT_READY, at, cause = BlendCause.LATE)
            retire()
        }
    }

    private fun commitPoint() {
        when (word.stateOf(generation)) {
            CommitState.ACKED -> commit()
            CommitState.INITIAL, CommitState.PROPOSED -> when {
                commitBeforeAck -> commit()
                word.cancel(generation) -> floorCancel()
                handedOver() -> commit()
            }
            else -> {}
        }
    }

    private fun commit() {
        handedOver()
        committed = true
        action(BlendActionKind.COMMIT_B, startFrame)
    }

    // Main has not acked by the floor: B's start is dropped as not ready.
    // An A already past its end plays the next pass it holds, as its own
    // advance, since no edit on main can have won.
    private fun floorCancel() {
        action(BlendActionKind.UNDO_START, startFrame)
        action(BlendActionKind.LIMITER_OFF, startFrame)
        if (heldNextAt >= 0) action(BlendActionKind.A_FOLLOW, soonest, BLEND_PASS_HELD)
        dropEvents(generation, startFrame)
        event(BlendEventKind.INCOMING_NOT_READY, startFrame, cause = BlendCause.FLOOR)
        retire()
    }

    private fun mixedInput(deck: BlendDeck, input: BlendInput, frame: Long, value: Long) {
        if (input == BlendInput.PAUSED) {
            paused(frame, value)
            return
        }
        // On iPhone a start can be decided before S: what happens before it
        // counts from S.
        val at = max(frame, startFrame)
        if (deck == BlendDeck.B) {
            when (input) {
                BlendInput.DRY -> if (state == BlendState.MIXING && at >= runFrom) incomingDry(at)
                BlendInput.READY -> when (state) {
                    BlendState.HELD -> place(at)
                    BlendState.STALLED -> cut(at)
                    else -> {}
                }
                else -> {}
            }
            return
        }
        when (input) {
            // With B already stopped this is the second of two dry decks: a
            // plain rebuffer.
            BlendInput.DRY -> if (state == BlendState.MIXING) outgoingDry(at)
            BlendInput.ENDED, BlendInput.MOVED_ON -> outgoingEnded(frame, at, input == BlendInput.MOVED_ON)
            BlendInput.END_REACHED -> if (state != BlendState.HELD) endAt = value
            BlendInput.RESTARTED -> restarted(frame, value)
            else -> {}
        }
    }

    // B ran dry at `at`. Inside A's hold (any kind with a hold: an intro, a
    // lead-in), with room, B is held for placement on a later bar and A's
    // blend time stops; otherwise A fades on and B waits for the clean cut.
    private fun incomingDry(at: Long) {
        stop(at)
        val blendMs = blendAt(at)
        val room = if (holdShare > 0 && blendMs < holdShare * overlapMs) roomDeadline(at) else -1L
        action(BlendActionKind.HOLD_B, at)
        if (room >= 0) {
            state = BlendState.HELD
            holdFrom = at
            deadline = room
            frozenBlendMs = blendMs
            action(BlendActionKind.FREEZE_A, at)
        } else {
            state = BlendState.STALLED
        }
    }

    // The latest placement for B stopped at `stop` that still ends the
    // extended blend by the plan's latest end while A has more than its
    // ring's lead plus the sink buffer plus the spare left (in output time);
    // -1 when none is past the horizon. On the bar grid placements are whole
    // bars from the stop.
    private fun roomDeadline(stop: Long): Long {
        val leftMs = timing.ringLeadMs + timing.sinkBufferMs + BLEND_ROOM_SPARE_MS
        deadlineBars = 0
        val latest = if (locked) {
            val bar = barMs / msPerFrame
            val most = min(allowedBars(), MOST_HOLD_BARS)
            var best = -1L
            var bars = 1
            while (bars <= most) {
                val at = stop + frames(bars * bar)
                if (!leaves(at, leftMs)) break
                best = at
                deadlineBars = bars
                bars++
            }
            best
        } else if (lateMoveBars == 0) {
            // An unlocked plan that may not move has no hold extension.
            -1L
        } else {
            val byEnd = stop + floorFrames((latestEndMs - startMs - overlapMs - extensionMs) / msPerFrame)
            val byLeft = anchorFrame + ceilFrames((outgoingEndMs - leftMs * speed - startMs - anchorSongMs) / msPerFrame) - 1
            min(byEnd, byLeft)
        }
        return if (latest >= stop + timing.placementHorizonFrames) latest else -1L
    }

    private fun leaves(at: Long, leftMs: Double): Boolean =
        (outgoingEndMs - (startMs + songSince(at))) / speed > leftMs

    // A's song time since the plan's start at `frame`.
    private fun songSince(frame: Long): Double = anchorSongMs + (frame - anchorFrame) * msPerFrame

    // A's blend time at `frame`: frozen while B is held, after a hold the
    // song time less the extensions.
    private fun blendAt(frame: Long): Double =
        if (state == BlendState.HELD) frozenBlendMs else max(songSince(frame) - extensionMs, frozenBlendMs)

    // The frame where A's blend time reaches `blendMs`, the extensions so
    // far included.
    private fun frameAtBlend(blendMs: Double): Long = anchorFrame + frames((blendMs + extensionMs - anchorSongMs) / msPerFrame)

    // B is ready again before the deadline: its next frame lands the fewest
    // whole bars after its stop that reach past the placement horizon (at
    // the horizon with no bar grid), and A's blend time runs again there.
    // A placement past the deadline is the deadline passing, and B, ready,
    // comes in by the clean cut.
    private fun place(ready: Long) {
        val earliest = max(ready + timing.placementHorizonFrames, soonest)
        val at: Long
        val added: Double
        var bars = 0L
        if (locked) {
            val bar = barMs / msPerFrame
            bars = max(1L, ceil((earliest - holdFrom) / bar).toLong())
            while (bars > 1 && holdFrom + frames((bars - 1) * bar) >= earliest) bars--
            while (holdFrom + frames(bars * bar) < earliest) bars++
            at = holdFrom + frames(bars * bar)
            added = bars * barMs
        } else {
            at = earliest
            added = (at - holdFrom) * msPerFrame
        }
        if (at > deadline) {
            deadlinePassed(ready)
            cut(ready)
            return
        }
        movedBars += bars.toInt()
        extensionMs += added
        action(BlendActionKind.PLACE_B, at)
        action(BlendActionKind.UNFREEZE_A, at, ms = extensionMs)
        moved(ready)
        runFrom = at
        stopped = false
        state = BlendState.MIXING
        holdFrom = -1
        deadline = -1
    }

    // B was not ready by the deadline: A's blend time runs again there and
    // B waits for the clean cut.
    private fun deadlinePassed(frame: Long) {
        val at = max(deadline, soonest)
        extensionMs += (at - holdFrom) * msPerFrame
        if (locked) movedBars += deadlineBars
        action(BlendActionKind.UNFREEZE_A, at, ms = extensionMs)
        moved(frame)
        state = BlendState.STALLED
        holdFrom = -1
        deadline = -1
    }

    // B is ready again with A fading: one mix point where A leaves and B
    // comes in from where it stopped, its shaping disarmed.
    private fun cut(ready: Long) {
        val at = max(ready + timing.placementHorizonFrames, soonest)
        val ramp = rampFrames(BLEND_LEAVE_RAMP_MS)
        action(BlendActionKind.CUT, at, ramp)
        runFrom = at
        stopped = false
        endBlend(at, runMs, ramp)
    }

    // A ran dry in the mixed stretch: the blend ends here and B goes to its
    // own level.
    private fun outgoingDry(at: Long) {
        val from = max(at, soonest)
        val ramp = rampFrames(BLEND_DISARM_RAMP_MS)
        action(BlendActionKind.A_END, at)
        action(BlendActionKind.B_DISARM, from, ramp)
        endBlend(from, incomingMs(from), ramp)
    }

    // A's song ended before the planned blend end: the blend ends at its
    // last frame, and anything of A's next pass is held, never mixed.
    private fun outgoingEnded(frame: Long, at: Long, movedOn: Boolean) {
        if (movedOn) {
            action(BlendActionKind.A_HOLD_NEXT, frame)
            heldNextAt = frame
        }
        val from = max(at, soonest)
        val ramp = rampFrames(BLEND_DISARM_RAMP_MS)
        action(BlendActionKind.B_DISARM, from, ramp)
        endBlend(from, incomingMs(from), ramp)
    }

    // A's frame at the (extended) blend end. With B stalled the blend ends
    // there too and B simply comes in when it can.
    private fun blendEndReached() {
        val at = max(endAt, soonest)
        endAt = -1
        action(BlendActionKind.A_END, at)
        if (state == BlendState.STALLED) {
            val ramp = rampFrames(BLEND_DISARM_RAMP_MS)
            action(BlendActionKind.B_DISARM, at, ramp)
            endBlend(at, incomingMs(at), ramp)
        } else {
            // B consumed nothing while held, so after a hold this is still
            // the unextended overlap.
            endBlend(at, incomingMs(at), 0)
        }
    }

    // iPhone: an engine restart re-placed a start that has not played. Not
    // yet committed, it simply moves (the commit point decides). Committed
    // and now inside the commit deadline, it moves to A's bar line the
    // fewest whole bars on that reaches the deadline, or to the placement
    // horizon with no bar grid; with no such bar line before A's end or
    // within the plan's bar budget (with no bar grid, a budget of 0 or a
    // blend that would end past the latest end), B starts at the horizon by
    // the clean cut (a committed start cannot be cancelled).
    private fun restarted(frame: Long, s: Long) {
        if (head >= startFrame) return
        if (!committed && !handedOver()) {
            replace(s, commitNow = false)
            return
        }
        val earliest = head + timing.commitAtFrames
        if (s >= earliest) {
            replace(s, commitNow = false)
            return
        }
        if (!locked && lateMoveBars != 0) {
            val at = max(soonest + timing.placementHorizonFrames, s)
            val movedMs = startMs + (at - s) * msPerFrame
            if (movedMs + overlapMs + extensionMs <= latestEndMs) {
                startMs = movedMs
                action(BlendActionKind.MOVE_START, frame, ms = startMs)
                replace(at, commitNow = true)
                moved(frame)
                return
            }
        }
        if (locked) {
            val bar = barMs / msPerFrame
            var bars = max(1L, ceil((earliest - s) / bar).toLong())
            while (bars > 1 && s + frames((bars - 1) * bar) >= earliest) bars--
            while (s + frames(bars * bar) < earliest) bars++
            val at = s + frames(bars * bar)
            val movedMs = startMs + bars * barMs
            if (bars <= allowedBars() && movedMs < outgoingEndMs) {
                movedBars += bars.toInt()
                startMs = movedMs
                action(BlendActionKind.MOVE_START, frame, ms = movedMs)
                replace(at, commitNow = true)
                moved(frame)
                return
            }
        }
        val cutAt = soonest + timing.placementHorizonFrames
        val ramp = rampFrames(BLEND_LEAVE_RAMP_MS)
        replace(cutAt, commitNow = true)
        action(BlendActionKind.CUT, cutAt, ramp)
        endBlend(cutAt, 0.0, ramp)
    }

    private fun replace(s: Long, commitNow: Boolean) {
        startFrame = s
        anchorFrame = s
        anchorSongMs = 0.0
        runFrom = s
        action(BlendActionKind.START_B, s)
        action(BlendActionKind.LIMITER_ON, s)
        if (commitNow || committed) {
            committed = true
            action(BlendActionKind.COMMIT_B, s)
        }
    }

    // A deck's own pause with frames left, in the mixed stretch: the
    // session's own disarm by the phase rule.
    private fun paused(frame: Long, settledHead: Long) {
        if (!handedOver()) word.cancel(generation)
        if (handedOver()) {
            afterAckPause(settledHead)
            retire()
            return
        }
        undo(frame)
        event(BlendEventKind.CANCELLED, frame, cause = BlendCause.PAUSED, now = true)
        openAsk()
        event(BlendEventKind.FOLLOW_UP_NEEDED, frame, now = true)
        asked = true
        retire()
    }

    // Before the handover: B's start is undone (its frames never written),
    // or, for a start committed before the acknowledgement, B's rendered
    // frames fade out. The generation's events from S on are dropped.
    private fun undo(frame: Long) {
        if (committed) {
            val at = max(frame, soonest)
            val ramp = rampFrames(BLEND_DISARM_RAMP_MS)
            action(BlendActionKind.FADE_OUT_B, at, ramp)
            action(BlendActionKind.A_DISARM, at, ramp)
            action(BlendActionKind.LIMITER_OFF, at + ramp)
        } else {
            action(BlendActionKind.UNDO_START, startFrame)
            action(BlendActionKind.LIMITER_OFF, startFrame)
        }
        dropEvents(generation, startFrame)
    }

    // After the handover, an interrupt that releases nothing: A leaves at
    // the later of its frame and S. Before S, A plays alone to S and B
    // enters there disarmed, so no silence opens before B.
    private fun afterAckEdit(frame: Long) {
        if (!reopenEnd(frame)) return
        if (frame < startFrame) {
            val ramp = rampFrames(BLEND_LEAVE_RAMP_MS)
            action(BlendActionKind.CUT, startFrame, ramp)
            endBlend(startFrame, 0.0, ramp)
        } else {
            val ramp = rampFrames(BLEND_DISARM_RAMP_MS)
            action(BlendActionKind.A_LEAVE, frame, rampFrames(BLEND_LEAVE_RAMP_MS))
            action(BlendActionKind.B_DISARM, frame, ramp)
            endBlend(frame, incomingMs(frame), ramp)
        }
    }

    // After the handover, a pause: the output is mixed again from the
    // settled head while paused, as A alone to S then B from S when S was
    // not consumed, else B alone from there. Its events post at the clear.
    private fun afterAckPause(settledHead: Long) {
        if (!reopenEnd(settledHead)) return
        action(BlendActionKind.REMIX, settledHead)
        if (settledHead < startFrame) {
            val ramp = rampFrames(BLEND_LEAVE_RAMP_MS)
            action(BlendActionKind.CUT, startFrame, ramp)
            endBlend(startFrame, 0.0, ramp, now = true)
        } else {
            val ramp = rampFrames(BLEND_DISARM_RAMP_MS)
            action(BlendActionKind.A_END, settledHead)
            action(BlendActionKind.B_DISARM, settledHead, ramp)
            endBlend(settledHead, incomingMs(settledHead), ramp, now = true)
        }
        markNow(generation)
    }

    // With the end already decided, an interrupt before it takes over from
    // it; one at or past it has nothing left to change.
    private fun reopenEnd(frame: Long): Boolean {
        if (state != BlendState.ENDED) return true
        if (frame >= endFrame) return false
        dropKind(generation, BlendEventKind.BLEND_END)
        return true
    }

    private fun endBlend(at: Long, easeFromMs: Double, rampFrames: Long, now: Boolean = false) {
        action(BlendActionKind.LIMITER_OFF, at + rampFrames)
        event(BlendEventKind.BLEND_END, at, ms = easeFromMs, now = now)
        state = BlendState.ENDED
        endFrame = at
        endAt = -1
    }

    private fun openAsk() {
        askGen = generation
        askHeldAt = heldNextAt
        askReadyAt = if (heldNextAt >= 0) calls else -1
        asked = false
    }

    private fun holdForAnswer(frame: Long) {
        if (askGen < 0 || askHeldAt >= 0) return
        action(BlendActionKind.A_HOLD_NEXT, frame)
        askHeldAt = frame
        askReadyAt = calls
    }

    private fun answer(pass: Long) {
        action(BlendActionKind.A_FOLLOW, soonest, pass)
        askGen = -1
    }

    // On the bar grid: how many more whole bars the blend may move later,
    // the bars left before the plan's latest end, within what remains of
    // its budget. Computed in floating point before any multiplication, so
    // an unbounded budget never overflows.
    private fun allowedBars(): Int {
        val roomBars = floor(max(0.0, latestEndMs - (startMs + overlapMs + extensionMs)) / barMs).toInt()
        return if (lateMoveBars == LATE_MOVE_UNBOUNDED) roomBars else min(lateMoveBars - movedBars, roomBars)
    }

    private fun handedOver(): Boolean {
        if (!acked && word.stateOf(generation) == CommitState.ACKED) acked = true
        return acked
    }

    private fun retire() {
        state = BlendState.IDLE
        startFrame = -1
        endAt = -1
        holdFrom = -1
        deadline = -1
    }

    private fun stop(at: Long) {
        runMs = incomingMs(at)
        stopped = true
    }

    private fun incomingMs(at: Long): Double =
        if (stopped) runMs else runMs + max(0L, at - runFrom) * msPerFrame

    private fun rampFrames(ms: Double): Long = frames(ms * timing.sampleRate / 1_000.0)

    // Buffers

    private fun action(kind: BlendActionKind, frame: Long, value: Long = 0, ms: Double = 0.0) {
        if (acCount == ACTION_SLOTS) {
            if (acRead == 0) {
                lost++
                return
            }
            for (i in acRead until acCount) {
                acKind[i - acRead] = acKind[i]
                acFrame[i - acRead] = acFrame[i]
                acValue[i - acRead] = acValue[i]
                acMs[i - acRead] = acMs[i]
            }
            acCount -= acRead
            acRead = 0
        }
        acKind[acCount] = kind
        acFrame[acCount] = frame
        acValue[acCount] = value
        acMs[acCount] = ms
        acCount++
    }

    // Events of a retired generation are never queued, except the question
    // an undone start still owes.
    private fun event(
        kind: BlendEventKind,
        frame: Long,
        ms: Double = 0.0,
        ms2: Double = 0.0,
        cause: BlendCause = BlendCause.NONE,
        now: Boolean = false,
        gen: Long = generation,
    ) {
        val live = (state != BlendState.IDLE && gen == generation) || (askGen >= 0 && gen == askGen)
        if (!live) return
        if (evCount == EVENT_SLOTS) {
            lost++
            return
        }
        var at = evCount
        while (at > 0 && evFrame[at - 1] > frame) {
            copyEvent(at - 1, at)
            at--
        }
        evKind[at] = kind
        evGen[at] = gen
        evFrame[at] = frame
        evMs[at] = ms
        evMs2[at] = ms2
        evCause[at] = cause
        evNow[at] = now
        evCount++
    }

    // One "moved" per generation, carrying the start and the extension as
    // they stand now.
    private fun moved(frame: Long) {
        for (i in 0 until evCount) {
            if (evGen[i] == generation && evKind[i] == BlendEventKind.MOVED) {
                evMs[i] = startMs
                evMs2[i] = extensionMs
                return
            }
        }
        event(BlendEventKind.MOVED, frame, ms = startMs, ms2 = extensionMs)
    }

    private fun dropEvents(gen: Long, fromFrame: Long) {
        var i = evCount - 1
        while (i >= 0) {
            if (evGen[i] == gen && evFrame[i] >= fromFrame) removeEvent(i)
            i--
        }
    }

    private fun dropKind(gen: Long, kind: BlendEventKind) {
        var i = evCount - 1
        while (i >= 0) {
            if (evGen[i] == gen && evKind[i] == kind) removeEvent(i)
            i--
        }
    }

    private fun markNow(gen: Long) {
        for (i in 0 until evCount) if (evGen[i] == gen) evNow[i] = true
    }

    private fun removeEvent(index: Int) {
        for (i in index + 1 until evCount) copyEvent(i, i - 1)
        evCount--
    }

    private fun copyEvent(from: Int, to: Int) {
        evKind[to] = evKind[from]
        evGen[to] = evGen[from]
        evFrame[to] = evFrame[from]
        evMs[to] = evMs[from]
        evMs2[to] = evMs2[from]
        evCause[to] = evCause[from]
        evNow[to] = evNow[from]
    }

    private companion object {
        const val INPUT_SLOTS = 64
        const val ACTION_SLOTS = 64
        const val EVENT_SLOTS = 16

        // A bound on the bars a hold may look ahead.
        const val MOST_HOLD_BARS = 64

        fun frames(x: Double): Long = floor(x + 0.5).toLong()

        // Frame counts at a bound, kept from tipping over by float rounding.
        fun floorFrames(x: Double): Long = floor(x + 1e-6).toLong()

        fun ceilFrames(x: Double): Long = ceil(x - 1e-6).toLong()
    }
}

import Foundation

/// One blend's life on a phone, decided on the thread that renders the audio. Everything it hears
/// carries a bus frame; everything it decides is an action for the renders, carrying the bus frame
/// it lands on, or an event for the main thread.
///
/// The renders post each deck's inputs as they find them (the two decks in any order, each deck's
/// own in its order) and `advance` is called once both decks have rendered at least that far, once
/// per render cycle, also when nothing was rendered: one call is one block. Inputs are taken in
/// frame order up to the frame `advance` names, so the order and chunking of the two renders never
/// change the outcome. The main thread's side (arm, disarm, a failure, a follow-up answer, A
/// stopped) arrives through a ring and is applied between advances. Actions are taken with
/// `takeAction` and applied at once, each at the later of its frame and the render's own position;
/// events are taken with `takeEvent` and posted to main.
///
/// Nothing allocates after `init`: inputs, actions and events live in fixed buffers, and no lock is
/// taken, so the render thread can own it. The shared Kotlin `BlendSession` is the same rulebook,
/// and both run blend-session-vectors.json.
public final class BlendSession: @unchecked Sendable {
    public private(set) var state: BlendState = .idle
    public private(set) var generation: Int64 = -1
    /// The plan's start in A's song time, moves included.
    public private(set) var startMs = 0.0
    /// What holds have added to the blend, in A's song time.
    public private(set) var extensionMs = 0.0
    /// Bus frame S, B's first frame, once decided; -1 before.
    public private(set) var startFrame: Int64 = -1
    /// Inputs, actions and events that found their fixed buffers full.
    public private(set) var lost = 0
    /// An undone start still waits to hear what the outgoing song plays after its end, so that song
    /// holds at its end instead of moving on.
    public var awaitsFollowUp: Bool { askGen >= 0 }
    /// The platform numbers; the caller may change them between calls.
    public var timing: BlendTiming

    private let word: CommitWord

    private var overlapMs = 0.0
    private var barMs = Double.nan
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
    /// Whole bars the blend has moved later so far, and the bars to a hold's room deadline.
    private var movedBars = 0
    private var deadlineBars = 0
    /// A's song time per bus frame.
    private var msPerFrame = 1.0
    private var locked: Bool { !barMs.isNaN }

    /// Where the session stands: every frame before `point` is decided; the head and A's song time
    /// come with each advance.
    private var point: Int64 = 0
    private var head: Int64 = 0
    private var outgoingMs = Double.nan
    /// The earliest frame an action can still land on in the current call.
    private var soonest: Int64 = 0
    private var calls: Int64 = 0

    private var incomingReady = false
    private var committed = false
    private var acked = false
    private var holdFrom: Int64 = -1
    private var deadline: Int64 = -1
    private var endAt: Int64 = -1
    private var endFrame: Int64 = -1
    private var heldNextAt: Int64 = -1

    /// The output frame just past A's last frame of its song, once known.
    private var decodedEnd: Int64 = -1

    /// B's song time since entry over the rate (in A's ms) is `runMs` at `runFrom`, growing with
    /// the frames after it unless B is stopped.
    private var runFrom: Int64 = 0
    private var runMs = 0.0
    private var stopped = false

    /// A's song time since the plan's start is `anchorSongMs` at `anchorFrame`, growing by
    /// `msPerFrame` a frame (rebased at each pace change). Its blend time is that less the
    /// extensions, never below where a hold froze it.
    private var anchorFrame: Int64 = 0
    private var anchorSongMs = 0.0
    private var frozenBlendMs = 0.0

    /// The blend time a speed drop cuts the blend at, if any.
    private var dropCutMs: Double?

    /// An undone start waiting to hear what A plays after its end.
    private var askGen: Int64 = -1
    private var askHeldAt: Int64 = -1
    private var askReadyAt: Int64 = -1
    private var asked = false

    private struct Input {
        var deck: BlendDeck
        var kind: BlendInput
        var frame: Int64
        var value: Int64
    }

    private static let inputSlots = 64
    private static let actionSlots = 64
    private static let eventSlots = 16
    /// A bound on the bars a hold may look ahead.
    private static let mostHoldBars = 64

    private let inputs: UnsafeMutablePointer<Input>
    private var inCount = 0
    private let actions: UnsafeMutablePointer<BlendAction>
    private var acRead = 0
    private var acCount = 0
    private let events: UnsafeMutablePointer<BlendEvent>
    private let eventNow: UnsafeMutablePointer<Bool>
    private var evCount = 0

    public init(word: CommitWord, timing: BlendTiming) {
        self.word = word
        self.timing = timing
        inputs = .allocate(capacity: Self.inputSlots)
        inputs.initialize(repeating: Input(deck: .a, kind: .ready, frame: 0, value: 0), count: Self.inputSlots)
        actions = .allocate(capacity: Self.actionSlots)
        actions.initialize(repeating: BlendAction(kind: .startB, frame: 0, value: 0, ms: 0), count: Self.actionSlots)
        events = .allocate(capacity: Self.eventSlots)
        events.initialize(repeating: BlendEvent(kind: .proposed, generation: 0, frame: 0, ms: 0, ms2: 0, cause: .none), count: Self.eventSlots)
        eventNow = .allocate(capacity: Self.eventSlots)
        eventNow.initialize(repeating: false, count: Self.eventSlots)
    }

    deinit {
        inputs.deallocate()
        actions.deallocate()
        events.deallocate()
        eventNow.deallocate()
    }

    // MARK: Render thread

    /// Queues what a deck found at `frame`. False when the buffer is full.
    @discardableResult
    public func post(_ deck: BlendDeck, _ input: BlendInput, frame: Int64, value: Int64 = 0) -> Bool {
        if inCount == Self.inputSlots {
            lost += 1
            return false
        }
        var at = inCount
        while at > 0 && inputs[at - 1].frame > frame {
            inputs[at] = inputs[at - 1]
            at -= 1
        }
        inputs[at] = Input(deck: deck, kind: input, frame: frame, value: value)
        inCount += 1
        return true
    }

    /// Takes every input before `to`, in frame order, then decides what the head allows. `head` is
    /// the render's bus frame and `outgoingMs` A's song time of its next frame at `to`.
    public func advance(to: Int64, head: Int64, outgoingMs: Double) {
        calls += 1
        self.head = head
        soonest = to
        var taken = 0
        while taken < inCount && inputs[taken].frame < to {
            let input = inputs[taken]
            timers(input.frame)
            take(input.deck, input.kind, input.frame, input.value)
            taken += 1
        }
        if taken > 0 {
            for i in taken..<inCount { inputs[i - taken] = inputs[i] }
            inCount -= taken
        }
        timers(to)
        point = to
        self.outgoingMs = outgoingMs
        if askGen >= 0 && !asked && askReadyAt >= 0 && calls > askReadyAt {
            event(.followUpNeeded, to, now: true, gen: askGen)
            asked = true
        }
        headRules()
    }

    // MARK: Main thread's side, applied between advances

    /// A new plan. The registering thread has opened its generation on the word. A plan still
    /// registered is disarmed first.
    public func arm(_ arm: BlendArm) {
        soonest = point
        if state != .idle { disarm(.edit, frame: point) }
        generation = arm.generation
        startMs = arm.startMs
        overlapMs = arm.overlapMs
        barMs = arm.barMs > 0 ? arm.barMs : .nan
        holdShare = arm.holdShare
        bothHeardStartMs = arm.bothHeardStartMs
        bothHeardEndMs = arm.bothHeardEndMs
        lockMs = arm.lockMs
        driftGap = arm.driftGap
        frozenBlendMs = 0
        dropCutMs = nil
        latestEndMs = arm.latestEndMs
        outgoingEndMs = arm.outgoingEndMs
        speed = arm.speed
        commitBeforeAck = arm.commitBeforeAck
        lateMoveBars = arm.lateMoveBars
        movedBars = 0
        deadlineBars = 0
        msPerFrame = arm.speed * 1_000 / Double(timing.sampleRate)
        state = .armed
        incomingReady = false
        startFrame = -1
        committed = false
        acked = false
        extensionMs = 0
        holdFrom = -1
        deadline = -1
        endAt = -1
        endFrame = -1
        heldNextAt = -1
        decodedEnd = -1
        runFrom = 0
        runMs = 0
        stopped = false
        // Any earlier undo's answer has been sent by now unless A holds an end.
        if askGen >= 0 && askHeldAt < 0 { askGen = -1 }
        if !outgoingMs.isNaN {
            let predicted = point + Self.frames((startMs - outgoingMs) / msPerFrame)
            if predicted - head < timing.proposalLeadFrames { lateStart(predicted, at: point) }
        }
    }

    /// An interrupt by the phase rule: before B's start was rendered B never starts; rendered but
    /// not handed over the start is undone; after the handover A leaves and B plays on alone.
    /// `frame` is where it applies (the session's own point when nil); `settledHead` is where a
    /// pause's hardware stopped (the frame when nil).
    public func disarm(_ reason: BlendDisarm, frame: Int64? = nil, settledHead: Int64? = nil) {
        soonest = point
        guard state != .idle else { return }
        let at = frame ?? point
        if startFrame < 0 {
            action(.aDisarm, at)
            retire()
            return
        }
        if !handedOver() { _ = word.cancel(generation) }
        if handedOver() {
            if reason == .pause { afterAckPause(settledHead ?? at) } else { afterAckEdit(at) }
        } else {
            undo(at)
            openAsk()
        }
        retire()
    }

    /// A deck's player error or stop, heard on main. B's failure carries the follow-up answer.
    public func failed(_ deck: BlendDeck, followUp: Int64 = blendNoAnswer) {
        soonest = point
        guard state != .idle else { return }
        if startFrame < 0 {
            action(.aDisarm, point)
            retire()
            return
        }
        if !handedOver() { _ = word.cancel(generation) }
        if handedOver() {
            if state == .ended { return }
            let at = max(point, startFrame)
            if deck == .a {
                let ramp = rampFrames(blendDisarmRampMs)
                action(.aEnd, at)
                action(.bDisarm, at, ramp)
                endBlend(at, incomingMs(at), ramp)
            } else {
                let ramp = rampFrames(blendLeaveRampMs)
                action(.aLeave, at, ramp)
                endBlend(at, incomingMs(at), ramp)
            }
            return
        }
        undo(point)
        if deck == .b {
            openAsk()
            if followUp != blendNoAnswer { answer(followUp) }
        }
        retire()
    }

    /// What A plays after its end, for an undone start. The first answer for a generation wins.
    public func followUp(generation: Int64, pass: Int64) {
        soonest = point
        if askGen >= 0 && generation == askGen { answer(pass) }
    }

    /// The listener's pace became `pace` from `switchFrame`, the first frame carrying it. A's and B's
    /// clocks run at the new pace from there, and the blend is cut at the first moment of its blend
    /// time (holds mapped) where the beats would drift past the bound at that pace; a pace that
    /// keeps them within it clears such a cut.
    public func paceChanged(pace: Double, switchFrame: Int64) {
        soonest = point
        guard state != .idle else { return }
        if startFrame >= 0 {
            anchorSongMs = songSince(switchFrame)
            anchorFrame = switchFrame
            if !stopped && switchFrame >= runFrom {
                runMs = incomingMs(switchFrame)
                runFrom = switchFrame
            }
        }
        speed = pace
        msPerFrame = pace * 1_000 / Double(timing.sampleRate)
        if startFrame < 0 || state == .ended { return }
        dropCutMs = blendDropCutAtMs(bothHeardStartMs: bothHeardStartMs, bothHeardEndMs: bothHeardEndMs,
                                     holdEndMs: holdShare * overlapMs, lockMs: lockMs, driftGap: driftGap,
                                     pace: pace, fromMs: blendAt(switchFrame))
    }

    /// The Crossfader stopped A: the generation retires.
    public func outgoingStopped(generation: Int64) {
        if generation == self.generation && state != .idle { retire() }
    }

    /// The output was cleared or rebuilt from `from`: the events for those frames post at once.
    public func cleared(from: Int64) {
        for i in 0..<evCount where events[i].frame >= from { eventNow[i] = true }
    }

    // MARK: Outputs

    public func takeAction() -> BlendAction? {
        if acRead == acCount {
            acRead = 0
            acCount = 0
            return nil
        }
        let next = actions[acRead]
        acRead += 1
        return next
    }

    /// The next event main may hear with the output's head at `head`: per generation in frame
    /// order, each once its frame has passed the head or it is due at once (a proposal, a question,
    /// anything a clear removed), which also lets out the generation's earlier ones.
    public func takeEvent(head: Int64) -> BlendEvent? {
        for i in 0..<evCount {
            let gen = events[i].generation
            var first = true
            for j in 0..<i where events[j].generation == gen {
                first = false
                break
            }
            if !first { continue }
            var due = false
            for j in i..<evCount where events[j].generation == gen && (eventNow[j] || events[j].frame <= head) {
                due = true
                break
            }
            if !due { continue }
            let next = events[i]
            removeEvent(i)
            return next
        }
        return nil
    }

    // MARK: Rules

    /// Frame-timed rules: the room deadline of a hold and the blend end, each decided while its
    /// frame is still a placement horizon away.
    private func timers(_ frame: Int64) {
        let horizon = timing.placementHorizonFrames
        if state == .held && frame > deadline - horizon { deadlinePassed(frame) }
        let running = state == .mixing || state == .stalled
        if running, let cutMs = dropCutMs {
            let cutAt = frameAtBlend(cutMs)
            if frame > cutAt - horizon { dropCut(max(cutAt, soonest)) }
        }
        if endAt >= 0 && running && frame > endAt - horizon { blendEndReached() }
    }

    /// The speed drop's cut: after the handover A leaves over 20 ms and B goes to its own level
    /// over 250 ms; before it, the start is undone as by the session's own disarm.
    private func dropCut(_ at: Int64) {
        dropCutMs = nil
        if !handedOver() { _ = word.cancel(generation) }
        if handedOver() {
            let ramp = rampFrames(blendDisarmRampMs)
            action(.aLeave, at, rampFrames(blendLeaveRampMs))
            action(.bDisarm, at, ramp)
            endBlend(at, incomingMs(at), ramp)
            return
        }
        undo(at)
        event(.cancelled, at, cause: .pace)
        openAsk()
        event(.followUpNeeded, at, now: true)
        asked = true
        retire()
    }

    /// Head-timed rules: A's real end, then the late start while armed; the proposal, then the
    /// commit or the cancel at the deadline once a start is decided.
    private func headRules() {
        if state == .armed {
            if !outgoingMs.isNaN && word.state(of: generation) == .initial {
                let predicted = point + Self.frames((startMs - outgoingMs) / msPerFrame)
                if decodedEnd >= 0 && predicted >= decodedEnd {
                    // A's song ends before the start: A plays on into its next song, never a
                    // start it does not reach.
                    cancelBeforeStart(point, .aMovedOn)
                } else if predicted - head < timing.proposalLeadFrames {
                    lateStart(predicted, at: point)
                }
            }
            return
        }
        if state == .idle || startFrame < 0 { return }
        if !acked { _ = handedOver() }
        if committed { return }
        let ahead = startFrame - head
        if ahead <= timing.commitAtFrames {
            commitPoint()
        } else if ahead <= timing.proposeAtFrames && word.propose(generation) {
            event(.proposed, startFrame, now: true)
        }
    }

    private func take(_ deck: BlendDeck, _ input: BlendInput, _ frame: Int64, _ value: Int64) {
        if deck == .b {
            switch input {
            case .ready: incomingReady = true
            case .dry, .paused: incomingReady = false
            default: break
            }
        }
        switch state {
        case .idle: if deck == .a && input == .movedOn { holdForAnswer(frame) }
        case .armed: armedInput(deck, input, frame, value)
        case .ended: if input == .paused { paused(frame, settledHead: value) }
        case .mixing, .held, .stalled: mixedInput(deck, input, frame, value)
        }
    }

    private func armedInput(_ deck: BlendDeck, _ input: BlendInput, _ frame: Int64, _ value: Int64) {
        if input == .paused {
            // A deck's own pause in the run-up: disarmed, and main hears it at once while the
            // output is paused.
            action(.aDisarm, frame)
            event(.cancelled, frame, cause: .paused, now: true)
            retire()
            return
        }
        if deck == .b { return }
        switch input {
        case .decodedEnd: decodedEnd = value
        case .startAt: startAt(frame, value)
        case .movedOn:
            holdForAnswer(frame)
            cancelBeforeStart(frame, .aMovedOn)
        case .ended: cancelBeforeStart(frame, .aEnded)
        default: break
        }
    }

    private func cancelBeforeStart(_ frame: Int64, _ cause: BlendCause) {
        action(.aDisarm, frame)
        event(.cancelled, frame, cause: cause)
        retire()
    }

    /// A's frame at the start is known at bus frame `s`.
    private func startAt(_ frame: Int64, _ s: Int64) {
        guard word.state(of: generation) == .initial else { return }
        if decodedEnd >= 0 && decodedEnd <= s {
            cancelBeforeStart(frame, .aMovedOn)
            return
        }
        if s - head < timing.proposalLeadFrames {
            lateStart(s, at: frame)
            return
        }
        if !incomingReady {
            // A's gain at S is its first value, so the disarm is unheard.
            action(.aDisarm, s)
            event(.incomingNotReady, s, cause: .notReady)
            retire()
            return
        }
        startFrame = s
        state = .mixing
        anchorFrame = s
        anchorSongMs = 0
        runFrom = s
        runMs = 0
        stopped = false
        action(.startB, s)
        action(.limiterOn, s)
    }

    /// A start that can no longer be decided the proposal lead ahead of the head moves to A's
    /// first bar line (or, with no bar grid, the first frame) that far ahead, while the plan's bar
    /// budget and its latest end allow (with no bar grid, any budget but 0 and the latest end);
    /// otherwise B does not start. A move past A's known end is no move: A plays on into its next
    /// song.
    private func lateStart(_ predicted: Int64, at: Int64) {
        let nowMs = startMs + Double(head + timing.proposalLeadFrames - predicted) * msPerFrame
        let bars = locked ? max(1, ((nowMs - startMs) / barMs).rounded(.up)) : 0
        let target = locked ? startMs + bars * barMs : nowMs
        if decodedEnd >= 0 && predicted + Self.frames((target - startMs) / msPerFrame) >= decodedEnd {
            cancelBeforeStart(at, .aMovedOn)
            return
        }
        let fits = locked
            ? bars <= Double(allowedBars())
            : lateMoveBars != 0 && target + overlapMs + extensionMs <= latestEndMs
        if fits {
            if locked { movedBars += Int(bars) }
            startMs = target
            action(.moveStart, at, ms: target)
            moved(at)
        } else {
            action(.aDisarm, at)
            event(.incomingNotReady, at, cause: .late)
            retire()
        }
    }

    private func commitPoint() {
        switch word.state(of: generation) {
        case .acked:
            commit()
        case .initial, .proposed:
            if commitBeforeAck {
                commit()
            } else if word.cancel(generation) {
                floorCancel()
            } else if handedOver() {
                commit()
            }
        default:
            break
        }
    }

    private func commit() {
        _ = handedOver()
        committed = true
        action(.commitB, startFrame)
    }

    /// Main has not acked by the deadline: B's start is dropped as not ready. An A already past
    /// its end plays the next pass it holds, as its own advance, since no edit on main can have won.
    private func floorCancel() {
        action(.undoStart, startFrame)
        action(.limiterOff, startFrame)
        if heldNextAt >= 0 { action(.aFollow, soonest, blendPassHeld) }
        dropEvents(generation, from: startFrame)
        event(.incomingNotReady, startFrame, cause: .floor)
        retire()
    }

    private func mixedInput(_ deck: BlendDeck, _ input: BlendInput, _ frame: Int64, _ value: Int64) {
        if input == .paused {
            paused(frame, settledHead: value)
            return
        }
        // A start can be decided before S: what happens before it counts from S.
        let at = max(frame, startFrame)
        if deck == .b {
            switch input {
            case .dry: if state == .mixing && at >= runFrom { incomingDry(at) }
            case .ready:
                if state == .held { place(at) } else if state == .stalled { cut(at) }
            default: break
            }
            return
        }
        switch input {
        // With B already stopped this is the second of two dry decks: a plain rebuffer.
        case .dry: if state == .mixing { outgoingDry(at) }
        case .ended, .movedOn: outgoingEnded(frame, at, movedOn: input == .movedOn)
        case .endReached: if state != .held { endAt = value }
        case .restarted: restarted(frame, value)
        default: break
        }
    }

    /// B ran dry at `at`. Inside A's hold (any kind with a hold: an intro, a lead-in), with room, B
    /// is held for placement on a later bar and A's blend time stops; otherwise A fades on and B
    /// waits for the clean cut.
    private func incomingDry(_ at: Int64) {
        stop(at)
        let blendMs = blendAt(at)
        let room = holdShare > 0 && blendMs < holdShare * overlapMs ? roomDeadline(at) : -1
        action(.holdB, at)
        if room >= 0 {
            state = .held
            holdFrom = at
            deadline = room
            frozenBlendMs = blendMs
            action(.freezeA, at)
        } else {
            state = .stalled
        }
    }

    /// The latest placement for B stopped at `stop` that still ends the extended blend by the
    /// plan's latest end while A has more than its ring's lead plus the sink buffer plus the spare
    /// left (in output time); -1 when none is past the horizon. On the bar grid placements are
    /// whole bars from the stop.
    private func roomDeadline(_ stop: Int64) -> Int64 {
        let leftMs = timing.ringLeadMs + timing.sinkBufferMs + blendRoomSpareMs
        let latest: Int64
        deadlineBars = 0
        if locked {
            let bar = barMs / msPerFrame
            let most = min(allowedBars(), Self.mostHoldBars)
            var best: Int64 = -1
            var bars = 1
            while bars <= most {
                let at = stop + Self.frames(Double(bars) * bar)
                if !leaves(at, leftMs) { break }
                best = at
                deadlineBars = bars
                bars += 1
            }
            latest = best
        } else if lateMoveBars == 0 {
            // An unlocked plan that may not move has no hold extension.
            latest = -1
        } else {
            let byEnd = stop + Self.floorFrames((latestEndMs - startMs - overlapMs - extensionMs) / msPerFrame)
            let byLeft = anchorFrame + Self.ceilFrames((outgoingEndMs - leftMs * speed - startMs - anchorSongMs) / msPerFrame) - 1
            latest = min(byEnd, byLeft)
        }
        return latest >= stop + timing.placementHorizonFrames ? latest : -1
    }

    private func leaves(_ at: Int64, _ leftMs: Double) -> Bool {
        (outgoingEndMs - (startMs + songSince(at))) / speed > leftMs
    }

    /// A's song time since the plan's start at `frame`.
    private func songSince(_ frame: Int64) -> Double { anchorSongMs + Double(frame - anchorFrame) * msPerFrame }

    /// A's blend time at `frame`: frozen while B is held, after a hold the song time less the
    /// extensions.
    private func blendAt(_ frame: Int64) -> Double {
        state == .held ? frozenBlendMs : max(songSince(frame) - extensionMs, frozenBlendMs)
    }

    /// The frame where A's blend time reaches `blendMs`, the extensions so far included.
    private func frameAtBlend(_ blendMs: Double) -> Int64 {
        anchorFrame + Self.frames((blendMs + extensionMs - anchorSongMs) / msPerFrame)
    }

    /// B is ready again before the deadline: its next frame lands the fewest whole bars after its
    /// stop that reach past the placement horizon (at the horizon with no bar grid), and A's blend
    /// time runs again there. A placement past the deadline is the deadline passing, and B, ready,
    /// comes in by the clean cut.
    private func place(_ ready: Int64) {
        let earliest = max(ready + timing.placementHorizonFrames, soonest)
        let at: Int64
        let added: Double
        var bars: Int64 = 0
        if locked {
            let bar = barMs / msPerFrame
            bars = max(1, Int64((Double(earliest - holdFrom) / bar).rounded(.up)))
            while bars > 1 && holdFrom + Self.frames(Double(bars - 1) * bar) >= earliest { bars -= 1 }
            while holdFrom + Self.frames(Double(bars) * bar) < earliest { bars += 1 }
            at = holdFrom + Self.frames(Double(bars) * bar)
            added = Double(bars) * barMs
        } else {
            at = earliest
            added = Double(at - holdFrom) * msPerFrame
        }
        if at > deadline {
            deadlinePassed(ready)
            cut(ready)
            return
        }
        movedBars += Int(bars)
        extensionMs += added
        action(.placeB, at)
        action(.unfreezeA, at, ms: extensionMs)
        moved(ready)
        runFrom = at
        stopped = false
        state = .mixing
        holdFrom = -1
        deadline = -1
    }

    /// B was not ready by the deadline: A's blend time runs again there and B waits for the clean
    /// cut.
    private func deadlinePassed(_ frame: Int64) {
        let at = max(deadline, soonest)
        extensionMs += Double(at - holdFrom) * msPerFrame
        if locked { movedBars += deadlineBars }
        action(.unfreezeA, at, ms: extensionMs)
        moved(frame)
        state = .stalled
        holdFrom = -1
        deadline = -1
    }

    /// B is ready again with A fading: one mix point where A leaves and B comes in from where it
    /// stopped, its shaping disarmed.
    private func cut(_ ready: Int64) {
        let at = max(ready + timing.placementHorizonFrames, soonest)
        let ramp = rampFrames(blendLeaveRampMs)
        action(.cut, at, ramp)
        runFrom = at
        stopped = false
        endBlend(at, runMs, ramp)
    }

    /// A ran dry in the mixed stretch: the blend ends here and B goes to its own level.
    private func outgoingDry(_ at: Int64) {
        let from = max(at, soonest)
        let ramp = rampFrames(blendDisarmRampMs)
        action(.aEnd, at)
        action(.bDisarm, from, ramp)
        endBlend(from, incomingMs(from), ramp)
    }

    /// A's song ended before the planned blend end: the blend ends at its last frame, and anything
    /// of A's next pass is held, never rendered.
    private func outgoingEnded(_ frame: Int64, _ at: Int64, movedOn: Bool) {
        if movedOn {
            action(.aHoldNext, frame)
            heldNextAt = frame
        }
        let from = max(at, soonest)
        let ramp = rampFrames(blendDisarmRampMs)
        action(.bDisarm, from, ramp)
        endBlend(from, incomingMs(from), ramp)
    }

    /// A's frame at the (extended) blend end. With B stalled the blend ends there too and B simply
    /// comes in when it can.
    private func blendEndReached() {
        let at = max(endAt, soonest)
        endAt = -1
        action(.aEnd, at)
        if state == .stalled {
            let ramp = rampFrames(blendDisarmRampMs)
            action(.bDisarm, at, ramp)
            endBlend(at, incomingMs(at), ramp)
        } else {
            // B consumed nothing while held, so after a hold this is still the unextended overlap.
            endBlend(at, incomingMs(at), 0)
        }
    }

    /// An engine restart re-placed a start that has not played. Not yet committed, it simply moves
    /// (the commit point decides). Committed and now inside the commit deadline, it moves to A's bar
    /// line the fewest whole bars on that reaches the deadline, or to the placement horizon with no
    /// bar grid; with no such bar line before A's end or within the plan's bar budget (with no bar
    /// grid, a budget of 0 or a blend that would end past the latest end), B starts at the horizon
    /// by the clean cut (a committed start cannot be cancelled).
    private func restarted(_ frame: Int64, _ s: Int64) {
        if head >= startFrame { return }
        if !committed && !handedOver() {
            replace(s, commitNow: false)
            return
        }
        let earliest = head + timing.commitAtFrames
        if s >= earliest {
            replace(s, commitNow: false)
            return
        }
        if !locked && lateMoveBars != 0 {
            let at = max(soonest + timing.placementHorizonFrames, s)
            let movedMs = startMs + Double(at - s) * msPerFrame
            if movedMs + overlapMs + extensionMs <= latestEndMs {
                startMs = movedMs
                action(.moveStart, frame, ms: startMs)
                replace(at, commitNow: true)
                moved(frame)
                return
            }
        }
        if locked {
            let bar = barMs / msPerFrame
            var bars = max(1, Int64((Double(earliest - s) / bar).rounded(.up)))
            while bars > 1 && s + Self.frames(Double(bars - 1) * bar) >= earliest { bars -= 1 }
            while s + Self.frames(Double(bars) * bar) < earliest { bars += 1 }
            let at = s + Self.frames(Double(bars) * bar)
            let movedMs = startMs + Double(bars) * barMs
            if bars <= Int64(allowedBars()) && movedMs < outgoingEndMs {
                movedBars += Int(bars)
                startMs = movedMs
                action(.moveStart, frame, ms: movedMs)
                replace(at, commitNow: true)
                moved(frame)
                return
            }
        }
        let cutAt = soonest + timing.placementHorizonFrames
        let ramp = rampFrames(blendLeaveRampMs)
        replace(cutAt, commitNow: true)
        action(.cut, cutAt, ramp)
        endBlend(cutAt, 0, ramp)
    }

    private func replace(_ s: Int64, commitNow: Bool) {
        startFrame = s
        anchorFrame = s
        anchorSongMs = 0
        runFrom = s
        action(.startB, s)
        action(.limiterOn, s)
        if commitNow || committed {
            committed = true
            action(.commitB, s)
        }
    }

    /// A deck's own pause with frames left, in the mixed stretch: the session's own disarm by the
    /// phase rule.
    private func paused(_ frame: Int64, settledHead: Int64) {
        if !handedOver() { _ = word.cancel(generation) }
        if handedOver() {
            afterAckPause(settledHead)
            retire()
            return
        }
        undo(frame)
        event(.cancelled, frame, cause: .paused, now: true)
        openAsk()
        event(.followUpNeeded, frame, now: true)
        asked = true
        retire()
    }

    /// Before the handover: B's start is undone (its frames never rendered), or, for a start
    /// committed before the acknowledgement, B's rendered frames fade out. The generation's events
    /// from S on are dropped.
    private func undo(_ frame: Int64) {
        if committed {
            let at = max(frame, soonest)
            let ramp = rampFrames(blendDisarmRampMs)
            action(.fadeOutB, at, ramp)
            action(.aDisarm, at, ramp)
            action(.limiterOff, at + ramp)
        } else {
            action(.undoStart, startFrame)
            action(.limiterOff, startFrame)
        }
        dropEvents(generation, from: startFrame)
    }

    /// After the handover, an interrupt that releases nothing: A leaves at the later of its frame
    /// and S. Before S, A plays alone to S and B enters there disarmed, so no silence opens before B.
    private func afterAckEdit(_ frame: Int64) {
        guard reopenEnd(frame) else { return }
        if frame < startFrame {
            let ramp = rampFrames(blendLeaveRampMs)
            action(.cut, startFrame, ramp)
            endBlend(startFrame, 0, ramp)
        } else {
            let ramp = rampFrames(blendDisarmRampMs)
            action(.aLeave, frame, rampFrames(blendLeaveRampMs))
            action(.bDisarm, frame, ramp)
            endBlend(frame, incomingMs(frame), ramp)
        }
    }

    /// After the handover, a pause: the output is mixed again from the settled head while paused,
    /// as A alone to S then B from S when S was not consumed, else B alone from there. Its events
    /// post at the clear.
    private func afterAckPause(_ settledHead: Int64) {
        guard reopenEnd(settledHead) else { return }
        action(.remix, settledHead)
        if settledHead < startFrame {
            let ramp = rampFrames(blendLeaveRampMs)
            action(.cut, startFrame, ramp)
            endBlend(startFrame, 0, ramp, now: true)
        } else {
            let ramp = rampFrames(blendDisarmRampMs)
            action(.aEnd, settledHead)
            action(.bDisarm, settledHead, ramp)
            endBlend(settledHead, incomingMs(settledHead), ramp, now: true)
        }
        markNow(generation)
    }

    /// With the end already decided, an interrupt before it takes over from it; one at or past it
    /// has nothing left to change.
    private func reopenEnd(_ frame: Int64) -> Bool {
        guard state == .ended else { return true }
        if frame >= endFrame { return false }
        dropKind(generation, .blendEnd)
        return true
    }

    private func endBlend(_ at: Int64, _ easeFromMs: Double, _ rampFrames: Int64, now: Bool = false) {
        action(.limiterOff, at + rampFrames)
        event(.blendEnd, at, ms: easeFromMs, now: now)
        state = .ended
        endFrame = at
        endAt = -1
    }

    private func openAsk() {
        askGen = generation
        askHeldAt = heldNextAt
        askReadyAt = heldNextAt >= 0 ? calls : -1
        asked = false
    }

    private func holdForAnswer(_ frame: Int64) {
        guard askGen >= 0 && askHeldAt < 0 else { return }
        action(.aHoldNext, frame)
        askHeldAt = frame
        askReadyAt = calls
    }

    private func answer(_ pass: Int64) {
        action(.aFollow, soonest, pass)
        askGen = -1
    }

    /// On the bar grid: how many more whole bars the blend may move later, the bars left before
    /// the plan's latest end, within what remains of its budget. Computed in floating point before
    /// any multiplication, so an unbounded budget never overflows (and never traps).
    private func allowedBars() -> Int {
        let roomBars = Int((max(0, latestEndMs - (startMs + overlapMs + extensionMs)) / barMs).rounded(.down))
        return lateMoveBars == lateMoveUnbounded ? roomBars : min(lateMoveBars - movedBars, roomBars)
    }

    private func handedOver() -> Bool {
        if !acked && word.state(of: generation) == .acked { acked = true }
        return acked
    }

    private func retire() {
        state = .idle
        startFrame = -1
        endAt = -1
        holdFrom = -1
        deadline = -1
    }

    private func stop(_ at: Int64) {
        runMs = incomingMs(at)
        stopped = true
    }

    private func incomingMs(_ at: Int64) -> Double {
        stopped ? runMs : runMs + Double(max(0, at - runFrom)) * msPerFrame
    }

    private func rampFrames(_ ms: Double) -> Int64 { Self.frames(ms * Double(timing.sampleRate) / 1_000) }

    // MARK: Buffers

    private func action(_ kind: BlendActionKind, _ frame: Int64, _ value: Int64 = 0, ms: Double = 0) {
        if acCount == Self.actionSlots {
            if acRead == 0 {
                lost += 1
                return
            }
            for i in acRead..<acCount { actions[i - acRead] = actions[i] }
            acCount -= acRead
            acRead = 0
        }
        actions[acCount] = BlendAction(kind: kind, frame: frame, value: value, ms: ms)
        acCount += 1
    }

    /// Events of a retired generation are never queued, except the question an undone start still
    /// owes.
    private func event(_ kind: BlendEventKind, _ frame: Int64, ms: Double = 0, ms2: Double = 0, cause: BlendCause = .none,
                       now: Bool = false, gen: Int64? = nil) {
        let gen = gen ?? generation
        let live = (state != .idle && gen == generation) || (askGen >= 0 && gen == askGen)
        guard live else { return }
        if evCount == Self.eventSlots {
            lost += 1
            return
        }
        var at = evCount
        while at > 0 && events[at - 1].frame > frame {
            events[at] = events[at - 1]
            eventNow[at] = eventNow[at - 1]
            at -= 1
        }
        events[at] = BlendEvent(kind: kind, generation: gen, frame: frame, ms: ms, ms2: ms2, cause: cause)
        eventNow[at] = now
        evCount += 1
    }

    /// One "moved" per generation, carrying the start and the extension as they stand now.
    private func moved(_ frame: Int64) {
        for i in 0..<evCount where events[i].generation == generation && events[i].kind == .moved {
            events[i].ms = startMs
            events[i].ms2 = extensionMs
            return
        }
        event(.moved, frame, ms: startMs, ms2: extensionMs)
    }

    private func dropEvents(_ gen: Int64, from: Int64) {
        var i = evCount - 1
        while i >= 0 {
            if events[i].generation == gen && events[i].frame >= from { removeEvent(i) }
            i -= 1
        }
    }

    private func dropKind(_ gen: Int64, _ kind: BlendEventKind) {
        var i = evCount - 1
        while i >= 0 {
            if events[i].generation == gen && events[i].kind == kind { removeEvent(i) }
            i -= 1
        }
    }

    private func markNow(_ gen: Int64) {
        for i in 0..<evCount where events[i].generation == gen { eventNow[i] = true }
    }

    private func removeEvent(_ index: Int) {
        var i = index + 1
        while i < evCount {
            events[i - 1] = events[i]
            eventNow[i - 1] = eventNow[i]
            i += 1
        }
        evCount -= 1
    }

    private static func frames(_ x: Double) -> Int64 { Int64((x + 0.5).rounded(.down)) }

    /// Frame counts at a bound, kept from tipping over by float rounding.
    private static func floorFrames(_ x: Double) -> Int64 { Int64((x + 1e-6).rounded(.down)) }

    private static func ceilFrames(_ x: Double) -> Int64 { Int64((x - 1e-6).rounded(.up)) }
}

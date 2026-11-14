import Foundation

/// One deck's part in a blend on the phone's render thread: it takes the session's actions, each
/// named by the bus frame it lands on, and places them on the deck's own source frames through
/// the deck's clock (the leave and fade ramps go to the deck's gain after its time-pitch stage, on
/// the bus frame itself); and it tells the deck's render, frame by frame, whether to play, hold
/// silent, start, or change its shaping. It posts what the deck finds to the session, tagged with
/// the bus frame where it happened. Nothing allocates after `init`.
public final class DeckBlendStage: @unchecked Sendable {
    public enum Role: Sendable, Equatable { case none, outgoing, incoming }

    /// The shaping the stage changes: a deck's processor.
    public typealias Shaping = DeckProcessor

    public let clock: DeckClock
    public let gain: DeckGainRamp
    public private(set) var role: Role = .none
    public private(set) var deck: BlendDeck = .a
    /// It played out in the last generation registered: an undone start may still ask what
    /// follows its end.
    public private(set) var wasOutgoing = false
    private var session: BlendSession?
    /// The other deck's stage, for a start that waits for the outgoing song's kept frames. Both
    /// live as long as the engine; no reference is counted on the render thread.
    public unowned(unsafe) var partner: DeckBlendStage?

    // The incoming song.
    /// Loaded and waiting for its start: silent, consuming nothing.
    public private(set) var parked = false
    public private(set) var started = false
    /// The start may be rendered (main acknowledged it, or it commits without waiting).
    public private(set) var committed = false
    /// The source frame where its first frame lands, once decided, and the bus frame it is heard
    /// at, from which the index is placed again whenever the mapping changes.
    public private(set) var startIndex: Int64 = -1
    private var startBus: Int64 = -1
    /// How many source frames past the outgoing song's cut the start came (it was rendered past
    /// the cut before the cut was known): that much of its song is dropped, so it lands on the cut.
    public private(set) var startLate: Int64 = 0
    /// Held for placement on a bar: consumes nothing.
    public private(set) var held = false
    /// Where it comes in, in its own song: frames before are dropped at the start.
    public private(set) var entryMs = 0.0
    /// Starts where the outgoing song's kept frames end (after a pause past the handover).
    public private(set) var startsAfterPartner = false
    /// Set by the stage the moment the start is reached; the render drops what lies before the
    /// entry and starts the song's stream there, then clears it.
    public var startingNow = false

    // The outgoing song.
    /// The plan's start in its song time, as armed and with moves.
    public private(set) var planStartMs = 0.0
    public private(set) var startMs = 0.0
    public private(set) var overlapMs = 0.0
    public private(set) var extensionMs = 0.0
    /// Its frames from the blend's end on are not rendered.
    public private(set) var stopped = false
    /// An undone start caught it at its end: it holds there until main names what follows.
    public private(set) var holdingForAnswer = false
    /// What main named: a follower's serial, `blendPassNone` or `blendPassHeld`.
    public private(set) var follow: Int64?
    /// Whether its frame at the start, and at the end, has been told to the session.
    public var startPosted = false
    public var endPostedMs = Double.nan
    /// After a pause past the handover with the start not yet heard: it plays to the start, then
    /// leaves, and the incoming song comes in there.
    public private(set) var cutAtStart = false
    /// The bus frame where the outgoing song's last kept frame ends, once known (-1 before).
    public var cutBus: Int64 = -1

    /// A ramp in (a rejoin, a placement, a cut), in source frames.
    private var rampLeft = 0
    private var rampTotal = 0

    /// Whether the deck is out of sound now, and what the session was last told.
    public private(set) var dry = false
    private var readyTold: Bool?
    /// While the session's actions for a pause past the handover arrive.
    private var remixing = false

    private struct Pending {
        var index: Int64
        var kind: BlendActionKind
        var frames: Int
        var ms: Double
    }

    private static let slots = 32
    private let pending: UnsafeMutablePointer<Pending>
    private var pendingCount = 0
    /// Actions that found the buffer full.
    public private(set) var lost = 0

    public init(clock: DeckClock = DeckClock(), gain: DeckGainRamp = DeckGainRamp()) {
        self.clock = clock
        self.gain = gain
        pending = .allocate(capacity: Self.slots)
        pending.initialize(repeating: Pending(index: 0, kind: .startB, frames: 0, ms: 0), count: Self.slots)
    }

    deinit { pending.deallocate() }

    // MARK: Registration

    /// The incoming song of a new blend, parked until its start, entering at `entryMs`.
    public func armIncoming(_ session: BlendSession, entryMs: Double) {
        reset()
        self.session = session
        role = .incoming
        deck = .b
        wasOutgoing = false
        self.entryMs = entryMs
        parked = true
        gain.open()
    }

    /// The outgoing song of a new blend, starting at `startMs` of its song over `overlapMs`.
    public func armOutgoing(_ session: BlendSession, startMs: Double, overlapMs: Double) {
        reset()
        self.session = session
        role = .outgoing
        deck = .a
        wasOutgoing = true
        planStartMs = startMs
        self.startMs = startMs
        self.overlapMs = overlapMs
        gain.open()
    }

    /// The session is done with this deck's generation: it tells the session nothing more. What
    /// was decided still happens: a committed start still starts (a start waiting for the outgoing
    /// song's kept frames too), a parked song that never started stays silent until main unloads
    /// or registers it, queued actions still land, and an outgoing song waiting to hear what
    /// follows its end keeps waiting.
    public func retire() {
        role = .none
        readyTold = nil
        remixing = false
    }

    /// The deck plays as itself, fully open: a new song loaded for plain playback.
    public func release() {
        reset()
        gain.open()
    }

    private func reset() {
        role = .none
        session = nil
        parked = false
        started = false
        committed = false
        startIndex = -1
        startBus = -1
        startLate = 0
        held = false
        startsAfterPartner = false
        startingNow = false
        stopped = false
        holdingForAnswer = false
        follow = nil
        startPosted = false
        endPostedMs = .nan
        cutAtStart = false
        cutBus = -1
        extensionMs = 0
        rampLeft = 0
        rampTotal = 0
        dry = false
        readyTold = nil
        remixing = false
        pendingCount = 0
    }

    // MARK: The session's actions

    /// Takes one action as the deck's `part` of it (the deck that played out, or came in, in the
    /// generation that sent it). Those for a frame not yet rendered wait for it; the rest apply at
    /// the next frame.
    public func take(_ action: BlendAction, as part: Role) {
        let rate = clock.current.rate
        let at = max(clock.source, clock.source(ofBus: action.frame))
        let frames = Int((Double(action.value) * rate).rounded())
        switch (action.kind, part) {
        case (.startB, .incoming):
            startIndex = at
            startBus = action.frame
        case (.commitB, .incoming):
            committed = true
            if startIndex < 0 {
                startIndex = at
                startBus = action.frame
            }
            startIndex = max(startIndex, clock.source)
        case (.undoStart, .incoming):
            parked = true
            started = false
            committed = false
            startIndex = -1
            startBus = -1
            held = false
            queue(clock.source, .undoStart)
        case (.undoStart, .outgoing):
            queue(clock.source, .undoStart)
        case (.fadeOutB, .incoming):
            gain.leave(atBus: action.frame, frames: action.value)
        case (.aDisarm, .outgoing):
            queue(at, .aDisarm, frames)
        case (.aLeave, .outgoing):
            gain.leave(atBus: action.frame, frames: action.value)
        case (.aEnd, .outgoing):
            // Paused past the handover (the actions come remixed): the song ends once the deck's
            // own fade into the pause is out, never cut mid-cycle.
            queue(remixing ? clock.source + Int64(DeckFlow.rampFrames(ms: DeckFlow.pauseFadeMs, rate: rate)) : at, .aEnd)
        case (.aHoldNext, .outgoing):
            holdingForAnswer = true
        case (.aFollow, .outgoing):
            // Only a song holding at its end needs the answer; one still short of it plays on into
            // whatever follows it then.
            if holdingForAnswer || (session?.awaitsFollowUp ?? false) { follow = action.value }
            holdingForAnswer = false
        case (.bDisarm, .incoming):
            queue(remixing ? clock.source : at, .bDisarm, frames)
        case (.holdB, .incoming):
            queue(at, .holdB)
        case (.placeB, .incoming):
            queue(at, .placeB, DeckFlow.rampFrames(rate: rate))
        case (.cut, .outgoing):
            if !remixing {
                gain.leave(atBus: action.frame, frames: action.value)
            } else if partner?.started == true {
                // Paused past the handover with the incoming song already under way: it plays on
                // alone, and nothing more of this song is heard once the deck's own 10 ms fade into
                // the pause is out.
                queue(clock.source + Int64(DeckFlow.rampFrames(ms: DeckFlow.pauseFadeMs, rate: rate)), .aEnd)
            } else {
                cutAtStart = true
            }
        case (.cut, .incoming):
            if !remixing {
                queue(at, .cut, max(1, frames))
            } else if started {
                queue(clock.source, .bDisarm, DeckFlow.rampFrames(ms: blendDisarmRampMs, rate: rate))
            } else {
                startsAfterPartner = true
            }
        case (.freezeA, .outgoing):
            queue(at, .freezeA)
        case (.unfreezeA, .outgoing):
            queue(at, .unfreezeA, ms: action.ms)
        case (.moveStart, .outgoing):
            startMs = action.ms
            startPosted = false
            queue(clock.source, .moveStart, ms: action.ms)
        case (.remix, _):
            remixing = true
        default:
            break
        }
    }

    /// The session's actions of one call are all in.
    public func endOfActions() { remixing = false }

    /// `frames` frames already handed out are handed out again (an engine restart's replay): what
    /// waits on a later frame of the song moves by as much.
    public func shift(by frames: Int64) {
        guard frames > 0 else { return }
        for i in 0..<pendingCount where pending[i].index >= clock.source { pending[i].index += frames }
        if startIndex >= clock.source { startIndex += frames }
    }

    /// The deck's sound is heard `frames` bus frames later from now on (an engine restart): what
    /// was placed on a bus frame ahead (the start's frame, the cut, the level's leave) moves with
    /// it, and the blend's end is told again from the new mapping.
    public func shift(byBus frames: Int64) {
        guard frames > 0 else { return }
        if startBus >= 0 { startBus += frames }
        if cutBus >= 0 { cutBus += frames }
        gain.shift(byBus: frames)
        endPostedMs = .nan
    }

    /// The deck's rate or latency changed: a start placed on a bus frame and not yet reached is
    /// placed again on the source frame heard there now; the outgoing song's blend end is told
    /// again from the new mapping.
    public func mappingChanged() {
        if role == .outgoing { endPostedMs = .nan }
        guard role == .incoming, parked, !started, !startsAfterPartner, startBus >= 0 else { return }
        startIndex = max(clock.source, clock.source(ofBus: startBus))
    }

    private func queue(_ index: Int64, _ kind: BlendActionKind, _ frames: Int = 0, ms: Double = 0) {
        if pendingCount == Self.slots {
            lost += 1
            return
        }
        var at = pendingCount
        while at > 0 && pending[at - 1].index > index {
            pending[at] = pending[at - 1]
            at -= 1
        }
        pending[at] = Pending(index: index, kind: kind, frames: frames, ms: ms)
        pendingCount += 1
    }

    // MARK: The render

    /// Applies what is due at the deck's next source frame: the actions placed there and, for
    /// the incoming song, its start.
    public func crossDue(_ shaping: Shaping?) {
        while pendingCount > 0 && pending[0].index <= clock.source {
            apply(pending[0], shaping)
            for i in 1..<pendingCount { pending[i - 1] = pending[i] }
            pendingCount -= 1
        }
        guard parked, !started else { return }
        if startsAfterPartner {
            guard let partner, partner.cutBus >= 0 else { return }
            let at = clock.source(ofBus: partner.cutBus)
            if clock.source >= at {
                startLate = clock.source - at
                begin(rampFrames: DeckFlow.rampFrames(rate: clock.current.rate), shaping)
            }
            return
        }
        if committed && startIndex >= 0 && clock.source >= startIndex { begin(rampFrames: 0, shaping) }
    }

    private func begin(rampFrames: Int, _ shaping: Shaping?) {
        parked = false
        started = true
        startingNow = true
        held = false
        if rampFrames > 0 {
            shaping?.disarmTransition(frames: 0)
            rampIn(rampFrames)
        }
    }

    private func apply(_ p: Pending, _ shaping: Shaping?) {
        switch p.kind {
        case .undoStart:
            shaping?.disarmTransition(frames: 0)
        case .aDisarm:
            shaping?.disarmTransition(frames: p.frames)
        case .bDisarm:
            shaping?.disarmTransition(frames: p.frames)
            if held {
                held = false
                rampIn(DeckFlow.rampFrames(rate: clock.current.rate))
            }
        case .holdB:
            held = true
        case .placeB:
            held = false
            rampIn(p.frames)
        case .cut:
            held = false
            shaping?.disarmTransition(frames: 0)
            if !started {
                parked = false
                started = true
                startingNow = true
            }
            rampIn(p.frames)
        case .freezeA:
            shaping?.freezeBlend()
        case .unfreezeA:
            extensionMs = p.ms
            shaping?.unfreezeBlend(extensionMs: p.ms)
        case .moveStart:
            shaping?.moveBlendStart(byMs: p.ms - planStartMs)
        case .aEnd:
            stopped = true
        default:
            break
        }
    }

    /// Frames from the next one until something changes (an action's frame, the start), at most
    /// `limit`.
    public func framesUntilNext(_ limit: Int) -> Int {
        var n = Int64(limit)
        if pendingCount > 0 { n = min(n, pending[0].index - clock.source) }
        if parked && committed && !startsAfterPartner && startIndex > clock.source {
            n = min(n, startIndex - clock.source)
        }
        if parked && startsAfterPartner, let partner, partner.cutBus >= 0 {
            let at = clock.source(ofBus: partner.cutBus)
            if at > clock.source { n = min(n, at - clock.source) }
        }
        return Int(max(1, n))
    }

    /// Whether the deck is part of a blend still sounding or waiting: registered, or past its
    /// session with kept frames to play or a start waiting on them.
    public var live: Bool { role != .none || cutAtStart || startsAfterPartner }

    /// Whether the deck renders silence and consumes nothing now: parked before its start, held
    /// for placement, or past the blend's end of the outgoing song.
    public var silent: Bool { parked || held || stopped }

    /// Starts a ramp in over `frames` source frames.
    public func rampIn(_ frames: Int) {
        rampTotal = max(1, frames)
        rampLeft = rampTotal
    }

    /// Applies any ramp in to `frames` frames in place.
    public func applyRampIn(_ buffers: [UnsafeMutablePointer<Float>], offset: Int, frames: Int) {
        guard rampLeft > 0 else { return }
        let n = min(frames, rampLeft)
        // Index loops only: an array's own iterator allocates in an unoptimized build.
        for i in 0..<n {
            let g = DeckFlow.rampInGain(rampTotal - rampLeft + i, of: rampTotal)
            for ch in 0..<buffers.count { buffers[ch][offset + i] *= g }
        }
        rampLeft -= n
    }

    /// What the outgoing deck does at its song's end, with a follower of `followerSerial` loaded.
    public func atSongEnd(followerSerial: Int64?) -> OutgoingEnd {
        let waiting = holdingForAnswer || (wasOutgoing && (session?.awaitsFollowUp ?? false))
        let begun = role == .outgoing && (partner.map { $0.started || ($0.committed && $0.startIndex >= 0) } ?? false)
        return outgoingEnd(registered: role == .outgoing, started: begun || cutAtStart, holdingForAnswer: waiting,
                           follow: follow, followerSerial: followerSerial)
    }

    /// The outgoing deck moved on into its follower, or ended: a follow-up was used.
    public func followed() {
        follow = nil
        holdingForAnswer = false
    }

    // MARK: What the deck tells the session

    /// Posts `input` at the bus frame where source frame `index` (the next frame when nil) is
    /// heard. A deck in no blend tells nothing, except the outgoing song's end while the session
    /// waits to hear what follows it.
    public func post(_ input: BlendInput, atSource index: Int64? = nil, value: Int64 = 0) {
        guard let session else { return }
        guard role != .none || (wasOutgoing && input == .movedOn && session.awaitsFollowUp) else { return }
        session.post(deck, input, frame: clock.bus(ofSource: index ?? clock.source), value: value)
    }

    /// The deck can give frames (`ready`) or not, as its render found: told to the session only
    /// when it changes.
    public func readiness(_ ready: Bool) {
        guard role != .none, readyTold != ready else { return }
        readyTold = ready
        dry = !ready && (started || role == .outgoing)
        post(ready ? .ready : .dry)
    }

    /// Whether the session was told the deck is ready.
    public var toldReady: Bool { readyTold == true }
}

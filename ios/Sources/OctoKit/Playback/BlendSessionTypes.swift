import Foundation
import Synchronization

/// The two decks of a blend: A plays out, B comes in.
public enum BlendDeck: String, Sendable {
    case a = "A"
    case b = "B"
}

/// What a deck's render found, posted with the bus frame where it happened.
public enum BlendInput: String, Sendable, CaseIterable {
    /// The deck can give frames: it plays with its start-after threshold met and its ring holds
    /// frames, or it has reached its end. Also a dry deck that has frames again.
    case ready
    /// A playing deck with an empty ring, its end not reached.
    case dry
    /// A pause while unrendered frames remain (never a rebuffer). The value is the settled head:
    /// the frame the hardware stopped at.
    case paused
    /// A's stream ended with nothing after it.
    case ended
    /// A's first frame of a new pass (another entry, or the same entry from its start right after
    /// its end) lands at this frame.
    case movedOn
    /// A's frame at the plan's start is known: the value is S, the frame it lands on. Posted at
    /// the frame where it became known, which is before S.
    case startAt
    /// A's frame at the blend end (extensions included) is known: the value is its frame. Posted
    /// at the frame where it became known.
    case endReached
    /// An engine restart re-placed a decided start that has not played yet: the value is the new S.
    case restarted
    /// A's decoded end is known (its stream's last frame of the song is in hand, its next pass or
    /// its stream's end after it): the value is the output frame just past A's last frame of the
    /// song. Posted at the frame where it became known.
    case decodedEnd
}

/// Idle; armed (the run-up); mixing; held (B stopped inside A's hold, waiting to be placed on a
/// bar); stalled (B stopped, waiting for the clean cut); ended (the blend's end is decided, A not
/// yet stopped).
public enum BlendState: String, Sendable {
    case idle, armed, mixing, held, stalled, ended
}

/// Why the main thread disarms: a pause takes its own rule after the handover, every other
/// interrupt is an edit.
public enum BlendDisarm: String, Sendable, CaseIterable {
    case edit, pause
}

/// What the render does, at the action's frame (or at once when the frame is already behind it).
public enum BlendActionKind: String, Sendable, CaseIterable {
    /// B's first frame lands at the frame, B's shaping armed by the plan.
    case startB
    /// B's start is final: its frames may be rendered from the frame on.
    case commitB
    /// B's start at the frame is undone: none of B's frames is rendered, B is parked again and A
    /// goes on alone at its own level.
    case undoStart
    /// B's frames already rendered fade out over `value` frames.
    case fadeOutB
    /// A's blend shaping goes to its own level over `value` frames (0 when nothing it shaped has
    /// been heard).
    case aDisarm
    /// A leaves with a ramp of `value` frames; what of it was rendered plays out.
    case aLeave
    /// A's frames of its own song from the frame on are dropped and counted presented; frames of
    /// its next song are never rendered.
    case aEnd
    /// A's frames past its song's last frame (its next pass, from the frame on) are held, neither
    /// rendered nor counted presented.
    case aHoldNext
    /// What A plays after a held end: the pass named by `value` (`blendPassNone`: nothing, A
    /// stops at its end; `blendPassHeld`: the pass it holds, as its own advance).
    case aFollow
    /// B's shaping goes to its own level over `value` frames, and any hold on B is lifted: B plays
    /// from where it stopped as soon as it has frames.
    case bDisarm
    /// B consumes nothing from the frame on.
    case holdB
    /// B's next frame lands at the frame (shaping on).
    case placeB
    /// The clean cut at the frame: A leaves with a ramp of `value` frames, and B comes in from
    /// where it stopped with the same ramp, its shaping disarmed.
    case cut
    /// A's blend time stops at A's frame at the frame.
    case freezeA
    /// A's blend time runs again from the frame, shifted by `ms` in all.
    case unfreezeA
    /// The plan's start moves to `ms` of A's song time.
    case moveStart
    /// The mix limiter limits the sum from the frame on, whatever the Limiter setting.
    case limiterOn
    /// The mix limiter is off from the frame on (by a need of 1 and its release).
    case limiterOff
    /// While paused, the output is mixed again from the frame, the consumed point, by the actions
    /// that follow.
    case remix
}

/// What the main thread is told.
public enum BlendEventKind: String, Sendable, CaseIterable {
    /// B's start at the frame is decided: main acks and hands over.
    case proposed
    /// B did not start (cause notReady, late or floor): re-plan the rest of A.
    case incomingNotReady
    /// The start moved (`ms`, A's song time) or a hold extended the blend (`ms2`, in all).
    case moved
    /// The blend ended at the frame: finish the fade. `ms` is B's song time since entry over the
    /// rate there, where easing starts.
    case blendEnd
    /// The session cancelled the plan itself (cause aMovedOn, aEnded, paused, or pace for a speed
    /// drop's cut before the handover).
    case cancelled
    /// An undone start needs to hear what A plays next: answer with `followUp`.
    case followUpNeeded
}

public enum BlendCause: String, Sendable, CaseIterable {
    case none, notReady, late, floor, aMovedOn, aEnded, paused, pace
}

/// Follow-up answers: no next pass (A stops at its end), the pass A already holds, and a failure
/// that carries no answer.
public let blendPassNone: Int64 = -1
public let blendPassHeld: Int64 = -2
public let blendNoAnswer: Int64 = -3

/// A leave, a cut's two ramps and a rejoin take this long; a disarm to a song's own level takes
/// the longer one.
public let blendLeaveRampMs = 20.0
public let blendDisarmRampMs = 250.0

/// A hold may move the meeting point only while A has more than its ring's lead plus the sink
/// buffer plus this much left, in output time.
public let blendRoomSpareMs = 1_000.0

/// A plan whose drift bound holds wherever it sits: its start may move any number of bars. The
/// same number as the shared Kotlin `LATE_MOVE_UNBOUNDED`.
public let lateMoveUnbounded = 2_147_483_647

/// How far apart, in ms, the two songs' beats may be while both are heard, at a pace of 1 (the
/// planner's drift limit, the shared Kotlin `DRIFT_LIMIT_MS`).
public let blendDriftLimitMs = 40.0

/// The numbers that differ between phones and routes, in bus frames unless named otherwise. The
/// caller may change them between calls.
public struct BlendTiming: Sendable, Equatable {
    public var sampleRate: Int
    /// How far ahead an action that lands on a later frame (a cut, a placement) must land.
    public var placementHorizonFrames: Int64
    /// How far ahead of the head a start must be decided; a start closer than this moves (the
    /// late start).
    public var proposalLeadFrames: Int64
    /// The start is proposed once it is this close to the head.
    public var proposeAtFrames: Int64
    /// The start commits, or is cancelled when main has not acked, once it is this close to the
    /// head (the commit deadline).
    public var commitAtFrames: Int64
    /// The deck ring's lead and the sink's buffer, for the hold's room.
    public var ringLeadMs: Double
    public var sinkBufferMs: Double

    public init(sampleRate: Int, placementHorizonFrames: Int64, proposalLeadFrames: Int64, proposeAtFrames: Int64,
                commitAtFrames: Int64, ringLeadMs: Double, sinkBufferMs: Double) {
        self.sampleRate = sampleRate
        self.placementHorizonFrames = placementHorizonFrames
        self.proposalLeadFrames = proposalLeadFrames
        self.proposeAtFrames = proposeAtFrames
        self.commitAtFrames = commitAtFrames
        self.ringLeadMs = ringLeadMs
        self.sinkBufferMs = sinkBufferMs
    }
}

/// One plan as the session needs it. Times are A's song time in ms; a bar length of nan (or 0)
/// means A's tempo is not locked. A's end is its length as known, for the hold's room.
/// Generations start at 1 and stay under 2^61.
public struct BlendArm: Sendable, Equatable {
    public var generation: Int64
    public var startMs: Double
    public var overlapMs: Double
    public var latestEndMs: Double
    public var outgoingEndMs: Double
    /// The most whole bars of A the blend may move later in all (a late start, a hold, a restart
    /// inside the commit deadline) and still meet its drift bound: `lateMoveUnbounded` for no
    /// limit, 0 for no move. A move past it does not happen. Without a bar grid only 0 counts (no
    /// move and no hold extension); any other budget moves within the latest end.
    public var lateMoveBars: Int
    public var barMs: Double
    /// A holds its level (and B does not yet sound) for this share of the overlap, whatever the
    /// kind: an intro's hold, a silent lead-in. B going dry before it may move the meeting point.
    public var holdShare: Double
    public var speed: Double
    /// Where both songs are heard together, and where their beats meet, in the blend's time (ms of
    /// A from the start); the beats part by `driftGap` ms a ms away from the lock. A gap of 0 has
    /// no bound.
    public var bothHeardStartMs: Double
    public var bothHeardEndMs: Double
    public var lockMs: Double
    public var driftGap: Double
    /// A start too quiet to be heard before the acknowledgement can arrive commits at its deadline
    /// without waiting for it.
    public var commitBeforeAck: Bool

    public init(generation: Int64, startMs: Double, overlapMs: Double, latestEndMs: Double, outgoingEndMs: Double,
                lateMoveBars: Int, barMs: Double = .nan, holdShare: Double = 0, speed: Double = 1,
                bothHeardStartMs: Double = 0, bothHeardEndMs: Double = 0, lockMs: Double = 0, driftGap: Double = 0,
                commitBeforeAck: Bool = false) {
        self.generation = generation
        self.startMs = startMs
        self.overlapMs = overlapMs
        self.latestEndMs = latestEndMs
        self.outgoingEndMs = outgoingEndMs
        self.lateMoveBars = lateMoveBars
        self.barMs = barMs
        self.holdShare = holdShare
        self.speed = speed
        self.bothHeardStartMs = bothHeardStartMs
        self.bothHeardEndMs = bothHeardEndMs
        self.lockMs = lockMs
        self.driftGap = driftGap
        self.commitBeforeAck = commitBeforeAck
    }
}

/// One action, from `BlendSession.takeAction`.
public struct BlendAction: Sendable, Equatable {
    public var kind: BlendActionKind
    public var frame: Int64
    public var value: Int64
    public var ms: Double
}

/// One event, from `BlendSession.takeEvent`.
public struct BlendEvent: Sendable, Equatable {
    public var kind: BlendEventKind
    public var generation: Int64
    public var frame: Int64
    public var ms: Double
    public var ms2: Double
    public var cause: BlendCause
}

/// Where a generation's start stands between the main thread and the render thread: the arm
/// opens it initial, the render proposes, main acks or cancels, and the render cancels at its
/// commit deadline.
public enum CommitState: Int64, Sendable, CaseIterable {
    case initial = 0
    case proposed = 1
    case acked = 2
    case cancelled = 3
}

/// A generation and its state packed into one Int64: the generation above the two low bits that
/// hold the state, so one compare-and-set checks both and a stale generation never matches a
/// reused slot.
public func commitWord(generation: Int64, state: CommitState) -> Int64 { (generation << 2) | state.rawValue }

public func commitGeneration(of word: Int64) -> Int64 { Int64(bitPattern: UInt64(bitPattern: word) >> 2) }

public func commitState(of word: Int64) -> CommitState { CommitState(rawValue: word & 3)! }

/// The one word main and the render thread share, lock-free and allocation-free. Before any arm
/// it holds generation 0, cancelled.
public final class CommitWord: Sendable {
    private let word = Atomic<Int64>(commitWord(generation: 0, state: .cancelled))

    public init() {}

    public func get() -> Int64 { word.load(ordering: .acquiring) }

    /// A new generation, initial. The registering thread opens it before it sends the arm.
    public func open(_ generation: Int64) { word.store(commitWord(generation: generation, state: .initial), ordering: .releasing) }

    public func compareAndSet(expect: Int64, update: Int64) -> Bool {
        word.compareExchange(expected: expect, desired: update, ordering: .acquiringAndReleasing).exchanged
    }

    /// The generation's state, or nil once the word holds another one.
    public func state(of generation: Int64) -> CommitState? {
        let now = get()
        return commitGeneration(of: now) == generation ? commitState(of: now) : nil
    }

    public func propose(_ generation: Int64) -> Bool {
        compareAndSet(expect: commitWord(generation: generation, state: .initial), update: commitWord(generation: generation, state: .proposed))
    }

    public func ack(_ generation: Int64) -> Bool {
        compareAndSet(expect: commitWord(generation: generation, state: .proposed), update: commitWord(generation: generation, state: .acked))
    }

    /// Initial or proposed to cancelled; false once acked, cancelled or stale.
    public func cancel(_ generation: Int64) -> Bool {
        compareAndSet(expect: commitWord(generation: generation, state: .initial), update: commitWord(generation: generation, state: .cancelled))
            || compareAndSet(expect: commitWord(generation: generation, state: .proposed), update: commitWord(generation: generation, state: .cancelled))
    }
}

/// When to arm a blend, in A's song time: the earlier of the run-up start and the moment the start
/// is the proposal lead plus the spare's ready lead ahead in output time. At high speed the run-up
/// alone can leave less output time than the lead.
public func blendArmAtMs(startMs: Double, preRollLeadMs: Double, proposalLeadMs: Double, readyLeadMs: Double, speed: Double) -> Double {
    min(startMs - preRollLeadMs, startMs - (proposalLeadMs + readyLeadMs) * speed)
}

/// After a drop of the pace to `pace`: the first moment at or after `fromMs`, inside the
/// both-heard stretch and never before `holdEndMs` (the outgoing song keeps its own level through
/// a hold), where the beats would be more than `blendDriftLimitMs` times the pace apart (their
/// offset is `driftGap` times the distance from the lock); nil when there is none. All in the
/// blend's time.
public func blendDropCutAtMs(bothHeardStartMs: Double, bothHeardEndMs: Double, holdEndMs: Double, lockMs: Double,
                             driftGap: Double, pace: Double, fromMs: Double) -> Double? {
    if driftGap <= 0 { return nil }
    let from = max(bothHeardStartMs, holdEndMs, fromMs)
    if from > bothHeardEndMs { return nil }
    let within = blendDriftLimitMs * pace / driftGap
    if from < lockMs - within || from > lockMs + within { return from }
    return lockMs + within < bothHeardEndMs ? lockMs + within : nil
}

/// What a re-plan takes as now: A's song time at the render's bus frame plus the proposal lead,
/// never the heard position.
public func blendReplanNowMs(outgoingAtHeadMs: Double, proposalLeadFrames: Int64, sampleRate: Int, speed: Double) -> Double {
    outgoingAtHeadMs + Double(proposalLeadFrames) * 1_000 / Double(sampleRate) * speed
}

/// B's rate at its song time `incomingMs`: the matched rate until its time since entry over the
/// rate passes `easeFromMs` (from the blend end event), then eased back to its own. Song time
/// stands still while B is held, so a stall never moves the easing.
public func blendIncomingRate(rate: Double, incomingMs: Double, entryMs: Double, easeFromMs: Double) -> Double {
    rate == 1 ? 1 : beatMatchRateAt(rate, (incomingMs - entryMs) / rate, easeFromMs)
}

/// The spare song that failed for this transition. It is not loaded into the spare again until
/// the song changes, so the playing deck reaches it once by its own advance instead of the spare
/// reloading it on every check.
public struct BlendFailedSpare: Sendable, Equatable {
    public private(set) var failedFor: String?

    public init() {}

    public mutating func failed(_ entryId: String) { failedFor = entryId }

    public func mayLoad(_ entryId: String) -> Bool { entryId != failedFor }

    public mutating func songChanged() { failedFor = nil }
}

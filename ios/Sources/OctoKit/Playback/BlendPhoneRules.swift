import Foundation

/// Whether the person wants music now: playing was asked for, the queue has not played out and
/// the song did not stop at its end as asked. A call ending, or headphones coming back, resume
/// only this, so a call during buffering resumes and a finished queue never starts again.
public func wantedMusic(playWhenReady: Bool, ended: Bool, stoppedAtEnd: Bool) -> Bool {
    playWhenReady && !ended && !stoppedAtEnd
}

/// A deck's dry-out and rejoin: when its sound runs out it fades its last frames over
/// `blendLeaveRampMs` instead of stopping dead, only while more is truly late (not decoded to the
/// song's end, no song following, nothing delivered since the last render); and it comes back
/// with a ramp of the same length only once it holds a render cycle plus a quarter of its buffer,
/// or its stream has ended, so a feed at real time after a stall plays on instead of fading out
/// and in every few frames.
public enum DeckFlow {
    /// Whether the frames a render has left should fade out because the sound is running dry.
    public static func fadesOut(decodedToEnd: Bool, hasFollowing: Bool, deliveredSinceLast: Bool) -> Bool {
        !decodedToEnd && !hasFollowing && !deliveredSinceLast
    }

    /// Whether a dry deck plays again.
    public static func rejoins(waitingFrames: Int, cycleFrames: Int, bufferFrames: Int, ended: Bool) -> Bool {
        ended || waitingFrames >= cycleFrames + bufferFrames / 4
    }

    /// Frames of a ramp at the bus rate, at the deck's `rate` of source frames per bus frame.
    public static func rampFrames(ms: Double = blendLeaveRampMs, rate: Double = 1) -> Int {
        max(1, Int((ms * blendBusRate / 1_000 * max(rate, 0.01)).rounded()))
    }

    /// The level `index` frames into a fade-out of `frames` frames (1 just before it starts).
    public static func fadeOutGain(_ index: Int, of frames: Int) -> Float {
        frames <= 0 ? 0 : max(0, 1 - Float(index + 1) / Float(frames))
    }

    /// The level `index` frames into a ramp in of `frames` frames.
    public static func rampInGain(_ index: Int, of frames: Int) -> Float {
        frames <= 0 ? 1 : min(1, Float(index + 1) / Float(frames))
    }
}

/// What the outgoing deck does when its song ends and a follower is loaded on it.
public enum OutgoingEnd: Sendable, Equatable {
    /// Its own gapless advance, as with no blend.
    case moveOn
    /// Holds at its end: renders silence and moves on to nothing until told.
    case hold
    /// Ends: the engine's own ended path plays what comes next.
    case end
}

/// The outgoing deck at its song's end. With no blend registered (and no undone start waiting to
/// hear what follows) it advances by itself as always. Before the blend's start, its advance is
/// the plan's cancel ("A moved on") and it advances. From the start on, it never advances or ends
/// by itself: the session hears its end and the blend goes on with the incoming song alone. An
/// undone start that caught it at its end holds it until main names its follower (`follow`: the
/// follower's serial, or `blendPassNone`), and only that follower ever plays.
public func outgoingEnd(registered: Bool, started: Bool, holdingForAnswer: Bool, follow: Int64?,
                        followerSerial: Int64?) -> OutgoingEnd {
    if let follow {
        if follow == blendPassNone { return .end }
        if follow == blendPassHeld { return followerSerial == nil ? .end : .moveOn }
        return followerSerial == follow ? .moveOn : .end
    }
    if holdingForAnswer { return .hold }
    if registered && started { return .hold }
    return followerSerial == nil ? .end : .moveOn
}

/// The answer to "what does the outgoing song play after its end": nothing when it stops at its
/// end, else the follower loaded on its deck now (after the edit's own write and the deck's
/// follower brought in line with the queue), by its serial, or nothing when none matches the
/// queue's next song.
public func outgoingFollowUp(stopAtEnd: Bool, followerSerial: Int64?, followerMatchesNext: Bool) -> Int64 {
    if stopAtEnd { return blendPassNone }
    guard let followerSerial, followerMatchesNext else { return blendPassNone }
    return followerSerial
}

/// The spare is parked this long before the incoming song's entry (the song's start with no plan
/// yet), so its frames before the entry are there to drop and its first is exact.
public let spareParkLeadMs = 100

/// The spare is registered this much before the proposal lead, so it is ready by then.
public let spareReadyLeadMs = 500

extension TransitionPlan {
    /// Where the incoming song is parked: just before its entry.
    public func parkAtMs() -> Int { overlapMs <= 0 ? 0 : max(0, entryMs - spareParkLeadMs) }

    /// The outgoing song's bar in ms when the plan is locked to it, else nan.
    public func barMs() -> Double {
        guard barLocked, let beat = beatMs, beat > 0 else { return .nan }
        return beat * Double(beatsPerBar)
    }

    /// Where in the playing song the blend is registered and the spare plays: the earlier of the
    /// run-up's start and the moment the start is the proposal lead plus the spare's ready lead
    /// ahead, in output time at `speed`.
    public func armAtMs(proposalLeadMs: Double, speed: Double) -> Double {
        blendArmAtMs(startMs: Double(startMs), preRollLeadMs: Double(preRollLeadMs), proposalLeadMs: proposalLeadMs,
                     readyLeadMs: Double(spareReadyLeadMs), speed: speed)
    }
}

/// Whether an edit of the queue keeps a registered blend: on the iPhone the spare holds no copy
/// of the queue, so an edit that leaves the song on now and the next song (entry and file) as
/// they were, compared after the edit, changes nothing the blend depends on.
public func editKeepsBlend(currentBefore: String?, currentAfter: String?, registeredNext: (entryId: String?, uri: String)?,
                           nextAfter: (entryId: String?, uri: String)?) -> Bool {
    guard let registeredNext, let nextAfter, currentBefore == currentAfter else { return false }
    return registeredNext.entryId == nextAfter.entryId && registeredNext.uri == nextAfter.uri
}

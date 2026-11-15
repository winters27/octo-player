import Foundation

/// The rate every deck plays at; bus frames count at it, whatever rate the hardware runs at.
public let blendBusRate = 48_000.0

/// The bus clock: the hardware frames the mix limiter passes, counted as bus frames (48 kHz), so a
/// 44.1 kHz route (AirPlay) counts the same time as the deck's own rate. It only grows: a rebuilt
/// unit or a new hardware rate carries on from where the count stood.
public struct BusClock: Sendable, Equatable {
    /// The bus frame of the next hardware frame, with its fraction.
    public private(set) var exact = 0.0

    public init() {}

    /// The bus frame of the next hardware frame.
    public var bus: Int64 { Int64(exact.rounded(.down)) }

    /// Bus frames a hardware frame lasts at `hardwareRate`.
    public static func perHardwareFrame(_ hardwareRate: Double) -> Double { hardwareRate > 0 ? blendBusRate / hardwareRate : 1 }

    /// The bus frame of hardware frame `offset` into the coming cycle.
    public func bus(atHardwareOffset offset: Int, hardwareRate: Double) -> Double {
        exact + Double(offset) * Self.perHardwareFrame(hardwareRate)
    }

    /// `frames` hardware frames passed at `hardwareRate`.
    public mutating func passed(_ frames: Int, hardwareRate: Double) {
        exact += Double(max(0, frames)) * Self.perHardwareFrame(hardwareRate)
    }
}

/// Where a deck's frames are heard: each deck counts the source frames its time-pitch stage pulls
/// (rendered silence included, so the count never stops while the engine runs), and a source
/// frame is heard at the bus frame given by the running integral of the stage's rate (a new piece
/// at each change of rate or latency) plus the stage's latency, anchored where the stage was last
/// reset. Lives on the render thread: fixed storage, nothing allocated after `init`.
public final class DeckClock: @unchecked Sendable {
    /// From source frame `source` on, source frames come out at `rate` per bus frame from bus
    /// frame `bus`, `latency` bus frames later.
    public struct Piece: Sendable, Equatable {
        public var source: Int64
        public var bus: Double
        public var rate: Double
        public var latency: Double
    }

    private static let slots = 16
    private let pieces: UnsafeMutablePointer<Piece>
    /// The newest piece, and how many are kept.
    private var newest = 0
    private var count = 1

    /// Source frames pulled since the deck was made; it only grows.
    public private(set) var source: Int64 = 0

    public init(bus: Int64 = 0, rate: Double = 1, latency: Double = 0) {
        pieces = .allocate(capacity: Self.slots)
        pieces.initialize(repeating: Piece(source: 0, bus: Double(bus), rate: max(rate, 1e-6), latency: latency), count: Self.slots)
    }

    deinit { pieces.deallocate() }

    public var current: Piece { pieces[newest] }

    /// The stage was reset (an engine start, a configuration change): the next source frame is
    /// heard at `bus` plus the stage's latency.
    public func reset(atBus bus: Int64, rate: Double, latency: Double) {
        newest = 0
        count = 1
        pieces[0] = Piece(source: source, bus: Double(bus), rate: max(rate, 1e-6), latency: max(0, latency))
    }

    /// `frames` source frames were pulled.
    public func pulled(_ frames: Int) { source += Int64(max(0, frames)) }

    /// The stage's rate or latency changed from the next source frame on. Answers whether the
    /// mapping changed.
    @discardableResult
    public func set(rate: Double, latency: Double) -> Bool {
        let now = pieces[newest]
        let rate = max(rate, 1e-6)
        let latency = max(0, latency)
        if now.rate == rate && now.latency == latency { return false }
        let piece = Piece(source: source, bus: now.bus + Double(source - now.source) / now.rate, rate: rate, latency: latency)
        if now.source == source {
            pieces[newest] = piece
            return true
        }
        newest = (newest + 1) % Self.slots
        count = min(count + 1, Self.slots)
        pieces[newest] = piece
        return true
    }

    /// The bus frame where source frame `index` is heard, unrounded.
    public func exactBus(ofSource index: Int64) -> Double {
        let piece = pieceHolding(index)
        return piece.bus + Double(index - piece.source) / piece.rate + piece.latency
    }

    /// The bus frame where source frame `index` is heard.
    public func bus(ofSource index: Int64) -> Int64 { Int64((exactBus(ofSource: index) + 0.5).rounded(.down)) }

    /// The first source frame heard at or after bus frame `bus`.
    public func source(ofBus bus: Int64) -> Int64 {
        let target = Double(bus)
        var i = newest
        for step in 0..<count {
            let piece = pieces[i]
            let from = piece.bus + piece.latency
            if target >= from || step == count - 1 {
                let index = Double(piece.source) + (target - from) * piece.rate
                let first = Int64((index - 1e-6).rounded(.up))
                // A piece with a later start owns the frames from there on.
                if step > 0 {
                    let later = pieces[(i + 1) % Self.slots]
                    if first >= later.source { return later.source }
                }
                return first
            }
            i = (i - 1 + Self.slots) % Self.slots
        }
        return source
    }

    /// The bus frame up to which this deck's sound is decided: where its next source frame is heard.
    public var renderedBus: Int64 { bus(ofSource: source) }

    private func pieceHolding(_ index: Int64) -> Piece {
        var i = newest
        for _ in 0..<(count - 1) {
            if pieces[i].source <= index { return pieces[i] }
            i = (i - 1 + Self.slots) % Self.slots
        }
        return pieces[i]
    }
}

/// How a deck's time-pitch unit is set for a pace: bypassed at the song's own speed and pitch,
/// and delaying its sound by its reported latency (in bus frames) either way. A bypassed unit only
/// stops stretching; its sound still comes out that much later, so a deck at its own pace and a
/// tempo-matched one line up only when both count it.
public func timePitchSetting(speed: Float, pitch: Float, latencySeconds: Double, deckRate: Double) -> (bypass: Bool, latencyFrames: Double) {
    (speed == 1 && pitch == 1, max(0, latencySeconds) * deckRate)
}

/// The numbers a phone's session runs on, from what the render measures: the larger time-pitch
/// latency and one render cycle place every action that lands on a later frame; the start commits
/// (or is cancelled without an acknowledgement) one placement horizon plus one render cycle before
/// it; and it is proposed the ticker's period plus main's measured lag before that, which is also
/// the lead a start must be decided by. All in bus frames.
public func phoneBlendTiming(latencyFrames: Int64, cycleFrames: Int64, tickerMs: Double, mainLagMs: Double,
                             ringLeadMs: Double, sinkBufferMs: Double) -> BlendTiming {
    let horizon = max(0, latencyFrames) + max(1, cycleFrames)
    let commit = horizon + max(1, cycleFrames)
    let lead = commit + Int64(((tickerMs + max(0, mainLagMs)) * blendBusRate / 1_000).rounded(.up))
    return BlendTiming(sampleRate: Int(blendBusRate), placementHorizonFrames: horizon, proposalLeadFrames: lead, proposeAtFrames: lead,
                       commitAtFrames: commit, ringLeadMs: ringLeadMs, sinkBufferMs: sinkBufferMs)
}

/// How often the main thread drains the session's events.
public let blendTickerMs = 40.0

/// On a route whose output delay is over this (AirPlay), a start too quiet to be heard before the
/// acknowledgement arrives is acknowledged this long before it is heard.
public let airPlayAckLeadMs = 1_000.0

/// Whether a blend on a route `outputDelayMs` late commits its start at its deadline without
/// waiting for main: only on a route over `airPlayAckLeadMs` late, and only when, over the
/// exposure window, the incoming song's gain stays under -40 dB and the outgoing song's within
/// 0.5 dB of its own level, so an interrupt before the acknowledgement leaves only frames nobody
/// hears. The window, in output time at `speed`, is everything an interrupt can find rendered: the
/// output delay less that lead, plus `lateMs` (how late main can be told: the commit deadline, the
/// ticker's period and main's lag), plus the 250 ms fade that follows it. A short crossfade, where
/// the incoming song is loud at once, waits for the acknowledgement.
public func commitsBeforeAck(_ plan: TransitionPlan, outputDelayMs: Double, speed: Double = 1, lateMs: Double = 0) -> Bool {
    guard outputDelayMs > airPlayAckLeadMs, plan.overlapMs > 0 else { return false }
    let windowMs = (outputDelayMs - airPlayAckLeadMs + max(0, lateMs) + blendDisarmRampMs) * max(speed, 0.01)
    let incoming = DeckTransition(entryId: nil, plan: plan, incoming: true)
    let outgoing = DeckTransition(entryId: nil, plan: plan, incoming: false)
    let rate = plan.beatMatchRate ?? 1
    var since = 0.0
    while since <= windowMs {
        let t = since / Double(plan.overlapMs)
        if t >= 1 { return false }
        // B's progress runs on its own song time, at its rate.
        let inGain = incoming.gainAt(incoming.progressAt(Double(plan.entryMs) + since * rate))
        let outGain = outgoing.gainAt(t)
        if inGain > 0 && 20 * log10(inGain) >= -40 { return false }
        if outGain <= 0 || abs(20 * log10(outGain)) > 0.5 { return false }
        since += 10
    }
    return true
}

/// When main acts on a session event drained by the ticker: a start's proposal at once (on a route
/// that commits a quiet start before the acknowledgement, `airPlayAckLeadMs` before it is heard);
/// a question, a cancel and a start that did not happen at once; anything the screen shows (the
/// blend's end, a move) once its bus frame is heard. `nowBus` is the bus clock now.
public func blendEventDue(_ event: BlendEvent, nowBus: Int64, outputDelayFrames: Int64, quietStart: Bool) -> Bool {
    switch event.kind {
    case .proposed:
        guard quietStart else { return true }
        return nowBus >= event.frame + outputDelayFrames - Int64(airPlayAckLeadMs * blendBusRate / 1_000)
    case .followUpNeeded, .cancelled, .incomingNotReady:
        return true
    case .blendEnd, .moved:
        return nowBus >= event.frame + outputDelayFrames
    }
}

/// Main's lag for the handover margin: the largest seen over the last ten minutes plus 10 ms, and
/// never under 50 ms until 40 samples are in. Kept in one-minute buckets.
public struct LagWindow: Sendable {
    private var buckets: [(minute: Int64, largest: Double, samples: Int)] = []
    public static let spanMs: Int64 = 600_000
    public static let floorMs = 50.0
    public static let floorSamples = 40
    public static let spareMs = 10.0

    public init() {}

    public mutating func add(nowMs: Int64, lagMs: Double) {
        let minute = nowMs / 60_000
        buckets.removeAll { $0.minute <= minute - Self.spanMs / 60_000 }
        if let last = buckets.indices.last, buckets[last].minute == minute {
            buckets[last].largest = max(buckets[last].largest, lagMs)
            buckets[last].samples += 1
        } else {
            buckets.append((minute, lagMs, 1))
        }
    }

    public func value(nowMs: Int64) -> Double {
        let minute = nowMs / 60_000
        let live = buckets.filter { $0.minute > minute - Self.spanMs / 60_000 }
        let samples = live.reduce(0) { $0 + $1.samples }
        let largest = live.map(\.largest).max() ?? 0
        let measured = largest + Self.spareMs
        return samples < Self.floorSamples ? max(Self.floorMs, measured) : measured
    }
}

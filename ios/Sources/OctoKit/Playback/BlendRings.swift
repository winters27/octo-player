import Foundation
import Synchronization

/// One writer, one reader, a fixed number of slots: the way inputs cross from main to the render
/// thread and events back. Neither side waits or allocates; a full ring refuses the write, and
/// the writer keeps what it could not send.
public final class BlendRing<Element>: @unchecked Sendable {
    public let capacity: Int
    private let slots: UnsafeMutablePointer<Element>
    private let written = Atomic<Int>(0)
    private let read = Atomic<Int>(0)

    public init(capacity: Int, filler: Element) {
        self.capacity = max(1, capacity)
        slots = .allocate(capacity: self.capacity)
        slots.initialize(repeating: filler, count: self.capacity)
    }

    deinit {
        slots.deinitialize(count: capacity)
        slots.deallocate()
    }

    /// Writer: how many more fit now.
    public var room: Int { capacity - (written.load(ordering: .relaxed) - read.load(ordering: .acquiring)) }

    /// Writer: false when full.
    @discardableResult
    public func push(_ element: Element) -> Bool {
        let w = written.load(ordering: .relaxed)
        guard w - read.load(ordering: .acquiring) < capacity else { return false }
        slots[w % capacity] = element
        written.store(w + 1, ordering: .releasing)
        return true
    }

    /// Reader: the oldest element, or nil when empty.
    public func pop() -> Element? {
        let r = read.load(ordering: .relaxed)
        guard r < written.load(ordering: .acquiring) else { return nil }
        let element = slots[r % capacity]
        read.store(r + 1, ordering: .releasing)
        return element
    }
}

/// What main tells the render-thread session.
public enum BlendMainKind: Sendable, Equatable {
    /// A new plan, its generation already open on the commit word: `arm`, and which deck plays
    /// out (`outgoing`, 0 or 1) and which comes in; the incoming song enters at `entryMs` of its
    /// own song.
    case arm
    /// An interrupt by the phase rule (`reason`) of `generation`.
    case disarm
    /// A deck's player failed (`deck`), with the follow-up answer in `value`.
    case failed
    /// What the outgoing song plays after its end, for an undone start of `generation`: the
    /// follower's serial in `value`, or `blendPassNone`.
    case followUp
    /// The listener's pace became `pace`; the switch frame is the outgoing deck's first frame
    /// carrying it, which the render finds.
    case paceChanged
    /// The Crossfader stopped the outgoing deck of `generation`.
    case outgoingStopped
    /// The engine restarted: both time-pitch stages were reset.
    case restarted
}

/// One message from main to the render thread. A plain value: nothing in it is counted or freed
/// on the render thread.
public struct BlendMainInput: Sendable, Equatable {
    public var kind: BlendMainKind
    public var generation: Int64 = 0
    public var arm = BlendArm(generation: 0, startMs: 0, overlapMs: 0, latestEndMs: 0, outgoingEndMs: 0, lateMoveBars: 0)
    public var outgoing = 0
    public var entryMs = 0.0
    public var reason = BlendDisarm.edit
    public var deck = BlendDeck.a
    public var value: Int64 = 0
    public var pace = 1.0
    /// For an arm: the songs it was made for on the outgoing and the incoming deck (-1 unknown).
    public var outgoingSerial: Int64 = -1
    public var incomingSerial: Int64 = -1

    public init(kind: BlendMainKind) { self.kind = kind }

    public static func arm(_ arm: BlendArm, outgoing: Int, entryMs: Double, outgoingSerial: Int64 = -1,
                           incomingSerial: Int64 = -1) -> BlendMainInput {
        var m = BlendMainInput(kind: .arm)
        m.generation = arm.generation
        m.arm = arm
        m.outgoing = outgoing
        m.entryMs = entryMs
        m.outgoingSerial = outgoingSerial
        m.incomingSerial = incomingSerial
        return m
    }

    public static func disarm(_ reason: BlendDisarm, generation: Int64) -> BlendMainInput {
        var m = BlendMainInput(kind: .disarm)
        m.reason = reason
        m.generation = generation
        return m
    }

    public static func failed(_ deck: BlendDeck, generation: Int64, followUp: Int64) -> BlendMainInput {
        var m = BlendMainInput(kind: .failed)
        m.deck = deck
        m.generation = generation
        m.value = followUp
        return m
    }

    public static func followUp(generation: Int64, pass: Int64) -> BlendMainInput {
        var m = BlendMainInput(kind: .followUp)
        m.generation = generation
        m.value = pass
        return m
    }

    public static func paceChanged(_ pace: Double) -> BlendMainInput {
        var m = BlendMainInput(kind: .paceChanged)
        m.pace = pace
        return m
    }

    public static func outgoingStopped(generation: Int64) -> BlendMainInput {
        var m = BlendMainInput(kind: .outgoingStopped)
        m.generation = generation
        return m
    }
}

/// The event ring's size. Each generation queues at most one proposal, one "moved" (coalesced in
/// the session, carrying the cumulative move), one end of its start (a blend end, a start that
/// did not happen or a cancel) and one question for the follow-up; the render never queues an
/// event of a retired generation, and main drains the ring before every arm, so at most two live
/// generations hold slots. An event that finds no room stays in the session until there is.
public let blendEventRingSlots = 2 * 4

/// The event ring's filler.
public let blendNoEvent = BlendEvent(kind: .moved, generation: -1, frame: 0, ms: 0, ms2: 0, cause: .none)

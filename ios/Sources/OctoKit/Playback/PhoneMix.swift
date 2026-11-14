import Foundation
import Synchronization

/// What the debug build's tap hears from the phone's mix, on the render thread.
public protocol PhoneMixObserver: AnyObject {
    func note(input: BlendMainInput, bus: Int64)
    func note(action: BlendAction, bus: Int64)
    func note(event: BlendEvent)
    func note(restartAt bus: Int64, replayBus: Double)
    func cycle(bus: Int64, limiterLevel: Float, limiting: Bool)
    func deck(_ index: Int, _ channels: UnsafeMutablePointer<UnsafeMutablePointer<Float>>, count: Int, frames: Int, firstBus: Int64)
}

/// The blend's rules where the audio is decided: the render thread owns the `BlendSession`, both
/// decks' renders and stages and the limiter on the sum. Each hardware cycle runs it twice: before
/// the decks render (main's messages taken, so they apply between session steps) and after (the
/// session advanced to the lower of the two decks' rendered bus frames, its actions placed on each
/// deck's frames, its events handed to main, the sum limited, the bus clock moved on). Main talks
/// to it only through the two rings, the commit word and a few published numbers; nothing here
/// allocates or locks once running.
public final class PhoneMix: @unchecked Sendable {
    public let word: CommitWord
    public let session: BlendSession
    public let decks: [DeckRender]
    public let stages: [DeckBlendStage]
    /// Main's messages in, the session's events out.
    public let inputs = BlendRing<BlendMainInput>(capacity: 64, filler: BlendMainInput(kind: .restarted))
    public let events = BlendRing<BlendEvent>(capacity: blendEventRingSlots, filler: blendNoEvent)

    /// What main publishes: its estimate of the larger time-pitch latency in bus frames (for its
    /// own arm and plan leads; the render reads the decks' own each cycle), main's lag and the
    /// output delay in ms, and a count bumped before every engine start that reset the stages.
    public let latencyFrames = Atomic<Int64>(0)
    public let mainLagBits = Atomic<UInt64>(LagWindow.floorMs.bitPattern)
    public let outputDelayBits = Atomic<UInt64>(Double(0).bitPattern)
    public let restarts = Atomic<Int>(0)
    /// What the render publishes: the bus frame of the next hardware frame, and the hardware rate.
    public let busNow = Atomic<Int64>(0)
    public let hardwareRateBits = Atomic<UInt64>(Double(48_000).bitPattern)

    /// The debug build's tap.
    public unowned(unsafe) var observer: (any PhoneMixObserver)?

    // MARK: Render thread

    private var bus = BusClock()
    private var limiter: MixLimiter?
    /// The sum's channel buffers of the cycle, made at `prepare` so a cycle allocates nothing.
    private var sumChannels: [UnsafeMutablePointer<Float>] = []
    private var hardwareRate = 48_000.0
    /// The cycle being rendered: its first bus frame, its length in bus frames, and its number.
    public private(set) var cycleBus: Int64 = 0
    public private(set) var cycleBusFrames: Int64 = 1_024
    public private(set) var cycle = 0
    public private(set) var timing: BlendTiming
    private var seenRestarts = 0
    /// The decks of the generation registered last: which plays out (-1 before any).
    private var outgoing = -1
    private var registered = false

    public init(decks: [DeckRender]) {
        let word = CommitWord()
        let timing = phoneBlendTiming(latencyFrames: 0, cycleFrames: 1_024, tickerMs: blendTickerMs, mainLagMs: LagWindow.floorMs,
                                      ringLeadMs: 50, sinkBufferMs: 0)
        self.word = word
        self.timing = timing
        session = BlendSession(word: word, timing: timing)
        self.decks = decks
        stages = decks.map(\.stage)
        if stages.count == 2 {
            stages[0].partner = stages[1]
            stages[1].partner = stages[0]
        }
        for i in 0..<decks.count {
            decks[i].mix = self
            decks[i].index = i
        }
    }

    /// Main thread, while the engine does not render: the sum's format.
    public func prepare(sampleRate: Double, channels: Int) {
        let old = limiter
        let fresh = MixLimiter(sampleRate: sampleRate, channels: channels)
        if let old {
            if old.onAt != Int64.max { fresh.windowOn(fromBus: old.onAt) }
            if old.offAt != Int64.max { fresh.windowOff(fromBus: old.offAt) }
        }
        limiter = fresh
        sumChannels = [UnsafeMutablePointer<Float>](repeating: UnsafeMutablePointer<Float>(bitPattern: 16)!, count: fresh.channels)
        hardwareRate = sampleRate
        hardwareRateBits.store(sampleRate.bitPattern, ordering: .relaxed)
    }

    /// The limiter's delay, in hardware frames, for the output delay.
    public var limiterLatencyFrames: Int { limiter?.latencyFrames ?? 0 }

    /// How far ahead of the bus the decks' sound is decided as their stages run now, in bus
    /// frames: the larger of each time-pitch's latency plus how far past a cycle it pulls.
    public var decksLatencyFrames: Double {
        var largest = 0.0
        for i in 0..<decks.count { largest = max(largest, decks[i].stage.clock.current.latency + Double(decks[i].pullLead)) }
        return largest
    }

    /// Before the decks render: a cycle of `frames` hardware frames begins.
    public func willPull(frames: Int) {
        cycle &+= 1
        cycleBus = bus.bus
        cycleBusFrames = Int64((Double(frames) * BusClock.perHardwareFrame(hardwareRate)).rounded(.up))
        let restartsNow = restarts.load(ordering: .acquiring)
        if restartsNow != seenRestarts {
            seenRestarts = restartsNow
            // Both stages were reset: the decks in a live generation replay the shorter of their
            // delays, so both move by the same stretch and stay lined up; a deck in none replays
            // its own and loses nothing.
            var replay = Double.infinity
            for i in 0..<decks.count where decks[i].stage.live {
                replay = min(replay, decks[i].stage.clock.current.latency)
            }
            for i in 0..<decks.count {
                let own = decks[i].stage.clock.current.latency
                decks[i].restarted(atBus: cycleBus, replayBus: decks[i].stage.live && replay != .infinity ? replay : own)
            }
            observer?.note(restartAt: cycleBus, replayBus: replay == .infinity ? 0 : replay)
        }
        // Each deck takes its orders before the timing is worked out, so the cycle reads both
        // decks' rates and latencies as they render now.
        for i in 0..<decks.count { decks[i].prepareCycle() }
        let latency = Int64(decksLatencyFrames.rounded(.up))
        timing = phoneBlendTiming(latencyFrames: latency, cycleFrames: cycleBusFrames, tickerMs: blendTickerMs,
                                  mainLagMs: Double(bitPattern: mainLagBits.load(ordering: .relaxed)),
                                  ringLeadMs: Double(latency + cycleBusFrames) * 1_000 / blendBusRate, sinkBufferMs: 0)
        session.timing = timing
        while let input = inputs.pop() { take(input) }
        placeActions()
    }

    /// After the decks rendered: the session steps, then the sum (one buffer per channel) is
    /// limited.
    public func process(_ buffers: UnsafeMutablePointer<UnsafeMutablePointer<Float>>, channels: Int, frames: Int) {
        var to = Int64.max
        for i in 0..<decks.count { to = min(to, decks[i].renderedBus(cycle: cycle, cycleBus: cycleBus, cycleFrames: cycleBusFrames)) }
        if to == Int64.max { to = cycleBus }
        session.advance(to: to, head: cycleBus, outgoingMs: outgoingMs(at: to))
        placeActions()
        while events.room > 0, let event = session.takeEvent(head: cycleBus) {
            events.push(event)
            observer?.note(event: event)
        }
        if registered && session.state == .idle {
            registered = false
            for i in 0..<stages.count { stages[i].retire() }
        }
        if let limiter, sumChannels.count == limiter.channels, channels >= limiter.channels {
            for i in 0..<limiter.channels { sumChannels[i] = buffers[i] }
            limiter.process(sumChannels, frames: frames, firstBus: bus.exact, busPerFrame: BusClock.perHardwareFrame(hardwareRate))
        }
        observer?.cycle(bus: cycleBus, limiterLevel: limiter?.level ?? 1, limiting: limiter?.enabled ?? false)
        bus.passed(frames, hardwareRate: hardwareRate)
        busNow.store(bus.bus, ordering: .releasing)
    }

    /// The outgoing song's time of its frame heard at bus frame `to`, while a blend is registered.
    private func outgoingMs(at to: Int64) -> Double {
        guard registered, outgoing >= 0 else { return .nan }
        let deck = decks[outgoing]
        let songMs = deck.songMsNext
        if songMs.isNaN { return .nan }
        let rate = deck.stage.clock.current.rate
        return songMs - Double(deck.stage.clock.renderedBus - to) * rate * 1_000 / blendBusRate
    }

    private func take(_ input: BlendMainInput) {
        switch input.kind {
        case .arm:
            guard input.outgoing >= 0 && input.outgoing < stages.count && stages.count == 2 else { return }
            // What an earlier generation still has to do lands on the decks it was for.
            placeActions()
            outgoing = input.outgoing
            let into = 1 - input.outgoing
            // The decks take the songs they were given first: a song taken lets go of its stage.
            decks[outgoing].registered(serial: input.outgoingSerial)
            decks[into].registered(serial: input.incomingSerial)
            stages[outgoing].armOutgoing(session, startMs: input.arm.startMs, overlapMs: input.arm.overlapMs)
            stages[into].armIncoming(session, entryMs: input.entryMs)
            session.arm(input.arm)
            registered = true
        case .disarm:
            if input.generation == session.generation { session.disarm(input.reason) }
        case .failed:
            if input.generation == session.generation { session.failed(input.deck, followUp: input.value) }
        case .followUp:
            session.followUp(generation: input.generation, pass: input.value)
        case .paceChanged:
            guard registered, outgoing >= 0 else { return }
            session.paceChanged(pace: input.pace, switchFrame: stages[outgoing].clock.renderedBus)
        case .outgoingStopped:
            session.outgoingStopped(generation: input.generation)
        case .restarted:
            break
        }
        observer?.note(input: input, bus: cycleBus)
    }

    /// Takes the session's actions and places each on the decks it concerns: the limiter's window
    /// on the sum, the rest on the outgoing and the incoming deck's stages.
    private func placeActions() {
        var any = false
        while let action = session.takeAction() {
            any = true
            observer?.note(action: action, bus: cycleBus)
            switch action.kind {
            case .limiterOn: limiter?.windowOn(fromBus: action.frame)
            case .limiterOff: limiter?.windowOff(fromBus: action.frame)
            default:
                guard outgoing >= 0 && stages.count == 2 else { continue }
                stages[outgoing].take(action, as: .outgoing)
                stages[1 - outgoing].take(action, as: .incoming)
            }
        }
        guard any else { return }
        for i in 0..<stages.count { stages[i].endOfActions() }
        if outgoing >= 0 && stages[outgoing].cutAtStart { decks[outgoing].keepUntilStart() }
    }

    // MARK: Main thread

    /// The bus frame of the next hardware frame.
    public var nowBus: Int64 { busNow.load(ordering: .acquiring) }

    public var outputDelayMs: Double {
        get { Double(bitPattern: outputDelayBits.load(ordering: .relaxed)) }
        set { outputDelayBits.store(newValue.bitPattern, ordering: .relaxed) }
    }

    public var mainLagMs: Double {
        get { Double(bitPattern: mainLagBits.load(ordering: .relaxed)) }
        set { mainLagBits.store(newValue.bitPattern, ordering: .relaxed) }
    }

    /// The lead a start must be decided by, in bus frames, from main's estimate of the latency.
    public var proposalLeadFrames: Int64 {
        phoneBlendTiming(latencyFrames: latencyFrames.load(ordering: .relaxed), cycleFrames: 1_024, tickerMs: blendTickerMs,
                         mainLagMs: mainLagMs, ringLeadMs: 0, sinkBufferMs: 0).proposalLeadFrames
    }

    /// The same lead in ms.
    public var proposalLeadMs: Double { Double(proposalLeadFrames) * 1_000 / blendBusRate }

    /// Sends a message to the render thread; false when the ring is full.
    @discardableResult
    public func send(_ input: BlendMainInput) -> Bool { inputs.push(input) }

    /// Main's estimate of the larger time-pitch latency, read again from what each deck was told
    /// (a pace set on either deck changes it).
    public func noteLatency() {
        var largest = 0.0
        for i in 0..<decks.count { largest = max(largest, decks[i].orders.withLock { $0.latencyFrames }) }
        latencyFrames.store(Int64(largest.rounded(.up)), ordering: .relaxed)
    }
}

/// A deck's level after its time-pitch stage: the session's leave and fade ramps on bus frames,
/// at the bus frame of each frame the unit passes (the cycle's first plus what it passed this
/// cycle). Up to `slots` channels, held in a fixed table so a cycle allocates nothing.
public final class DeckLevel: @unchecked Sendable {
    public static let slots = 8
    public unowned(unsafe) let deck: DeckRender
    private var cycle = -1
    private var offset: Int64 = 0
    private let channels: UnsafeMutablePointer<UnsafeMutablePointer<Float>>

    public init(_ deck: DeckRender) {
        self.deck = deck
        channels = .allocate(capacity: Self.slots)
        channels.initialize(repeating: UnsafeMutablePointer<Float>(bitPattern: 16)!, count: Self.slots)
    }

    deinit { channels.deallocate() }

    /// Applies the level to `frames` frames of `count` channel buffers (`channel(i)` gives each).
    public func process(frames: Int, count: Int, channel: (Int) -> UnsafeMutablePointer<Float>?) {
        guard let mix = deck.mix else { return }
        if mix.cycle != cycle {
            cycle = mix.cycle
            offset = 0
        }
        let firstBus = mix.cycleBus + offset
        offset += Int64(frames)
        var used = 0
        for i in 0..<min(count, Self.slots) {
            if let data = channel(i) {
                channels[used] = data
                used += 1
            }
        }
        guard used > 0 else { return }
        deck.stage.gain.process(channels, count: used, frames: frames, firstBus: firstBus)
        mix.observer?.deck(deck.index, channels, count: used, frames: frames, firstBus: firstBus)
    }
}

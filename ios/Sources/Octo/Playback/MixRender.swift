import AVFoundation
import OctoKit
import Synchronization

/// The blend's rules where the audio is decided: the render thread owns the `BlendSession`, both
/// decks' blend stages and the limiter on the sum. Each hardware cycle the mix limiter's unit runs
/// it twice: before the decks render (main's messages taken, so they apply between session
/// steps) and after (the session advanced to the lower of the two decks' rendered bus frames, its
/// actions placed on each deck's frames, its events handed to main, the sum limited, the bus
/// clock moved on). Main talks to it only through the two rings, the commit word and a few
/// published numbers; nothing here allocates or locks once running.
final class MixRender: @unchecked Sendable {
    let word: CommitWord
    let session: BlendSession
    let stages: [DeckBlendStage]
    /// Main's messages in, the session's events out.
    let inputs = BlendRing<BlendMainInput>(capacity: 64, filler: BlendMainInput(kind: .restarted))
    let events = BlendRing<BlendEvent>(capacity: blendEventRingSlots, filler: blendNoEvent)

    /// What main publishes: the larger time-pitch latency in bus frames, main's lag and the output
    /// delay in ms, and a count bumped before every engine start that reset the stages.
    let latencyFrames = Atomic<Int64>(0)
    let mainLagBits = Atomic<UInt64>(Double(LagWindow.floorMs).bitPattern)
    let outputDelayBits = Atomic<UInt64>(Double(0).bitPattern)
    let restarts = Atomic<Int>(0)
    /// What the render publishes: the bus frame of the next hardware frame, and the hardware rate.
    let busNow = Atomic<Int64>(0)
    let hardwareRateBits = Atomic<UInt64>(Double(48_000).bitPattern)

    // MARK: Render thread

    private(set) var renders: [DeckRender] = []
    private var bus = BusClock()
    private var limiter: MixLimiter?
    /// The sum's channel buffers of the cycle, made at `prepare` so a cycle allocates nothing.
    private var sumChannels: [UnsafeMutablePointer<Float>] = []
    private var hardwareRate = 48_000.0
    /// The cycle being rendered: its first bus frame, its length in bus frames, and its number.
    private(set) var cycleBus: Int64 = 0
    private(set) var cycleBusFrames: Int64 = 1_024
    private(set) var cycle = 0
    private(set) var timing: BlendTiming
    private var seenRestarts = 0
    /// The decks of the generation registered last: which plays out (-1 before any).
    private var outgoing = -1
    private var registered = false
    #if DEBUG
    let tap = MixTap()
    #endif

    init(decks: [DeckRender]) {
        let word = CommitWord()
        let timing = phoneBlendTiming(latencyFrames: 0, cycleFrames: 1_024, tickerMs: blendTickerMs, mainLagMs: LagWindow.floorMs,
                                      ringLeadMs: 50, sinkBufferMs: 50)
        self.word = word
        self.timing = timing
        session = BlendSession(word: word, timing: timing)
        renders = decks
        stages = decks.map(\.stage)
        if stages.count == 2 {
            stages[0].partner = stages[1]
            stages[1].partner = stages[0]
        }
        for (i, deck) in decks.enumerated() {
            deck.mix = self
            deck.index = i
        }
    }

    /// Main thread, while the engine does not render: the sum's format.
    func prepare(sampleRate: Double, channels: Int) {
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
    var limiterLatencyFrames: Int { limiter?.latencyFrames ?? 0 }

    /// Before the decks render: a cycle of `frames` hardware frames begins.
    func willPull(frames: Int) {
        cycle &+= 1
        cycleBus = bus.bus
        cycleBusFrames = Int64((Double(frames) * BusClock.perHardwareFrame(hardwareRate)).rounded(.up))
        let restartsNow = restarts.load(ordering: .acquiring)
        if restartsNow != seenRestarts {
            seenRestarts = restartsNow
            for i in 0..<renders.count { renders[i].restarted(atBus: cycleBus) }
        }
        timing = phoneBlendTiming(latencyFrames: latencyFrames.load(ordering: .relaxed), cycleFrames: cycleBusFrames,
                                  tickerMs: blendTickerMs, mainLagMs: Double(bitPattern: mainLagBits.load(ordering: .relaxed)),
                                  ringLeadMs: Double(latencyFrames.load(ordering: .relaxed) + cycleBusFrames) * 1_000 / blendBusRate,
                                  sinkBufferMs: Double(bitPattern: outputDelayBits.load(ordering: .relaxed)))
        session.timing = timing
        while let input = inputs.pop() { take(input) }
        placeActions()
    }

    /// After the decks rendered: the session steps, then the sum is limited.
    func process(_ buffers: UnsafeMutableAudioBufferListPointer, frames: Int) {
        // Index loops on this thread: an array's own iterator allocates in an unoptimized build.
        var to = Int64.max
        for i in 0..<renders.count { to = min(to, renders[i].renderedBus(cycle: cycle, cycleBus: cycleBus, cycleFrames: cycleBusFrames)) }
        if to == Int64.max { to = cycleBus }
        session.advance(to: to, head: cycleBus, outgoingMs: outgoingMs(at: to))
        placeActions()
        while events.room > 0, let event = session.takeEvent(head: cycleBus) {
            events.push(event)
            #if DEBUG
            tap.note(event: event)
            #endif
        }
        if registered && session.state == .idle {
            registered = false
            for i in 0..<stages.count { stages[i].retire() }
        }
        if let limiter, sumChannels.count == limiter.channels, buffers.count >= limiter.channels {
            for i in 0..<limiter.channels {
                if let data = buffers[i].mData { sumChannels[i] = data.assumingMemoryBound(to: Float.self) }
            }
            limiter.process(sumChannels, frames: frames, firstBus: bus.exact, busPerFrame: BusClock.perHardwareFrame(hardwareRate))
        }
        #if DEBUG
        tap.cycle(bus: cycleBus, limiterLevel: limiter?.level ?? 1, limiting: limiter?.enabled ?? false)
        #endif
        bus.passed(frames, hardwareRate: hardwareRate)
        busNow.store(bus.bus, ordering: .releasing)
    }

    /// The outgoing song's time of its frame heard at bus frame `to`, while a blend is registered.
    private func outgoingMs(at to: Int64) -> Double {
        guard registered, outgoing >= 0 else { return .nan }
        let deck = renders[outgoing]
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
            stages[outgoing].armOutgoing(session, startMs: input.arm.startMs, overlapMs: input.arm.overlapMs)
            stages[into].armIncoming(session, entryMs: input.entryMs)
            renders[outgoing].registered()
            renders[into].registered()
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
        #if DEBUG
        tap.note(input: input, bus: cycleBus)
        #endif
    }

    /// Takes the session's actions and places each on the decks it concerns: the limiter's window
    /// on the sum, the rest on the outgoing and the incoming deck's stages.
    private func placeActions() {
        var any = false
        while let action = session.takeAction() {
            any = true
            #if DEBUG
            tap.note(action: action, bus: cycleBus)
            #endif
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
        if outgoing >= 0 && stages[outgoing].cutAtStart { renders[outgoing].keepUntilStart() }
    }

    // MARK: Main thread

    /// Sends a message to the render thread; false when the ring is full (never in use: main sends
    /// a handful per blend and the render takes them every few ms).
    @discardableResult
    func send(_ input: BlendMainInput) -> Bool {
        let sent = inputs.push(input)
        if !sent { playbackLog.info("mix: a message to the render thread was lost") }
        return sent
    }

    /// The bus frame of the next hardware frame.
    var nowBus: Int64 { busNow.load(ordering: .acquiring) }

    var outputDelayMs: Double {
        get { Double(bitPattern: outputDelayBits.load(ordering: .relaxed)) }
        set { outputDelayBits.store(newValue.bitPattern, ordering: .relaxed) }
    }

    var mainLagMs: Double {
        get { Double(bitPattern: mainLagBits.load(ordering: .relaxed)) }
        set { mainLagBits.store(newValue.bitPattern, ordering: .relaxed) }
    }

    /// The lead a start must be decided by, in ms, as the session last ran.
    var proposalLeadMs: Double {
        let lead = phoneBlendTiming(latencyFrames: latencyFrames.load(ordering: .relaxed), cycleFrames: 1_024, tickerMs: blendTickerMs,
                                    mainLagMs: mainLagMs, ringLeadMs: 0, sinkBufferMs: 0).proposalLeadFrames
        return Double(lead) * 1_000 / blendBusRate
    }
}

/// The mix limiter's render side: the session's cycle around the pull, then the limiter.
final class MixLimiterRender: EffectRenderer, @unchecked Sendable {
    let mix: MixRender

    init(_ mix: MixRender) { self.mix = mix }

    func prepare(sampleRate: Double, channels: Int) { mix.prepare(sampleRate: sampleRate, channels: channels) }
    func willPull(frames: Int) { mix.willPull(frames: frames) }
    func process(_ buffers: UnsafeMutableAudioBufferListPointer, frames: Int) { mix.process(buffers, frames: frames) }
}

/// A deck's level after its time-pitch stage: the session's leave and fade ramps on bus frames.
final class DeckLevelRender: EffectRenderer, @unchecked Sendable {
    let deck: DeckRender
    private var cycle = -1
    private var offset: Int64 = 0
    private var channels: [UnsafeMutablePointer<Float>] = []

    init(_ deck: DeckRender) { self.deck = deck }

    func prepare(sampleRate: Double, channels: Int) {
        self.channels = [UnsafeMutablePointer<Float>](repeating: UnsafeMutablePointer<Float>(bitPattern: 1)!, count: max(1, channels))
    }

    func willPull(frames: Int) {}

    func process(_ buffers: UnsafeMutableAudioBufferListPointer, frames: Int) {
        guard let mix = deck.mix else { return }
        if mix.cycle != cycle {
            cycle = mix.cycle
            offset = 0
        }
        let firstBus = mix.cycleBus + offset
        offset += Int64(frames)
        let count = min(channels.count, buffers.count)
        guard count > 0 else { return }
        for i in 0..<count {
            if let data = buffers[i].mData { channels[i] = data.assumingMemoryBound(to: Float.self) }
        }
        deck.stage.gain.process(count == channels.count ? channels : Array(channels.prefix(count)), frames: frames, firstBus: firstBus)
        #if DEBUG
        mix.tap.deck(deck.index, channels, frames: frames, firstBus: firstBus)
        #endif
    }
}

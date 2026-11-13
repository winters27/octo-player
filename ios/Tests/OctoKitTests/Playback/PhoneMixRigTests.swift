import AllocCount
import Foundation
import Testing
@testable import OctoKit

/// A song as the rig's decoder fills it: each sample says its song frame, `(frame + 1) / 2^21`
/// on both channels, so what is heard tells which frame of the song it is.
final class RigSong: DeckFeed, @unchecked Sendable {
    static let scale: Float = 2_097_152
    let ring = PCMRing(seconds: 4, sampleRate: blendBusRate)
    let serial: Int
    let entryId: String?
    let isLocal = true
    private(set) var isDecodedToEnd = false
    let hasFailed = false
    let jumpedToMs: Int
    private var next: Int64
    private let endFrame: Int64

    init(serial: Int, entryId: String, fromFrame: Int64, endFrame: Int64 = 1_200_000) {
        self.serial = serial
        self.entryId = entryId
        next = fromFrame
        self.endFrame = endFrame
        jumpedToMs = Int(Double(fromFrame) * 1_000 / blendBusRate)
    }

    /// Tops the ring up, as the decode thread would.
    func fill() {
        let gen = ring.generation.load(ordering: .acquiring)
        while !isDecodedToEnd, let chunk = ring.freeChunk() {
            let n = Int(min(Int64(PCMRing.chunkFrames), endFrame - next))
            if n <= 0 {
                ring.publish(frames: 0, songFrame: next, end: true, gen: gen)
                isDecodedToEnd = true
                return
            }
            for i in 0..<n {
                let v = Self.value(next + Int64(i))
                chunk[0][i] = v
                chunk[1][i] = v
            }
            ring.publish(frames: n, songFrame: next, end: false, gen: gen)
            next += Int64(n)
        }
    }

    static func value(_ frame: Int64) -> Float { Float(frame + 1) / scale }

    /// The song frame a heard sample is, when it came through untouched but for `volume`.
    static func frame(_ sample: Float, volume: Float = 1) -> Int64? {
        let x = Double(sample / volume) * Double(scale)
        guard x >= 1, x == x.rounded() else { return nil }
        return Int64(x) - 1
    }
}

/// A deck's time-pitch stage and level unit as the engine runs them. Source frame k comes out at
/// bus frame `anchorBus + latency + (k - anchorSource) / rate`, and the stage pulls the deck's
/// render a latency ahead of what it puts out, in chunks of its own choosing, a random way past
/// the cycle; an engine restart loses all it holds. What comes out is laid on a timeline by bus
/// frame, before the deck's level (`raw`) and after it.
final class RigStage {
    let deck: DeckRender
    let level: DeckLevel
    private(set) var latency: Double
    private(set) var rate: Double
    private var anchorBus = 0.0
    private var anchorSource: Int64 = 0
    private var seed: UInt64
    /// What the deck handed out, by source frame.
    private(set) var pulled: [Float] = []
    private(set) var raw: [Float] = []
    private(set) var heard: [Float] = []
    /// The first bus frame where the deck was heard, kept as the timeline grows.
    private(set) var firstHeard: Int?
    private let left: UnsafeMutablePointer<Float>
    private let right: UnsafeMutablePointer<Float>
    /// Allocator calls counted inside the deck's render and inside its level, while counting.
    var counting = false
    private(set) var renderAllocations = 0
    private(set) var levelAllocations = 0
    var allocations: Int { renderAllocations + levelAllocations }
    /// Main holds the deck's orders, from another thread, through the deck's renders.
    var ordersBusy = false

    init(_ deck: DeckRender, latency: Double, rate: Double, seed: UInt64) {
        self.deck = deck
        level = DeckLevel(deck)
        self.latency = latency
        self.rate = rate
        self.seed = seed
        left = .allocate(capacity: 8_192)
        right = .allocate(capacity: 8_192)
        deck.orders.withLock {
            $0.latencyFrames = latency
            $0.speed = Float(rate)
        }
    }

    deinit {
        left.deallocate()
        right.deallocate()
    }

    private func random() -> UInt64 {
        seed = seed &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
        return seed >> 33
    }

    /// A new rate and latency from the next source frame on (a pace set); the deck is told the same.
    func set(rate: Double, latency: Double) {
        let k = Int64(pulled.count)
        anchorBus += Double(k - anchorSource) / self.rate
        anchorSource = k
        self.rate = rate
        self.latency = latency
        deck.orders.withLock {
            $0.latencyFrames = latency
            $0.speed = Float(rate)
        }
    }

    /// An engine restart at bus frame `bus`: what the stage held is lost, and the next source frame
    /// comes out a latency after it.
    func reset(atBus bus: Int64) {
        anchorBus = Double(bus)
        anchorSource = Int64(pulled.count)
    }

    /// Pulls the deck for the cycle ending at bus frame `end`: everything heard up to a latency
    /// and a random lead past it.
    func pull(through end: Double) {
        let lead = Double(random() % 600)
        let need = anchorSource + Int64(((end + lead - anchorBus) * rate).rounded(.up))
        while Int64(pulled.count) < need {
            let n = min(Int(need - Int64(pulled.count)), 1 + Int(random() % 700))
            render(n)
            for i in 0..<n { pulled.append(left[i]) }
        }
    }

    private func render(_ n: Int) {
        if ordersBusy {
            let deck = self.deck
            let taken = DispatchSemaphore(value: 0)
            let done = DispatchSemaphore(value: 0)
            let finished = DispatchSemaphore(value: 0)
            Thread {
                deck.orders.withLock { _ in
                    taken.signal()
                    done.wait()
                }
                finished.signal()
            }.start()
            taken.wait()
            deck.render(n, left, right)
            done.signal()
            finished.wait()
            return
        }
        if counting { octo_alloc_arm() }
        deck.render(n, left, right)
        if counting { renderAllocations += octo_alloc_disarm() }
    }

    /// What the stage puts out over the cycle from bus frame `first`, through the deck's level.
    func emit(first: Int64, frames: Int) {
        for i in 0..<frames {
            let k = anchorSource + Int64(((Double(first + Int64(i)) - anchorBus - latency) * rate).rounded())
            let v: Float = k >= anchorSource && k < Int64(pulled.count) ? pulled[Int(k)] : 0
            left[i] = v
            right[i] = v
        }
        let at = Int(first)
        if raw.count < at + frames {
            raw.append(contentsOf: repeatElement(0, count: at + frames - raw.count))
            heard.append(contentsOf: repeatElement(0, count: at + frames - heard.count))
        }
        for i in 0..<frames { raw[at + i] = left[i] }
        if firstHeard == nil, let i = (0..<frames).first(where: { left[$0] != 0 }) { firstHeard = at + i }
        if counting { octo_alloc_arm() }
        level.process(frames: frames, count: 2) { ch in ch == 0 ? self.left : ch == 1 ? self.right : nil }
        if counting { levelAllocations += octo_alloc_disarm() }
        for i in 0..<frames { heard[at + i] = left[i] }
    }

    // MARK: Reading the timeline

    /// The first bus frame from `from` on where the deck is heard, before its level.
    func firstSound(from: Int = 0) -> Int? { (from..<raw.count).first { raw[$0] != 0 } }

    /// The last bus frame before `before` where the deck is heard, before its level.
    func lastSound(before: Int? = nil) -> Int? { (0..<min(before ?? raw.count, raw.count)).last { raw[$0] != 0 } }

    func silent(from: Int, to: Int) -> Bool { (from..<min(to, raw.count)).allSatisfy { raw[$0] == 0 } }

    /// The song frame heard at bus frame `bus`, where it came through untouched.
    func frame(at bus: Int, volume: Float = 1) -> Int64? { bus >= 0 && bus < raw.count ? RigSong.frame(raw[bus], volume: volume) : nil }

    /// The first bus frame from `from` on where song frame `f` is heard.
    func bus(of f: Int64, from: Int = 0, volume: Float = 1) -> Int? { (from..<raw.count).first { frame(at: $0, volume: volume) == f } }

    /// The highest song frame heard untouched in the bus frames `range`.
    func highestFrame(_ range: Range<Int>, volume: Float = 1) -> Int64? {
        range.clamped(to: 0..<raw.count).compactMap { frame(at: $0, volume: volume) }.max()
    }
}

/// The phone's mix as the engine runs it, with main's side as the crossfader drives it: deck A
/// plays out, deck B comes in. Each cycle the mix steps before the pull, both decks are pulled
/// through their stages (B first when `bFirst`), and the mix steps after with their sum; main's
/// ticker runs every other cycle (about 43 ms).
final class MixRig {
    static let frames = 1_024
    let a: DeckRender
    let b: DeckRender
    let mix: PhoneMix
    let stageA: RigStage
    let stageB: RigStage
    let songA: RigSong
    let songB: RigSong
    var bFirst = false
    /// Main acknowledges a proposal at its next tick.
    var acks = true
    private(set) var events: [BlendEvent] = []
    private(set) var handedOver = false
    private(set) var generation: Int64 = 0
    private var cycles = 0
    private var restartPending = false
    private let sum: [UnsafeMutablePointer<Float>]
    private let table: UnsafeMutablePointer<UnsafeMutablePointer<Float>>
    var counting = false {
        didSet {
            stageA.counting = counting
            stageB.counting = counting
        }
    }
    private var pullAllocations = 0
    private var processAllocations = 0
    var allocations: Int { pullAllocations + processAllocations + stageA.allocations + stageB.allocations }
    /// The allocator calls so far by where: the mix before the pull and after it, deck A's render
    /// and level, deck B's render and level.
    var allocationsByPart: [Int] {
        [pullAllocations, processAllocations, stageA.renderAllocations, stageA.levelAllocations, stageB.renderAllocations,
         stageB.levelAllocations]
    }

    init(a la: Double, _ ra: Double, b lb: Double, _ rb: Double, seed: UInt64 = 1, songA: RigSong? = nil) {
        a = DeckRender(processor: DeckProcessor(keepActive: { false }, tap: nil, stage: nil))
        b = DeckRender(processor: DeckProcessor(keepActive: { false }, tap: nil, stage: nil))
        mix = PhoneMix(decks: [a, b])
        mix.prepare(sampleRate: blendBusRate, channels: 2)
        stageA = RigStage(a, latency: la, rate: ra, seed: seed)
        stageB = RigStage(b, latency: lb, rate: rb, seed: seed &+ 99)
        let first = songA ?? RigSong(serial: 1, entryId: "a", fromFrame: 400_000)
        let second = RigSong(serial: 2, entryId: "b", fromFrame: 91_200)
        self.songA = first
        songB = second
        sum = (0..<2).map { _ in UnsafeMutablePointer<Float>.allocate(capacity: Self.frames) }
        table = .allocate(capacity: 2)
        table[0] = sum[0]
        table[1] = sum[1]
        a.orders.withLock {
            $0.current = first
            $0.playing = true
        }
        // The spare plays, parked, before its blend is registered.
        b.orders.withLock {
            $0.current = second
            $0.playing = true
            $0.parked = true
        }
    }

    deinit {
        for buffer in sum { buffer.deallocate() }
        table.deallocate()
    }

    /// Both decks' volume.
    func setVolume(_ volume: Float) {
        a.orders.withLock { $0.volume = volume }
        b.orders.withLock { $0.volume = volume }
    }

    /// Both processors shaped by `plan`, as the crossfader arms them.
    func shape(_ plan: TransitionPlan) {
        a.processor.arm(DeckTransition(entryId: "a", plan: plan, incoming: false))
        b.processor.arm(DeckTransition(entryId: "b", plan: plan, incoming: true))
    }

    /// Main registers a blend of A into B: A's start at `startMs`, B entering at `entryMs`.
    func arm(startMs: Double = 10_000, overlapMs: Double = 8_000, entryMs: Double = 2_000) {
        generation += 1
        mix.word.open(generation)
        let arm = BlendArm(generation: generation, startMs: startMs, overlapMs: overlapMs, latestEndMs: startMs + overlapMs + 4_000,
                           outgoingEndMs: 25_000, lateMoveBars: lateMoveUnbounded)
        _ = mix.send(.arm(arm, outgoing: 0, entryMs: entryMs))
        handedOver = false
    }

    /// The crossfader's pause after the handover: the old deck paused, the disarm sent, then the
    /// player's deck paused.
    func pauseAfterHandover() {
        a.orders.withLock { $0.playing = false }
        _ = mix.send(.disarm(.pause, generation: generation))
        b.orders.withLock { $0.playing = false }
    }

    /// Playing again: the old deck's kept frames first, then the player's deck.
    func resume() {
        a.orders.withLock { $0.playing = true }
        b.orders.withLock { $0.playing = true }
    }

    /// The engine stops and starts again (a new output): both stages are reset.
    func restart() {
        mix.restarts.add(1, ordering: .releasing)
        restartPending = true
    }

    /// The first bus frame of the next cycle.
    var nextBus: Int { Int(mix.nowBus) }

    func cycle() {
        songA.fill()
        songB.fill()
        if counting { octo_alloc_arm() }
        mix.willPull(frames: Self.frames)
        if counting { pullAllocations += octo_alloc_disarm() }
        let first = mix.cycleBus
        if restartPending {
            restartPending = false
            stageA.reset(atBus: first)
            stageB.reset(atBus: first)
        }
        let end = Double(first) + Double(Self.frames)
        for stage in bFirst ? [stageB, stageA] : [stageA, stageB] {
            stage.pull(through: end)
            stage.emit(first: first, frames: Self.frames)
        }
        for i in 0..<Self.frames {
            let v = stageA.heard[Int(first) + i] + stageB.heard[Int(first) + i]
            sum[0][i] = v
            sum[1][i] = v
        }
        if counting { octo_alloc_arm() }
        mix.process(table, channels: 2, frames: Self.frames)
        if counting { processAllocations += octo_alloc_disarm() }
        cycles += 1
        if cycles % 2 == 0 { tick() }
    }

    func run(_ n: Int) { for _ in 0..<n { cycle() } }

    func run(until done: () -> Bool, max: Int = 1_200) {
        var n = 0
        while !done() && n < max {
            cycle()
            n += 1
        }
    }

    /// The blend's end, drained and not yet due.
    private var endWaiting: BlendEvent?

    /// Main's ticker, as the crossfader's: every event drained; a proposal acknowledged (when
    /// `acks`) and handed over, so main no longer parks B; once the blend's end is due (its bus
    /// frame reached; the rig's output adds no delay) the old deck stops.
    func tick() {
        while let event = mix.events.pop() {
            events.append(event)
            switch event.kind {
            case .proposed:
                guard acks, !handedOver else { break }
                if mix.word.ack(event.generation) || mix.word.state(of: event.generation) == .acked {
                    handedOver = true
                    b.orders.withLock { $0.parked = false }
                }
            case .blendEnd:
                endWaiting = event
            default:
                break
            }
        }
        if let end = endWaiting, blendEventDue(end, nowBus: mix.nowBus, outputDelayFrames: 0, quietStart: false) {
            endWaiting = nil
            a.orders.withLock {
                $0.current = nil
                $0.playing = false
                $0.parked = true
            }
            _ = mix.send(.outgoingStopped(generation: end.generation))
        }
    }

    var cancelled: Bool { events.contains { $0.kind == .cancelled || $0.kind == .incomingNotReady } }
}

func rigPlan(kind: TransitionKind = .blend, startMs: Int = 10_000, entryMs: Int = 2_000, overlapMs: Int = 8_000,
             holdShare: Double = 0) -> TransitionPlan {
    TransitionPlan(startMs: startMs, entryMs: entryMs, overlapMs: overlapMs, kind: kind, k: 0.5, filterStrength: 0,
                   beatMatchRate: nil, beatMs: nil, beatAnchorMs: nil, late: false, headroomDb: 1, reason: "test", curve: nil,
                   holdShare: holdShare, riseShare: 0, latestEndMs: startMs + overlapMs + 4_000, lateMoveBars: lateMoveUnbounded)
}

/// The phone's mix driven end to end: the session, both decks' renders and stages, the level
/// units and the limiter, through time-pitch stages with unequal delays and rates.
@Suite struct PhoneMixRigTests {
    /// Both decks' time-pitch latency (bus frames) and rate: unequal, never both zero.
    static let setups: [(la: Double, ra: Double, lb: Double, rb: Double)] = [(2_400, 1, 4_320, 0.94), (4_320, 0.94, 2_400, 1)]

    @Test func theIncomingSongStartsOnItsFrameThroughALatencyMainHasNotPublished() throws {
        // A plays through a bypassed stage; B is tempo-matched, its stage 90 ms late, and main's
        // own estimate of the larger latency still says 0.
        let rig = MixRig(a: 0, 1, b: 4_320, 0.94)
        rig.mix.latencyFrames.store(0, ordering: .relaxed)
        rig.arm()
        rig.run(until: { rig.stageB.firstHeard.map { $0 + 4_800 < rig.nextBus } ?? false })
        #expect(rig.handedOver)
        #expect(!rig.cancelled)
        let bStart = try #require(rig.stageB.firstSound())
        let s = try #require(rig.stageA.bus(of: 480_000))
        #expect(abs(bStart - s) <= 1, "B first heard at \(bStart), A's start at \(s)")
        let entry = try #require(rig.stageB.frame(at: bStart))
        #expect(abs(entry - 96_000) <= 1)
    }

    @Test(arguments: [false, true])
    func aPauseAfterTheHandoverPlaysTheOutgoingSongToItsStartThenTheIncomingSongAlone(bFirst: Bool) throws {
        for (n, s) in Self.setups.enumerated() {
            let rig = MixRig(a: s.la, s.ra, b: s.lb, s.rb, seed: UInt64(n + 1))
            rig.bFirst = bFirst
            rig.arm()
            rig.run(until: { rig.handedOver })
            try #require(rig.handedOver)
            #expect(rig.stageB.firstHeard == nil)
            let songMs = rig.a.processor.songMs
            let pausedAt = rig.nextBus
            rig.pauseAfterHandover()
            rig.cycle()
            // The frames to the start are kept as they are: none is shaped in the pause's cycle.
            #expect(rig.a.processor.songMs - songMs < 50, "setup \(n): \(rig.a.processor.songMs - songMs) ms shaped at the pause")
            rig.run(48)
            let resumedAt = rig.nextBus
            rig.resume()
            rig.run(160)
            // Silent through the pause, once each stage has let out what it held.
            #expect(rig.stageA.silent(from: pausedAt + Int(s.la) + 4_000, to: resumedAt), "setup \(n)")
            #expect(rig.stageB.silent(from: 0, to: resumedAt), "setup \(n)")
            // A plays its kept frames, up to its start and never past it; B comes in where they
            // end, alone.
            let bStart = try #require(rig.stageB.firstSound(), "setup \(n)")
            let aEnd = try #require(rig.stageA.lastSound(), "setup \(n)")
            #expect(aEnd > resumedAt, "setup \(n)")
            #expect(abs(bStart - (aEnd + 1)) <= 3, "setup \(n): A ends at \(aEnd), B starts at \(bStart)")
            let highest = try #require(rig.stageA.highestFrame(resumedAt..<aEnd + 1), "setup \(n)")
            #expect(highest < 480_000 && highest > 480_000 - 1_200, "setup \(n): A's last frame \(highest)")
            // B from its entry, at its rate, past its ramp in.
            let probe = bStart + 1_500
            let f = try #require(rig.stageB.frame(at: probe), "setup \(n)")
            #expect(abs(Double(f) - (96_000 + 1_500 * s.rb)) <= 3, "setup \(n): B at \(f)")
            #expect(!rig.cancelled, "setup \(n)")
        }
    }

    @Test(arguments: [false, true])
    func aPauseOnceTheIncomingSongHasStartedLeavesItAlone(bFirst: Bool) throws {
        let rig = MixRig(a: 2_400, 1, b: 4_320, 0.94)
        rig.bFirst = bFirst
        rig.arm()
        rig.run(until: { rig.stageB.firstHeard.map { $0 + 9_600 < rig.nextBus } ?? false })
        let pausedAt = rig.nextBus
        rig.pauseAfterHandover()
        rig.run(48)
        let resumedAt = rig.nextBus
        // The last of B heard before the resume: what its stage held came out after the pause.
        let before = try #require(rig.stageB.highestFrame(pausedAt..<resumedAt))
        rig.resume()
        rig.run(100)
        // Nothing of A once playing again; B carries on from where it paused (past its 10 ms fade
        // and its ramp back in).
        #expect(rig.stageA.silent(from: resumedAt, to: rig.nextBus))
        let back = try #require(rig.stageB.firstSound(from: resumedAt))
        #expect(back < resumedAt + 4_320 + 2_400)
        let after = try #require(rig.stageB.frame(at: back + 1_500))
        #expect(after > before && after - before < 2_600, "B before \(before), after \(after)")
    }

    /// The spec's restart row: delays 0 and 4320, and 2400 and 4320, the restart a few thousand
    /// frames before the blend's end, both decks at half volume.
    @Test func aRestartMidBlendKeepsBothSongsLinedUpAndTheBlendsEndOnItsFrame() throws {
        for (n, s) in [(0.0, 1.0, 4_320.0, 0.94), (2_400, 1, 4_320, 0.94)].enumerated() {
            let rig = MixRig(a: s.0, s.1, b: s.2, s.3, seed: UInt64(20 + n))
            rig.setVolume(0.5)
            rig.arm(overlapMs: 4_000)
            rig.run(until: { rig.stageB.firstHeard != nil })
            let bStart = try #require(rig.stageB.firstSound())
            // The blend ends 4 s on; the restart comes 8000 frames before.
            rig.run(until: { rig.nextBus >= bStart + 192_000 - 8_000 })
            let probeBefore = rig.nextBus - 3_000
            let a1 = try #require(rig.stageA.frame(at: probeBefore, volume: 0.5), "setup \(n)")
            let b1 = try #require(rig.stageB.frame(at: probeBefore, volume: 0.5), "setup \(n)")
            rig.restart()
            let restartAt = rig.nextBus
            rig.run(40)
            // Both songs moved by the same stretch: B's frame against A's is where it was.
            let probeAfter = restartAt + Int(max(s.0, s.2)) + 1_500
            let a2 = try #require(rig.stageA.frame(at: probeAfter, volume: 0.5), "setup \(n)")
            let b2 = try #require(rig.stageB.frame(at: probeAfter, volume: 0.5), "setup \(n)")
            let expected = Double(b1) + Double(a2 - a1) * s.3 / s.1
            #expect(abs(Double(b2) - expected) <= 3, "setup \(n): B at \(b2), lined up at \(expected)")
            if s.0 > 0 {
                // The replayed frames come out once turned down, and run on from the song.
                let replayed = restartAt + Int(s.0) + 200
                let f = try #require(rig.stageA.frame(at: replayed, volume: 0.5), "setup \(n)")
                #expect(rig.stageA.frame(at: replayed + 1, volume: 0.5) == f + 1)
            }
            // A leaves on the blend's end: its last frame is 14 s into its song, its start plus the overlap.
            rig.run(until: { rig.events.contains { $0.kind == .blendEnd } })
            rig.run(20)
            let last = try #require(rig.stageA.lastSound(), "setup \(n)")
            let lastFrame = try #require(rig.stageA.frame(at: last, volume: 0.5), "setup \(n)")
            #expect(abs(lastFrame - (480_000 + 192_000 - 1)) <= 2, "setup \(n): A's last frame \(lastFrame)")
            #expect(!rig.cancelled)
        }
    }

    @Test func theRenderPublishesTheSongTimeHeardAtTheBusHead() {
        let rig = MixRig(a: 2_400, 1, b: 0, 1)
        rig.run(40)
        var checked = 0
        for _ in 0..<20 {
            rig.cycle()
            guard let f = rig.stageA.frame(at: Int(rig.mix.cycleBus)) else { continue }
            #expect(abs(rig.a.headMs - Double(f) * 1_000 / blendBusRate) < 0.05, "head \(rig.a.headMs) ms, heard frame \(f)")
            checked += 1
        }
        #expect(checked > 10)
    }

    @Test func aSpareToldToPlayJustAfterItsRegistrationIsNeverTakenForPaused() throws {
        let rig = MixRig(a: 2_400, 1, b: 4_320, 0.94)
        rig.b.orders.withLock { $0.playing = false }
        rig.arm()
        rig.cycle()
        rig.b.orders.withLock { $0.playing = true }
        rig.run(until: { rig.stageB.firstHeard != nil })
        #expect(!rig.cancelled)
        #expect(rig.stageB.firstHeard != nil)
    }

    @Test func theSongEndAnswerIsUsedOnlyOnceTheOrdersAreTaken() throws {
        // A ends 10 000 frames past its start; a follower is loaded. Main undoes the start as it
        // is proposed, so A holds at its end until main says what follows.
        let songA = RigSong(serial: 1, entryId: "a", fromFrame: 400_000, endFrame: 490_000)
        let follower = RigSong(serial: 3, entryId: "f", fromFrame: 0)
        let rig = MixRig(a: 2_400, 1, b: 4_320, 0.94, songA: songA)
        rig.acks = false
        rig.a.orders.withLock { $0.following = follower }
        rig.arm()
        rig.run(until: { rig.events.contains { $0.kind == .proposed } })
        try #require(rig.events.contains { $0.kind == .proposed })
        _ = rig.mix.word.cancel(1)
        _ = rig.mix.send(.disarm(.edit, generation: 1))
        rig.run(until: { rig.mix.stages[0].holdingForAnswer }, max: 200)
        #expect(rig.mix.stages[0].holdingForAnswer)
        // Nothing follows (the song stops at its end), told while main holds the orders.
        _ = rig.mix.send(.followUp(generation: 1, pass: blendPassNone))
        rig.stageA.ordersBusy = true
        rig.cycle()
        rig.stageA.ordersBusy = false
        rig.run(20)
        let (endings, advances, same) = rig.a.orders.withLock { ($0.endings, $0.advances, $0.current === songA) }
        #expect(endings == 1)
        #expect(advances == 0)
        #expect(same)
    }

    @Test func theIPhoneHasNoSinkBufferPastTheBus() {
        let rig = MixRig(a: 2_400, 1, b: 0, 1)
        rig.mix.outputDelayMs = 2_000
        rig.cycle()
        #expect(rig.mix.timing.sinkBufferMs == 0)
    }

    @Test func theQuietStartsWindowCoversMainsLatenessAndTheFadeAfterIt() {
        var held = rigPlan(overlapMs: 12_000, holdShare: 0.5)
        held.headroomDb = 0
        // Where the shaping first leaves a quiet start's bounds, in 10 ms steps from the start.
        let incoming = DeckTransition(entryId: nil, plan: held, incoming: true)
        let outgoing = DeckTransition(entryId: nil, plan: held, incoming: false)
        var edge = 0.0
        while edge < Double(held.overlapMs) {
            let inGain = incoming.gainAt(incoming.progressAt(Double(held.entryMs) + edge))
            let outGain = outgoing.gainAt(edge / Double(held.overlapMs))
            if (inGain > 0 && 20 * log10(inGain) >= -40) || outGain <= 0 || abs(20 * log10(outGain)) > 0.5 { break }
            edge += 10
        }
        // The longest route delay a quiet start still commits on, in 10 ms steps.
        func longest(_ lateMs: Double) -> Double {
            var d = airPlayAckLeadMs + 10
            while d < 30_000 && commitsBeforeAck(held, outputDelayMs: d + 10, lateMs: lateMs) { d += 10 }
            return d
        }
        let plain = longest(0)
        #expect(edge > 1_000)
        // The window: the delay less the ack's lead, plus main's lateness, plus the 250 ms fade.
        #expect(abs(plain - (edge - blendDisarmRampMs + airPlayAckLeadMs)) <= 20, "longest \(plain), edge \(edge)")
        #expect(abs((plain - longest(400)) - 400) <= 10)
    }

    @Test func aReplacedTransitionIsFreedOnMainNeverByTheRender() {
        let processor = DeckProcessor(keepActive: { false }, tap: nil, stage: nil)
        processor.configure(sampleRate: 48_000, channels: 2)
        processor.flush(streamStartMs: 0, entryId: "a")
        func render() {
            var l = [Float](repeating: 0.5, count: 480)
            var r = l
            l.withUnsafeMutableBufferPointer { lp in
                r.withUnsafeMutableBufferPointer { rp in processor.process([lp.baseAddress!, rp.baseAddress!], frames: 480) }
            }
        }
        func transition() -> DeckTransition { DeckTransition(entryId: "a", plan: rigPlan(startMs: 0, overlapMs: 8_000), incoming: false) }
        weak var first: DeckTransition?
        do {
            let t = transition()
            first = t
            processor.arm(t)
        }
        render()
        // Replaced three times on main while the render shapes with it: kept.
        for _ in 0..<3 { processor.arm(transition()) }
        #expect(first != nil)
        // The render takes up the newest and lets go of the first: still not freed there.
        render()
        #expect(first != nil)
        // Main's next arm frees it.
        processor.arm(nil)
        #expect(first == nil)
    }
}

@Suite struct PhoneMixRigAllocationTests {
    /// The whole render side of the phone's mix, shaping on, driven through every path the session
    /// takes: nothing allocated. The same script runs once first on other objects, as the render
    /// thread's first cycles would, so the runtime's one-time type records are made before counting.
    @Test func theWholePhoneMixAllocatesNothingOnTheRenderThread() throws {
        try #require(octo_alloc_supported() == 1, "counting needs Linux and glibc")
        _ = script(counting: false)
        let counts = script(counting: true)
        for (part, calls) in counts {
            // By where: the mix before the pull, after it, A's render, A's level, B's render, B's level.
            #expect(calls.reduce(0, +) == 0, "\(part): \(calls) calls into the allocator")
        }
    }

    /// A blend from its arm through its start, a pace change and an engine restart in it, to its
    /// end (the limiter off); an undo with its follow-up; a pause after the handover with its kept
    /// frames and the resume; and kept frames dropped.
    func script(counting: Bool) -> [(String, [Int])] {
        var counts: [(String, [Int])] = []
        func part(_ name: String, _ rig: MixRig, _ body: () -> Void) {
            let before = rig.allocationsByPart
            body()
            counts.append((name, zip(rig.allocationsByPart, before).map { $0 - $1 }))
        }
        let plan = rigPlan(startMs: 10_000, entryMs: 2_000, overlapMs: 4_000)

        let blend = MixRig(a: 2_400, 1, b: 4_320, 0.94)
        blend.counting = counting
        blend.shape(plan)
        blend.arm(overlapMs: 4_000)
        part("arm to start", blend) { blend.run(until: { blend.stageB.firstHeard != nil }) }
        part("pace", blend) {
            _ = blend.mix.send(.paceChanged(1.05))
            blend.stageA.set(rate: 1.05, latency: 2_600)
            blend.stageB.set(rate: 0.94 * 1.05, latency: 4_500)
            blend.run(10)
        }
        part("restart", blend) {
            blend.restart()
            blend.run(10)
        }
        part("blend end, limiter off", blend) {
            blend.run(until: { blend.events.contains { $0.kind == .blendEnd } }, max: 600)
            blend.run(10)
        }
        precondition(blend.events.contains { $0.kind == .blendEnd }, "the script reaches the blend's end")

        let undo = MixRig(a: 2_400, 1, b: 4_320, 0.94, seed: 3)
        undo.counting = counting
        undo.shape(plan)
        part("undo and follow-up", undo) {
            undo.arm()
            undo.run(4)
            _ = undo.mix.word.cancel(1)
            _ = undo.mix.send(.disarm(.edit, generation: 1))
            undo.run(4)
            _ = undo.mix.send(.followUp(generation: 1, pass: blendPassNone))
            undo.run(20)
        }

        let pause = MixRig(a: 4_320, 0.94, b: 2_400, 1, seed: 5)
        pause.counting = counting
        pause.shape(plan)
        pause.arm()
        part("to the handover", pause) { pause.run(until: { pause.handedOver }) }
        part("pause, kept frames, resume", pause) {
            pause.pauseAfterHandover()
            pause.run(20)
            pause.resume()
            pause.run(100)
        }
        precondition(pause.stageB.firstHeard != nil, "the script reaches the incoming song after the kept frames")

        let drop = MixRig(a: 2_400, 1, b: 4_320, 0.94, seed: 7)
        drop.counting = counting
        drop.arm()
        part("drop the kept frames", drop) {
            drop.run(until: { drop.handedOver })
            drop.pauseAfterHandover()
            drop.run(10)
            drop.a.orders.withLock { $0.dropKept += 1 }
            drop.run(30)
        }
        return counts
    }
}

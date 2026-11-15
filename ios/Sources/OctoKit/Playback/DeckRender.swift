import Foundation
import Synchronization

/// What a deck plays from: one song loaded, decoded into a ring on a thread of its own. The deck's
/// render reads it; nothing here blocks the render.
public protocol DeckFeed: AnyObject, Sendable {
    var ring: PCMRing { get }
    /// Tells this load apart from every other.
    var serial: Int { get }
    var entryId: String? { get }
    /// A file on the phone starts at once; a stream waits for the start-after setting.
    var isLocal: Bool { get }
    /// The decoder reached the song's end in the current jump.
    var isDecodedToEnd: Bool { get }
    var hasFailed: Bool { get }
    /// Where the latest jump went, in ms.
    var jumpedToMs: Int { get }
}

/// What a deck's render is told, and what it changes back: the song it plays, the one to follow
/// without a gap, whether it plays, and its volume.
public struct DeckOrders: Sendable {
    public var current: (any DeckFeed)?
    public var following: (any DeckFeed)?
    public var playing = false
    public var pauseAtEnd = false
    public var volume: Float = 1
    /// Real time of sound waiting before a stream starts, and the pace it plays at.
    public var startAfterMs = 1_000
    public var speed: Float = 1
    /// How far the time-pitch stage delays this deck's sound, in bus frames, bypassed or not.
    public var latencyFrames = 0.0
    /// A spare loaded for a blend: silent and consuming nothing until the blend starts it.
    public var parked = false
    /// Bumped by main when the outgoing song's kept frames are no longer wanted.
    public var dropKept = 0
    /// Bumped by the render each time it moves on to `following` by itself.
    public var advances = 0
    /// Bumped by the render each time a song ends with nothing to follow, or stops at its end.
    public var endings = 0

    public init() {}
}

/// Where a deck's render stands, as the main side shows it.
public extension DeckState {
    var code: Int {
        switch self {
        case .idle: 0
        case .buffering: 1
        case .ready: 2
        case .ended: 3
        }
    }

    init(code: Int) {
        self = switch code {
        case 1: .buffering
        case 2: .ready
        case 3: .ended
        default: .idle
        }
    }
}

/// The render thread's side of a deck. Only the render thread touches its fields once the deck
/// runs; the main side talks to it through `orders` (which the render only tries to take, never
/// waits on) and reads what it reports from the atomics.
///
/// In a blend it follows its stage: the session's actions land on its source frames (counted by
/// its clock, every frame handed to the time-pitch stage, silence included), a parked spare
/// consumes nothing until its start, and the outgoing song neither moves on nor ends by itself
/// once the blend has started. Nothing allocates after `init`; every loop over channels or decks
/// is an index loop (an array's own iterator allocates in an unoptimized build).
public final class DeckRender: @unchecked Sendable {
    public let orders = Mutex(DeckOrders())
    public let processor: DeckProcessor
    public let stage = DeckBlendStage()
    /// Told on the render thread as each new song's stream starts (the sound stage's loudness).
    public var onNewSong: ((any DeckFeed) -> Void)?
    /// The render-thread session's owner, and which deck this is to it. Both live as long as the
    /// engine; no reference is counted on the render thread.
    public unowned(unsafe) var mix: PhoneMix?
    public internal(set) var index = 0

    /// What the render reports: the song time of the next frame, the song time heard at the bus
    /// clock's head (the render's own position, ahead of the ear by the output delay), where it
    /// stands, and the frames it has handed out.
    public let songMsBits = Atomic<UInt64>(0)
    public let headMsBits = Atomic<UInt64>(Double.nan.bitPattern)
    public let phaseCode = Atomic<Int>(DeckState.idle.code)
    public let rendered = Atomic<Int>(0)
    /// The song and the jump the render last handed out sound of.
    public let renderedSerial = Atomic<Int>(0)
    public let renderedGeneration = Atomic<Int>(-1)

    private var held = DeckOrders()
    private unowned(unsafe) var source: (any DeckFeed)?
    private var generation = -1
    private var started = false
    private var rebuffering = false
    private var newSong = true
    private var needsFlush = true
    private var songDone = false
    private var ended = false
    private var expectedFrame: Int64 = -1
    private var gain: Float = 0
    private var configured = false

    /// The song time of the next frame handed out (nan with no song), the cycle it last rendered
    /// in, and whether it plays now and at what volume.
    public private(set) var songMsNext = Double.nan
    private var renderedCycle = -1
    public private(set) var playingNow = false
    public private(set) var volumeNow: Float = 1
    /// Out of sound and waiting to rejoin; chunks delivered by the last look; what the session was
    /// told of a pause and of the song's end; whether it was seen playing since it was registered.
    private var dryNow = false
    private var chunksSeen = 0
    private var pausedTold = false
    private var endTold = false
    private var seenPlaying = false
    private var wasHeld = false
    /// The engine restarted: the outgoing song's start is placed again.
    private var replaceStart = false

    public static let block = 4_096
    private let scratch: [UnsafeMutablePointer<Float>]
    private let tail: [UnsafeMutablePointer<Float>]
    private var tailCount = 0
    private var tailRead = 0
    private var outAt: [UnsafeMutablePointer<Float>]
    private var outs: [UnsafeMutablePointer<Float>]

    /// The outgoing song's frames up to its blend's start, kept raw through a pause past the
    /// handover, shaped as they play on resume (the processor's own held frames first, then
    /// these, then its tail), the last 20 ms fading, before the incoming song comes in.
    public static let keptCapacity = 96_000
    private let kept: [UnsafeMutablePointer<Float>]
    /// Kept frames wait or play: until they are dropped or the deck takes another song.
    private var keptActive = false
    private var keptRaw = 0
    private var keptRead = 0
    /// Frames the kept stretch hands out in all, and how many so far; whether the processor's
    /// tail was taken; whether the incoming song was told where they end.
    private var keptTotal = 0
    private var keptOut = 0
    private var keptDrained = false
    private var cutPublished = false
    private var keptDropped = 0
    /// Whether this deck played as the orders were last taken, to see a pause inside the kept
    /// frames.
    private var wasPlaying = false
    /// Main's view: the outgoing song still has kept frames to play (the blend's end waits).
    public let keptPending = Atomic<Bool>(false)
    /// The song the registered blend was armed for on this deck (-1 for none): taking it never
    /// lets go of the registration.
    private var registeredSerial: Int64 = -1

    /// The last frames handed to the time-pitch stage, before the deck's volume, replayed after an
    /// engine restart reset the stage.
    public static let historyCapacity = 16_384
    private let history: [UnsafeMutablePointer<Float>]
    private var historyAt = 0
    private var historyFilled = 0
    private var replayLeft = 0
    private var replayAt = 0

    /// A rejoin waits for a render cycle plus a quarter of this much (500 ms) in the ring.
    private static let rejoinBufferFrames = 24_000

    public init(processor: DeckProcessor) {
        self.processor = processor
        let made: [UnsafeMutablePointer<Float>] = (0..<2).map { _ in .allocate(capacity: Self.block) }
        scratch = made
        tail = (0..<2).map { _ in .allocate(capacity: Self.block * 2) }
        kept = (0..<2).map { _ in .allocate(capacity: Self.keptCapacity) }
        history = (0..<2).map { _ in .allocate(capacity: Self.historyCapacity) }
        for ch in 0..<2 { history[ch].initialize(repeating: 0, count: Self.historyCapacity) }
        // Each its own storage: a table shared with another is copied at its first write, which
        // would be on the render thread.
        outAt = made.map { $0 }
        outs = made.map { $0 }
        // Set up here, on main: the processor's shaper is made as it is configured.
        processor.configure(sampleRate: Int(blendBusRate), channels: 2)
        configured = true
    }

    deinit {
        for ch in 0..<2 {
            scratch[ch].deallocate()
            tail[ch].deallocate()
            kept[ch].deallocate()
            history[ch].deallocate()
        }
    }

    /// The song time heard at the bus clock's head, nan with no song.
    public var headMs: Double { Double(bitPattern: headMsBits.load(ordering: .relaxed)) }

    // MARK: The render thread

    /// Fills `frames` frames of `left` and `right`.
    public func render(_ frames: Int, _ left: UnsafeMutablePointer<Float>, _ right: UnsafeMutablePointer<Float>) {
        outs[0] = left
        outs[1] = right
        if !configured {
            processor.configure(sampleRate: Int(blendBusRate), channels: 2)
            configured = true
        }
        takeOrders()
        playingNow = held.playing
        volumeNow = held.volume
        if held.playing { seenPlaying = true }
        if wasHeld && !stage.held {
            // The session placed the held song again: it plays from where it stopped.
            started = true
            rebuffering = false
            dryNow = false
        }
        wasHeld = stage.held
        tell()

        var written = 0
        while written < frames {
            stage.crossDue(processor)
            if stage.startingNow { beginIncoming() }
            let room = stage.framesUntilNext(frames - written)
            var n = 0
            if replayLeft > 0 {
                n = replay(at: written, room)
            } else if keptActive {
                // The kept frames play only while this deck itself plays (and through its volume's
                // fade as it pauses), and nothing of its song past them is ever read.
                if held.playing {
                    n = playKept(at: written, room)
                } else if gain > 0 {
                    let fading = Int((Double(gain) * Self.gainFadeFrames).rounded(.up)) - written
                    if fading > 0 { n = playKept(at: written, min(room, fading)) }
                }
            } else if tailCount > 0 {
                n = drainTail(at: written, frames: written + room)
            } else if !silentNow && (held.playing || gain > 0) {
                n = fill(from: written, limit: room)
            } else if let source, !silentNow {
                source.ring.dropStale()
            }
            if n == 0 {
                n = room
                for ch in 0..<2 { (outs[ch] + written).update(repeating: 0, count: n) }
            } else {
                stage.applyRampIn(outs, offset: written, frames: n)
            }
            stage.clock.pulled(n)
            written += n
        }
        // History before the deck's volume: a replay is turned down once, as it plays again.
        remember(frames)
        applyGain(held.playing ? held.volume : 0, frames)
        renderedCycle = mix?.cycle ?? -1
        songMsNext = source == nil ? .nan : processor.songMs
        publishHead()
        if let source { chunksSeen = source.ring.chunksWritten }
        rendered.add(frames, ordering: .relaxed)
    }

    /// Before either deck renders a cycle: the orders are taken now, so the cycle's timing reads
    /// this deck's rate and latency as it will render, and kept frames about to play again fix
    /// where they end before the incoming deck renders, whichever is pulled first.
    public func prepareCycle() {
        takeOrders()
        if keptActive && !cutPublished && held.playing { publishCut() }
    }

    /// Takes main's orders when they are free (never waiting), and what follows from them: a new
    /// song, kept frames dropped, a new rate or latency, a pause inside the kept frames.
    private func takeOrders() {
        if let fresh = orders.withLockIfAvailable({ $0 }) { held = fresh }
        if held.current !== source { takeSong(held.current) }
        if held.dropKept != keptDropped {
            // Superseded (a skip, a seek, a stop while paused): the kept frames never play, and a
            // song waiting to come in after them comes in now.
            keptDropped = held.dropKept
            dropKeptFrames()
        }
        if stage.clock.set(rate: Double(held.speed), latency: held.latencyFrames) {
            stage.mappingChanged()
            // The outgoing song's start is heard elsewhere now: told again, as after a restart.
            if stage.role == .outgoing && stage.startPosted { replaceStart = true }
        }
        if keptActive && cutPublished && wasPlaying && !held.playing && keptOut < keptTotal { pausedInKept() }
        wasPlaying = held.playing
    }

    /// Paused again inside the kept frames. With the incoming song not yet started, where they
    /// end is withdrawn and fixed again from what is left when this deck plays again; with it
    /// started, the rest is dropped past this deck's fade into the pause.
    private func pausedInKept() {
        if stage.partner?.started == true {
            keptTotal = min(keptTotal, keptOut + DeckFlow.rampFrames(ms: DeckFlow.pauseFadeMs, rate: Double(held.speed)))
        } else {
            cutPublished = false
            stage.cutBus = -1
        }
    }

    /// Silent and consuming nothing: parked by main, or by the blend's rules.
    private var silentNow: Bool { stage.silent || (held.parked && !stage.started) }

    /// The song time heard at the head: where the next frame is heard, less the stretch between.
    private func publishHead() {
        guard let mix, !songMsNext.isNaN else {
            headMsBits.store(Double.nan.bitPattern, ordering: .relaxed)
            return
        }
        let ahead = Double(stage.clock.renderedBus - mix.cycleBus) * stage.clock.current.rate * 1_000 / blendBusRate
        headMsBits.store((songMsNext - ahead).bitPattern, ordering: .relaxed)
    }

    private func takeSong(_ next: (any DeckFeed)?) {
        source = next
        generation = -1
        started = false
        rebuffering = false
        newSong = true
        needsFlush = true
        songDone = false
        ended = false
        expectedFrame = -1
        tailCount = 0
        tailRead = 0
        dryNow = false
        pausedTold = false
        endTold = false
        if next == nil {
            phaseCode.store(DeckState.idle.code, ordering: .relaxed)
        } else if let next, Int64(next.serial) == registeredSerial {
            // The song the registered blend was armed for: its registration stands.
        } else {
            // A song loaded plays as itself until a blend registers it; the outgoing song's kept
            // frames belong to the blend it left.
            registeredSerial = -1
            stage.release()
            clearKept()
        }
    }

    /// The incoming song's start: what lies before its entry is dropped and its stream starts
    /// there, its first frame on the start's frame.
    private func beginIncoming() {
        stage.startingNow = false
        guard let source else { return }
        source.ring.dropStale()
        // A start that came past the cut drops as much more, so its song lands where planned.
        source.ring.drop(beforeSongFrame: Self.songFrame(stage.entryMs) + stage.startLate)
        started = true
        rebuffering = false
        needsFlush = true
        dryNow = false
    }

    /// Fills the output from the song, at most `limit` frames, moving on to the following song
    /// without a gap.
    private func fill(from start: Int, limit: Int) -> Int {
        var written = start
        let end = start + limit
        while written < end {
            guard let source else { break }
            source.ring.dropStale()
            let gen = source.ring.generation.load(ordering: .acquiring)
            if gen != generation {
                // A jump: start again from the new place once enough is there.
                generation = gen
                started = false
                rebuffering = false
                needsFlush = true
                songDone = false
                ended = false
                expectedFrame = -1
                tailCount = 0
                tailRead = 0
                dryNow = false
            }
            if ended { break }
            if songDone {
                guard moveOn(from: source) else { break }
                continue
            }
            if !started {
                guard readyToStart(source), !dryNow || rejoins(source) else {
                    phaseCode.store(source.hasFailed ? DeckState.ended.code : DeckState.buffering.code, ordering: .relaxed)
                    break
                }
                started = true
                if dryNow {
                    // Back after running dry: in with a short ramp, never a step.
                    dryNow = false
                    stage.rampIn(DeckFlow.rampFrames(rate: Double(held.speed)))
                    stage.readiness(true)
                }
            }
            if needsFlush {
                if newSong { onNewSong?(source) }
                // Each stream decides again whether the sound is shaped, by the settings now.
                processor.configure(sampleRate: Int(blendBusRate), channels: 2)
                newSong = false
                needsFlush = false
                let frame = source.ring.nextSongFrame() ?? Int64(Double(source.jumpedToMs) * blendBusRate / 1_000)
                processor.flush(streamStartMs: Double(frame) * 1_000 / blendBusRate, entryId: source.entryId)
                expectedFrame = frame
            }
            let want = min(end - written, Self.block)
            let pull = source.ring.pull(into: scratch, offset: 0, frames: want)
            if pull.frames > 0 {
                if let frame = pull.songFrame {
                    if expectedFrame >= 0 && abs(frame - expectedFrame) > 2 {
                        processor.moveSongTime(toMs: Double(frame) * 1_000 / blendBusRate)
                    }
                    expectedFrame = frame + Int64(pull.frames)
                }
                outAt[0] = outs[0] + written
                outAt[1] = outs[1] + written
                written += processor.process(scratch, frames: pull.frames, output: outAt)
                renderedSerial.store(source.serial, ordering: .relaxed)
                renderedGeneration.store(gen, ordering: .relaxed)
                phaseCode.store(DeckState.ready.code, ordering: .relaxed)
                songMsBits.store(processor.songMs.bitPattern, ordering: .relaxed)
                if stage.role != .none && !stage.toldReady { stage.readiness(true) }
            }
            if pull.ended {
                songDone = true
                tailCount = processor.endOfStream(into: tail)
                tailRead = 0
                written += drainTail(at: written, frames: end)
                continue
            }
            if pull.frames == 0 {
                // Ran dry: wait for more, the longer amount this time.
                if !source.hasFailed && !source.isDecodedToEnd {
                    if !dryNow {
                        dryNow = true
                        fadeOutDry(source, from: start, to: written)
                        stage.readiness(false)
                    }
                    started = false
                    rebuffering = true
                    phaseCode.store(DeckState.buffering.code, ordering: .relaxed)
                } else if source.hasFailed {
                    phaseCode.store(DeckState.ended.code, ordering: .relaxed)
                }
                break
            }
        }
        return written - start
    }

    /// The frames this render wrote before running dry fade out over 20 ms, while more is truly
    /// late: never at a gapless join or a song's own end.
    private func fadeOutDry(_ source: any DeckFeed, from start: Int, to end: Int) {
        let delivered = source.ring.chunksWritten != chunksSeen
        guard end > start, DeckFlow.fadesOut(decodedToEnd: source.isDecodedToEnd, hasFollowing: held.following != nil,
                                              deliveredSinceLast: delivered) else { return }
        let frames = min(end - start, DeckFlow.rampFrames(rate: Double(held.speed)))
        let from = end - frames
        for i in 0..<frames {
            let g = DeckFlow.fadeOutGain(i, of: frames)
            outs[0][from + i] *= g
            outs[1][from + i] *= g
        }
    }

    /// Whether enough sound waits to start, by the start-after rule for streams.
    private func readyToStart(_ source: any DeckFeed) -> Bool {
        if source.isDecodedToEnd { return true }
        let waiting = source.ring.waitingFrames
        guard waiting > 0 else { return false }
        let bufferedUs = Int(Double(waiting) * 1_000_000 / blendBusRate)
        let startAfter = source.isLocal ? 250 : held.startAfterMs
        // Never wait for more than most of what the ring holds.
        if waiting >= source.ring.capacity * PCMRing.chunkFrames * 3 / 4 { return true }
        return hasEnoughToStart(bufferedUs: bufferedUs, speed: held.speed, rebuffering: rebuffering, startAfterMs: startAfter)
    }

    /// Whether a deck that ran dry holds enough to play on continuously.
    private func rejoins(_ source: any DeckFeed) -> Bool {
        DeckFlow.rejoins(waitingFrames: source.ring.waitingFrames, cycleFrames: Int(mix?.cycleBusFrames ?? 1_024),
                         bufferFrames: Self.rejoinBufferFrames, ended: source.isDecodedToEnd)
    }

    /// How a song's end went, decided inside the orders' lock.
    private enum SongEnd {
        case ended
        case movedOn(any DeckFeed)
        /// Nothing changed: decided again next time.
        case later
    }

    /// The song ended: on to the following song, hold, or stop. False when it does not move on now.
    /// Ending or moving on is decided inside the try-lock from the orders as they are then (the
    /// follower main named compared with the one loaded now), and the session's answer is used up
    /// only once that took, so a failed try decides again next time from the same state.
    private func moveOn(from source: any DeckFeed) -> Bool {
        let stale = held.pauseAtEnd ? nil : held.following.map { Int64($0.serial) }
        if stage.atSongEnd(followerSerial: stale) == .hold {
            // Inside a blend the outgoing song neither moves on nor ends: the session hears it and
            // the incoming song plays on alone. Holding never depends on the follower.
            if !endTold {
                endTold = true
                stage.post(stale != nil ? .movedOn : .ended)
            }
            return false
        }
        let outcome = orders.withLockIfAvailable { orders -> SongEnd in
            let follower = orders.pauseAtEnd ? nil : orders.following
            switch stage.atSongEnd(followerSerial: follower.map { Int64($0.serial) }) {
            case .moveOn:
                guard orders.current === source, let follower else { return .later }
                orders.current = follower
                orders.following = nil
                orders.advances += 1
                return .movedOn(follower)
            case .end:
                guard orders.current === source else { return .ended }
                orders.endings += 1
                if orders.pauseAtEnd { orders.playing = false }
                return .ended
            case .hold:
                return .later
            }
        }
        switch outcome ?? .later {
        case .later:
            return false
        case .ended:
            if !endTold {
                endTold = true
                stage.post(.ended)
            }
            stage.followed()
            ended = true
            phaseCode.store(DeckState.ended.code, ordering: .relaxed)
            return false
        case .movedOn(let next):
            if !endTold { stage.post(.movedOn) }
            stage.followed()
            held.current = next
            held.following = nil
            takeSong(next)
            return true
        }
    }

    private func drainTail(at offset: Int, frames: Int) -> Int {
        let take = min(tailCount - tailRead, frames - offset)
        guard take > 0 else { return 0 }
        for ch in 0..<2 { (outs[ch] + offset).update(from: tail[ch] + tailRead, count: take) }
        tailRead += take
        if tailRead >= tailCount {
            tailRead = 0
            tailCount = 0
        }
        return take
    }

    /// Frames the volume takes from silence to full.
    private static let gainFadeFrames = DeckFlow.pauseFadeMs * blendBusRate / 1_000

    /// Moves the volume toward `target` no faster than silence to full in 10 ms.
    private func applyGain(_ target: Float, _ frames: Int) {
        let step = Float(1 / Self.gainFadeFrames)
        if gain == target {
            if gain != 1 {
                for ch in 0..<2 { for i in 0..<frames { outs[ch][i] *= gain } }
            }
            return
        }
        for i in 0..<frames {
            if gain < target { gain = min(target, gain + step) } else if gain > target { gain = max(target, gain - step) }
            outs[0][i] *= gain
            outs[1][i] *= gain
        }
    }

    // MARK: What the session hears

    /// Tells the session what the deck finds before it renders: a pause with frames left (only
    /// once the deck was seen playing since it was registered, so the arm's own moment never
    /// counts), the incoming song's readiness while it consumes nothing, the outgoing song's frames
    /// at the start and at the blend's end.
    private func tell() {
        guard stage.role != .none, let source else { return }
        if !held.playing && seenPlaying && source.ring.waitingFrames > 0 {
            if !pausedTold {
                pausedTold = true
                stage.post(.paused, value: stage.clock.renderedBus)
            }
        } else if held.playing {
            pausedTold = false
        }
        switch stage.role {
        case .incoming:
            guard stage.parked || stage.held else { return }
            let enough = source.ring.waitingFrames > 0 && (stage.held ? rejoins(source) : readyToStart(source))
            stage.readiness(held.playing && !source.hasFailed && (source.isDecodedToEnd || enough))
        case .outgoing:
            lookAhead(source)
        case .none:
            break
        }
    }

    /// The outgoing song's frame at the plan's start, once its ring holds it and the start is
    /// close (so the session decides it when it can propose it), or the song's end found before
    /// it; after an engine restart, the start placed again; and the frame at the blend's end, just
    /// before it is needed (after any hold has extended it).
    private func lookAhead(_ source: any DeckFeed) {
        guard let mix else { return }
        let ahead = Int64(replayLeft + (tailCount - tailRead) + processor.latency)
        let partnerBegun = stage.partner?.started ?? false
        if !stage.startPosted || replaceStart {
            switch source.ring.ahead(ofSongFrame: Self.songFrame(stage.startMs)) {
            case .at(let n):
                let s = stage.clock.bus(ofSource: stage.clock.source + ahead + Int64(n))
                if replaceStart {
                    replaceStart = false
                    if stage.startPosted && !partnerBegun { stage.post(.restarted, value: s) }
                } else if s - mix.cycleBus <= mix.timing.proposalLeadFrames + 2 * mix.cycleBusFrames + 4_800 {
                    stage.post(.startAt, value: s)
                    stage.startPosted = true
                }
            case .endsBefore:
                replaceStart = false
                if !stage.startPosted {
                    stage.post(.ended)
                    stage.startPosted = true
                }
            case .notYet:
                break
            }
        }
        guard stage.startPosted, let partner = stage.partner, partner.startIndex >= 0 || partner.started else { return }
        let endMs = stage.startMs + stage.overlapMs + stage.extensionMs
        guard stage.endPostedMs != endMs, case .at(let n) = source.ring.ahead(ofSongFrame: Self.songFrame(endMs)) else { return }
        let end = stage.clock.bus(ofSource: stage.clock.source + ahead + Int64(n))
        if end - mix.cycleBus <= mix.timing.placementHorizonFrames + 2 * mix.cycleBusFrames + 4_800 {
            stage.post(.endReached, value: end)
            stage.endPostedMs = endMs
        }
    }

    /// The bus frame up to which this deck's sound is decided, for the session: a deck the engine
    /// did not pull this cycle counts as caught up. How far past the cycle and its own latency the
    /// stage pulled (it pulls in chunks of its own) is kept, the most seen, for the timing.
    public func renderedBus(cycle: Int, cycleBus: Int64, cycleFrames: Int64) -> Int64 {
        let bus = stage.clock.renderedBus
        if renderedCycle == cycle {
            let lead = bus - cycleBus - cycleFrames - Int64(stage.clock.current.latency.rounded(.up))
            if lead > pullLead { pullLead = lead }
            return bus
        }
        return max(bus, cycleBus + cycleFrames + Int64(stage.clock.current.latency))
    }

    /// The most the time-pitch stage was seen to pull past a cycle's end beyond its latency, in
    /// bus frames.
    public private(set) var pullLead: Int64 = 0

    /// A new blend registers this deck, before its stage is armed, for the song of `serial` (-1
    /// when unknown). The orders are taken as they are now, so a song main loaded just before the
    /// arm is the one registered; and that song, taken later (the orders were busy), never lets go
    /// of the registration. Whether it plays is read from the orders too.
    public func registered(serial: Int64 = -1) {
        registeredSerial = serial
        if let fresh = orders.withLockIfAvailable({ $0 }) {
            held = fresh
            if held.current !== source { takeSong(held.current) }
        }
        endTold = false
        pausedTold = false
        seenPlaying = held.playing
        clearKept()
    }

    // MARK: A pause past the handover

    /// The outgoing song's frames up to the blend's start are kept now (while paused), raw, so
    /// they play on resume; they are shaped block by block as they play.
    public func keepUntilStart() {
        guard !keptActive else { return }
        clearKept()
        keptActive = true
        var count = 0
        if let source {
            switch source.ring.ahead(ofSongFrame: Self.songFrame(stage.startMs)) {
            case .at(let n), .endsBefore(let n): count = n
            case .notYet: count = source.ring.waitingFrames
            }
            var left = min(count, Self.keptCapacity)
            while left > 0 {
                let pull = source.ring.pull(into: scratch, offset: 0, frames: min(left, Self.block))
                if pull.frames == 0 { break }
                for ch in 0..<2 { (kept[ch] + keptRaw).update(from: scratch[ch], count: pull.frames) }
                keptRaw += pull.frames
                left -= pull.frames
            }
        }
        // The processor's own held frames come out first, then the kept ones, then its tail.
        keptTotal = keptRaw + processor.latency
        keptPending.store(keptTotal > 0, ordering: .releasing)
    }

    /// Where the kept frames left end, fixed as the deck plays them again: the incoming song comes
    /// in there.
    private func publishCut() {
        cutPublished = true
        stage.cutBus = stage.clock.bus(ofSource: stage.clock.source + Int64(keptTotal - keptOut))
        if keptTotal > keptOut { stage.rampIn(DeckFlow.rampFrames(rate: Double(held.speed))) }
    }

    /// Hands out kept frames, shaped as they go. The first call while the deck plays again fixes
    /// where those left end (unless the cycle's start already did); frames played as the deck
    /// fades into its pause fix nothing.
    private func playKept(at offset: Int, _ room: Int) -> Int {
        if !cutPublished && held.playing { publishCut() }
        guard keptOut < keptTotal else {
            keptPending.store(false, ordering: .releasing)
            return 0
        }
        var n = 0
        if keptRead < keptRaw {
            let take = min(keptRaw - keptRead, room, Self.block)
            for ch in 0..<2 { scratch[ch].update(from: kept[ch] + keptRead, count: take) }
            outAt[0] = outs[0] + offset
            outAt[1] = outs[1] + offset
            n = processor.process(scratch, frames: take, output: outAt)
            keptRead += take
        } else {
            if !keptDrained {
                keptDrained = true
                tailCount = processor.endOfStream(into: tail)
                tailRead = 0
            }
            n = drainTail(at: offset, frames: offset + min(room, keptTotal - keptOut))
            if n == 0 {
                keptOut = keptTotal
                keptPending.store(false, ordering: .releasing)
                return 0
            }
        }
        // The last 20 ms fade out.
        let fade = min(keptTotal, DeckFlow.rampFrames(rate: Double(held.speed)))
        for i in 0..<n {
            let k = keptOut + i - (keptTotal - fade)
            if k >= 0 {
                let g = DeckFlow.fadeOutGain(k, of: fade)
                outs[0][offset + i] *= g
                outs[1][offset + i] *= g
            }
        }
        keptOut += n
        if keptOut >= keptTotal { keptPending.store(false, ordering: .releasing) }
        return n
    }

    /// Kept frames superseded: a start still waiting on where they end comes in now.
    private func dropKeptFrames() {
        let waiting = keptActive && keptOut < keptTotal
        let untold = keptActive && !cutPublished
        clearKept()
        if (waiting || untold) && stage.cutAtStart { stage.cutBus = stage.clock.renderedBus }
    }

    private func clearKept() {
        keptActive = false
        keptRaw = 0
        keptRead = 0
        keptTotal = 0
        keptOut = 0
        keptDrained = false
        cutPublished = false
        keptPending.store(false, ordering: .releasing)
    }

    // MARK: Engine restarts

    /// The engine restarted, resetting the time-pitch stage: the deck's clock is anchored again at
    /// `bus`, and the last `replayBus` bus frames of what it handed the stage are handed to it
    /// again. Both decks replay the same stretch, so they stay lined up, and the actions waiting
    /// on its frames move by the replay.
    public func restarted(atBus bus: Int64, replayBus: Double) {
        let piece = stage.clock.current
        // The stage held what it pulled ahead past its own latency too (it pulls in chunks of its
        // own); that is replayed as well, so this deck's song is heard exactly `replayBus` later.
        let ahead = max(0, Double(stage.clock.renderedBus - bus) - piece.latency)
        replayLeft = min(historyFilled, Int(((max(0, replayBus) + ahead) * piece.rate).rounded()))
        replayAt = (historyAt - replayLeft + Self.historyCapacity) % Self.historyCapacity
        stage.clock.reset(atBus: bus, rate: piece.rate, latency: piece.latency)
        stage.shift(by: Int64(replayLeft))
        stage.shift(byBus: Int64(max(0, replayBus).rounded()))
        replaceStart = stage.role == .outgoing && stage.startPosted
    }

    private func replay(at offset: Int, _ room: Int) -> Int {
        let take = min(replayLeft, room, Self.historyCapacity - replayAt)
        for ch in 0..<2 { (outs[ch] + offset).update(from: history[ch] + replayAt, count: take) }
        replayAt = (replayAt + take) % Self.historyCapacity
        replayLeft -= take
        return take
    }

    private func remember(_ frames: Int) {
        var done = 0
        while done < frames {
            let take = min(frames - done, Self.historyCapacity - historyAt)
            for ch in 0..<2 { (history[ch] + historyAt).update(from: outs[ch] + done, count: take) }
            historyAt = (historyAt + take) % Self.historyCapacity
            done += take
        }
        historyFilled = min(Self.historyCapacity, historyFilled + frames)
    }

    public static func songFrame(_ ms: Double) -> Int64 { Int64((ms * blendBusRate / 1_000).rounded()) }
}

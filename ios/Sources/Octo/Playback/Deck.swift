import AVFoundation
import OctoKit
import Synchronization
import os

/// The rate every deck plays at; the mixer turns it into whatever the output wants.
let deckRate: Double = 48_000

/// What a deck's audio thread is told, and what it changes back: the song it plays, the one to
/// follow without a gap, whether it plays, and its volume.
struct DeckOrders {
    var current: DeckSource?
    var following: DeckSource?
    var playing = false
    var pauseAtEnd = false
    var volume: Float = 1
    /// Real time of sound waiting before a stream starts, and the pace it plays at.
    var startAfterMs = 1_000
    var speed: Float = 1
    /// The time-pitch stage's latency, in bus frames (0 while it is bypassed).
    var latencyFrames = 0.0
    /// A spare loaded for a blend: silent and consuming nothing until the blend starts it.
    var parked = false
    /// Bumped by main when the outgoing song's kept frames are no longer wanted.
    var dropKept = 0
    /// Bumped by the audio thread each time it moves on to `following` by itself.
    var advances = 0
    /// Bumped by the audio thread each time a song ends with nothing to follow, or stops at its end.
    var endings = 0
}

/// The audio thread's side of a deck. Only the audio thread touches its fields once the deck
/// runs; the main side talks to it through `orders` (which the audio thread only tries to take,
/// never waits on) and reads what it reports from the atomics.
///
/// In a blend it follows its stage: the session's actions land on its source frames (counted by
/// its clock, every frame handed to the time-pitch stage, silence included), a parked spare
/// consumes nothing until its start, and the outgoing song neither moves on nor ends by itself
/// once the blend has started.
final class DeckRender: @unchecked Sendable {
    let orders = OSAllocatedUnfairLock(initialState: DeckOrders())
    let processor: DeckProcessor
    let stream: SoundStream?
    let stage = DeckBlendStage()
    /// The render-thread session's owner, and which deck this is to it.
    weak var mix: MixRender?
    var index = 0

    /// What the audio thread reports: the song time of the next frame, where it stands, and the
    /// frames it has handed out (to tell it is running).
    let songMsBits = Atomic<UInt64>(0)
    let phaseCode = Atomic<Int>(DeckState.idle.code)
    let rendered = Atomic<Int>(0)
    /// The song and the jump the audio thread last handed out sound of.
    let renderedSerial = Atomic<Int>(0)
    let renderedGeneration = Atomic<Int>(-1)

    private var held = DeckOrders()
    private var source: DeckSource?
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

    /// Render thread: the song time of the next frame handed out (nan with no song), the cycle it
    /// last rendered in, and whether it plays now and at what volume.
    private(set) var songMsNext = Double.nan
    private var renderedCycle = -1
    private(set) var playingNow = false
    private(set) var volumeNow: Float = 1
    /// Out of sound and waiting to rejoin; chunks delivered by the last look; what the session was
    /// told of a pause and of the song's end.
    private var dryNow = false
    private var chunksSeen = 0
    private var pausedTold = false
    private var endTold = false
    private var wasHeld = false
    /// The engine restarted: the outgoing song's start is placed again.
    private var replaceStart = false

    private static let block = 4096
    private let scratch: [UnsafeMutablePointer<Float>]
    private let tail: [UnsafeMutablePointer<Float>]
    private var tailCount = 0
    private var tailRead = 0
    private var outAt: [UnsafeMutablePointer<Float>]
    private var outs: [UnsafeMutablePointer<Float>]
    private var tailAt: [UnsafeMutablePointer<Float>]

    /// The outgoing song's frames up to its blend's start, kept through a pause past the handover
    /// so they play on resume before the incoming song comes in.
    private static let keptCapacity = 96_000
    private let kept: [UnsafeMutablePointer<Float>]
    private var keptCount = 0
    private var keptRead = 0
    private var keptDropped = 0

    /// The last frames handed to the time-pitch stage, replayed after an engine restart reset it,
    /// so nothing the stage held is lost.
    private static let historyCapacity = 16_384
    private let history: [UnsafeMutablePointer<Float>]
    private var historyAt = 0
    private var historyFilled = 0
    private var replayLeft = 0
    private var replayAt = 0

    /// A rejoin waits for a render cycle plus a quarter of this much (500 ms) in the ring.
    private static let rejoinBufferFrames = 24_000

    init(processor: DeckProcessor, stream: SoundStream?) {
        self.processor = processor
        self.stream = stream
        scratch = (0..<2).map { _ in .allocate(capacity: Self.block) }
        tail = (0..<2).map { _ in .allocate(capacity: Self.block * 2) }
        kept = (0..<2).map { _ in .allocate(capacity: Self.keptCapacity) }
        history = (0..<2).map { _ in .allocate(capacity: Self.historyCapacity) }
        for buffer in history { buffer.initialize(repeating: 0, count: Self.historyCapacity) }
        outAt = scratch
        outs = scratch
        tailAt = tail
    }

    deinit {
        for buffer in scratch + tail + kept + history { buffer.deallocate() }
    }

    // MARK: The audio thread

    func render(_ frameCount: AVAudioFrameCount, _ list: UnsafeMutablePointer<AudioBufferList>) -> OSStatus {
        let buffers = UnsafeMutableAudioBufferListPointer(list)
        let frames = Int(frameCount)
        guard buffers.count >= 2, let left = buffers[0].mData?.assumingMemoryBound(to: Float.self),
              let right = buffers[1].mData?.assumingMemoryBound(to: Float.self) else { return noErr }
        outs[0] = left
        outs[1] = right
        if !configured {
            processor.configure(sampleRate: Int(deckRate), channels: 2)
            configured = true
        }
        if let fresh = orders.withLockIfAvailable({ $0 }) { held = fresh }
        if held.current !== source { takeSong(held.current) }
        if held.dropKept != keptDropped {
            // Superseded (a skip, a seek, a stop while paused): the kept frames never play, and a
            // song waiting to come in after them comes in now.
            keptDropped = held.dropKept
            keptCount = 0
            keptRead = 0
            if stage.cutAtStart { stage.cutBus = stage.clock.renderedBus }
        }
        stage.clock.set(rate: Double(held.speed), latency: held.latencyFrames)
        playingNow = held.playing
        volumeNow = held.volume
        if wasHeld && !stage.held {
            // The session placed the held song again: it plays from where it stopped.
            started = true
            rebuffering = false
            dryNow = false
        }
        wasHeld = stage.held
        tell()

        var written = 0
        var keptPlayed = false
        let partnerPlays = mix.map { $0.renders.count == 2 && $0.renders[1 - index].playingNow } ?? false
        while written < frames {
            stage.crossDue(processor)
            if stage.startingNow { beginIncoming() }
            let room = stage.framesUntilNext(frames - written)
            var n = 0
            if replayLeft > 0 {
                n = replay(at: written, room)
            } else if keptRead < keptCount && partnerPlays {
                n = playKept(at: written, room)
                keptPlayed = true
            } else if keptCount > 0 && keptRead >= keptCount {
                n = 0
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
        let target: Float = keptPlayed ? partnerVolume : (held.playing ? held.volume : 0)
        applyGain(target, frames)
        remember(frames)
        renderedCycle = mix?.cycle ?? -1
        songMsNext = source == nil ? .nan : processor.songMs
        if let source { chunksSeen = source.ring.chunksWritten }
        rendered.add(frames, ordering: .relaxed)
        return noErr
    }

    /// Silent and consuming nothing: parked by main, or by the blend's rules.
    private var silentNow: Bool { stage.silent || (held.parked && !stage.started) }

    private var partnerVolume: Float {
        guard let mix, mix.renders.count == 2 else { return 1 }
        return mix.renders[1 - index].volumeNow
    }

    private func takeSong(_ next: DeckSource?) {
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
        } else {
            // A song loaded plays as itself until a blend registers it; the outgoing song's kept
            // frames belong to the blend it left.
            stage.release()
            keptCount = 0
            keptRead = 0
        }
    }

    /// The incoming song's start: what lies before its entry is dropped and its stream starts
    /// there, its first frame on the start's frame.
    private func beginIncoming() {
        stage.startingNow = false
        guard let source else { return }
        source.ring.dropStale()
        source.ring.drop(beforeSongFrame: Self.songFrame(stage.entryMs))
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
                    phaseCode.store(source.failure != nil ? DeckState.ended.code : DeckState.buffering.code, ordering: .relaxed)
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
                if newSong, let setUp = source.setUp { stream?.setNextSong(setUp, tags: source.loudness) }
                // Each stream decides again whether the sound is shaped, by the settings now.
                processor.configure(sampleRate: Int(deckRate), channels: 2)
                newSong = false
                needsFlush = false
                let frame = source.ring.nextSongFrame() ?? Int64(Double(source.jumpMs.load(ordering: .relaxed)) * deckRate / 1000)
                processor.flush(streamStartMs: Double(frame) * 1000 / deckRate, entryId: source.item.entryId)
                expectedFrame = frame
            }
            let want = min(end - written, Self.block)
            let pull = source.ring.pull(into: scratch, offset: 0, frames: want)
            if pull.frames > 0 {
                if let frame = pull.songFrame {
                    if expectedFrame >= 0 && abs(frame - expectedFrame) > 2 {
                        processor.moveSongTime(toMs: Double(frame) * 1000 / deckRate)
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
                if source.failure == nil && !source.decodedToEnd.load(ordering: .relaxed) {
                    if !dryNow {
                        dryNow = true
                        fadeOutDry(source, from: start, to: written)
                        stage.readiness(false)
                    }
                    started = false
                    rebuffering = true
                    phaseCode.store(DeckState.buffering.code, ordering: .relaxed)
                } else if source.failure != nil {
                    phaseCode.store(DeckState.ended.code, ordering: .relaxed)
                }
                break
            }
        }
        return written - start
    }

    /// The frames this render wrote before running dry fade out over 20 ms, while more is truly
    /// late: never at a gapless join or a song's own end.
    private func fadeOutDry(_ source: DeckSource, from start: Int, to end: Int) {
        let delivered = source.ring.chunksWritten != chunksSeen
        guard end > start, DeckFlow.fadesOut(decodedToEnd: source.decodedToEnd.load(ordering: .relaxed),
                                              hasFollowing: held.following != nil, deliveredSinceLast: delivered) else { return }
        let frames = min(end - start, DeckFlow.rampFrames(rate: Double(held.speed)))
        let from = end - frames
        for i in 0..<frames {
            let g = DeckFlow.fadeOutGain(i, of: frames)
            outs[0][from + i] *= g
            outs[1][from + i] *= g
        }
    }

    /// Whether enough sound waits to start, by the start-after rule for streams.
    private func readyToStart(_ source: DeckSource) -> Bool {
        if source.decodedToEnd.load(ordering: .relaxed) { return true }
        let waiting = source.ring.waitingFrames
        guard waiting > 0 else { return false }
        let bufferedUs = Int(Double(waiting) * 1_000_000 / deckRate)
        let startAfter = source.isLocal ? 250 : held.startAfterMs
        // Never wait for more than most of what the ring holds.
        if waiting >= source.ring.capacity * PCMRing.chunkFrames * 3 / 4 { return true }
        return hasEnoughToStart(bufferedUs: bufferedUs, speed: held.speed, rebuffering: rebuffering, startAfterMs: startAfter)
    }

    /// Whether a deck that ran dry holds enough to play on continuously.
    private func rejoins(_ source: DeckSource) -> Bool {
        DeckFlow.rejoins(waitingFrames: source.ring.waitingFrames, cycleFrames: Int(mix?.cycleBusFrames ?? 1_024),
                         bufferFrames: Self.rejoinBufferFrames, ended: source.decodedToEnd.load(ordering: .relaxed))
    }

    /// The song ended: on to the following song, hold, or stop. False when it does not move on now.
    private func moveOn(from source: DeckSource) -> Bool {
        let follower = held.pauseAtEnd ? nil : held.following.map { Int64($0.serial) }
        switch stage.atSongEnd(followerSerial: follower) {
        case .hold:
            // Inside a blend the outgoing song neither moves on nor ends: the session hears it and
            // the incoming song plays on alone.
            if !endTold {
                endTold = true
                stage.post(follower != nil ? .movedOn : .ended)
            }
            return false
        case .end:
            if !endTold {
                endTold = true
                stage.post(.ended)
            }
            stage.followed()
            let noted = orders.withLockIfAvailable { orders -> Bool in
                guard orders.current === source else { return true }
                orders.endings += 1
                if orders.pauseAtEnd { orders.playing = false }
                return true
            }
            guard noted != nil else { return false }
            ended = true
            phaseCode.store(DeckState.ended.code, ordering: .relaxed)
            return false
        case .moveOn:
            if !endTold { stage.post(.movedOn) }
            let next = orders.withLockIfAvailable { orders -> DeckSource? in
                guard orders.current === source, let following = orders.following else { return nil }
                orders.current = following
                orders.following = nil
                orders.advances += 1
                return following
            }
            guard let next = next ?? nil else { return false }
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

    /// Moves the volume toward `target` no faster than silence to full in 10 ms.
    private func applyGain(_ target: Float, _ frames: Int) {
        let step = Float(1 / (0.01 * deckRate))
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

    /// Tells the session what the deck finds before it renders: a pause with frames left, the
    /// incoming song's readiness while it consumes nothing, the outgoing song's frames at the start
    /// and at the blend's end.
    private func tell() {
        guard stage.role != .none, let source else { return }
        if !held.playing && source.ring.waitingFrames > 0 {
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
            let decoded = source.decodedToEnd.load(ordering: .relaxed)
            let enough = source.ring.waitingFrames > 0 && (stage.held ? rejoins(source) : readyToStart(source))
            stage.readiness(held.playing && source.failure == nil && (decoded || enough))
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
    private func lookAhead(_ source: DeckSource) {
        guard let mix else { return }
        let ahead = Int64(replayLeft + (tailCount - tailRead) + processor.latency)
        let partnerBegun = stage.partner.map { $0.started } ?? false
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
    /// did not pull this cycle counts as caught up.
    func renderedBus(cycle: Int, cycleBus: Int64, cycleFrames: Int64) -> Int64 {
        let bus = stage.clock.renderedBus
        if renderedCycle == cycle { return bus }
        return max(bus, cycleBus + cycleFrames + Int64(stage.clock.current.latency))
    }

    /// A new blend registered this deck.
    func registered() {
        endTold = false
        pausedTold = false
        keptCount = 0
        keptRead = 0
    }

    // MARK: A pause past the handover

    /// The outgoing song's frames up to the blend's start, shaped as they would have played, are
    /// kept now (while paused), so they play on resume, its last 20 ms fading, and the incoming
    /// song comes in where they end.
    func keepUntilStart() {
        guard keptCount == 0, let source else { return }
        let count: Int
        switch source.ring.ahead(ofSongFrame: Self.songFrame(stage.startMs)) {
        case .at(let n), .endsBefore(let n): count = n
        case .notYet: count = source.ring.waitingFrames
        }
        var total = 0
        var left = min(count, Self.keptCapacity - processor.latency - Self.block)
        while left > 0 {
            let pull = source.ring.pull(into: scratch, offset: 0, frames: min(left, Self.block))
            if pull.frames == 0 { break }
            outAt[0] = kept[0] + total
            outAt[1] = kept[1] + total
            total += processor.process(scratch, frames: pull.frames, output: outAt)
            left -= pull.frames
        }
        outAt[0] = kept[0] + total
        outAt[1] = kept[1] + total
        total += processor.endOfStream(into: outAt)
        let fade = min(total, DeckFlow.rampFrames(rate: Double(held.speed)))
        for i in 0..<fade {
            let g = DeckFlow.fadeOutGain(i, of: fade)
            kept[0][total - fade + i] *= g
            kept[1][total - fade + i] *= g
        }
        keptCount = total
        keptRead = 0
        if total == 0 { stage.cutBus = stage.clock.renderedBus }
    }

    private func playKept(at offset: Int, _ room: Int) -> Int {
        if keptRead == 0 {
            // Where the kept frames end is where the incoming song comes in.
            stage.cutBus = stage.clock.bus(ofSource: stage.clock.source + Int64(keptCount))
            stage.rampIn(DeckFlow.rampFrames(rate: Double(held.speed)))
        }
        let take = min(keptCount - keptRead, room)
        for ch in 0..<2 { (outs[ch] + offset).update(from: kept[ch] + keptRead, count: take) }
        keptRead += take
        return take
    }

    // MARK: Engine restarts

    /// The engine restarted, resetting the time-pitch stage: the deck's clock is anchored again at
    /// `bus`, and what the stage held is handed to it again so nothing is lost.
    func restarted(atBus bus: Int64) {
        let piece = stage.clock.current
        replayLeft = min(historyFilled, Int((piece.latency * piece.rate).rounded()))
        replayAt = (historyAt - replayLeft + Self.historyCapacity) % Self.historyCapacity
        stage.clock.reset(atBus: bus, rate: piece.rate, latency: piece.latency)
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

    static func songFrame(_ ms: Double) -> Int64 { Int64((ms * deckRate / 1_000).rounded()) }
}

extension DeckState {
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

/// What a deck noticed since it was last asked.
enum DeckEvent {
    /// It moved on to the following song by itself, without a gap.
    case advanced(from: QueueItem, to: QueueItem)
    /// The song ended with nothing to follow, or stopped at its end.
    case ended(QueueItem, paused: Bool)
    /// The song could not play.
    case failed(QueueItem, PlayError)
}

/// One of the two players: a song loaded (and the one to follow it without a gap), playing
/// through Octo's sound processor, a speed and pitch stage and its level after it into the mixer.
@MainActor
final class Deck {
    let name: String
    let render: DeckRender
    let node: AVAudioSourceNode
    let timePitch = AVAudioUnitTimePitch()
    /// The deck's level after its time-pitch stage, where the blend's leave and fade ramps land on
    /// their exact frames; nil when the unit could not be made.
    let level: AVAudioUnitEffect?
    var processor: DeckProcessor { render.processor }

    private(set) var current: DeckSource?
    private(set) var following: DeckSource?
    /// Songs let go, kept a moment so the audio thread is never the one to free them.
    private var retired: [(source: DeckSource, at: Date)] = []
    private var seenAdvances = 0
    private var seenEndings = 0
    private var reportedFailure: ObjectIdentifier?
    /// The delay between the song time the audio thread reports and what is heard, in ms.
    var outputDelayMs: Double = 0

    init(name: String, sound: SoundProcessor?, keepActive: @escaping @Sendable () -> Bool) {
        self.name = name
        let stream = sound?.makeStream(keepActive: keepActive)
        let processor = DeckProcessor(keepActive: keepActive, tap: LiveTapFeed(), stage: stream.map(SoundStage.init))
        let render = DeckRender(processor: processor, stream: stream)
        self.render = render
        node = Self.sourceNode(render)
        level = OctoEffectUnit.node(OctoEffectUnit.deckLevel, name: "deck level", renderer: DeckLevelRender(render))
    }

    /// The node the engine pulls the deck's sound from. Made outside the main actor: the audio
    /// thread calls it.
    nonisolated private static func sourceNode(_ render: DeckRender) -> AVAudioSourceNode {
        let format = AVAudioFormat(standardFormatWithSampleRate: deckRate, channels: 2)!
        return AVAudioSourceNode(format: format) { _, _, frameCount, list in
            render.render(frameCount, list)
        }
    }

    // MARK: What it holds

    var item: QueueItem? { current?.item }
    var followingItem: QueueItem? { following?.item }

    /// Loads a song, at `ms`, replacing whatever was loaded.
    func load(_ source: DeckSource) {
        let old = (current, following)
        current = source
        following = nil
        reportedFailure = nil
        render.orders.withLock {
            $0.current = source
            $0.following = nil
        }
        retire(old.0)
        retire(old.1)
    }

    /// The song to follow this one without a gap, or none.
    func setFollowing(_ source: DeckSource?) {
        let old = following
        following = source
        render.orders.withLock { $0.following = source }
        retire(old)
    }

    /// Empties the deck.
    func clear() {
        let old = (current, following)
        current = nil
        following = nil
        render.orders.withLock {
            $0.current = nil
            $0.following = nil
            $0.playing = false
        }
        retire(old.0)
        retire(old.1)
    }

    func seek(toMs ms: Int) {
        current?.seek(toMs: ms)
    }

    // MARK: Playing

    var playWhenReady: Bool {
        get { render.orders.withLock { $0.playing } }
        set { render.orders.withLock { $0.playing = newValue } }
    }

    var pauseAtEndOfSong: Bool {
        get { render.orders.withLock { $0.pauseAtEnd } }
        set { render.orders.withLock { $0.pauseAtEnd = newValue } }
    }

    var volume: Float {
        get { render.orders.withLock { $0.volume } }
        set { render.orders.withLock { $0.volume = newValue } }
    }

    var startAfterMs: Int {
        get { render.orders.withLock { $0.startAfterMs } }
        set { render.orders.withLock { $0.startAfterMs = newValue } }
    }

    /// A spare loaded for a blend: silent and consuming nothing until the blend starts it.
    var parked: Bool {
        get { render.orders.withLock { $0.parked } }
        set { render.orders.withLock { $0.parked = newValue } }
    }

    /// The old song's frames kept through a pause past a handover are no longer wanted.
    func dropKept() { render.orders.withLock { $0.dropKept += 1 } }

    /// The pace: speed (tempo) and pitch as a multiple of the original.
    private(set) var pace = Pace(speed: 1, pitch: 1)

    func setPace(_ next: Pace) {
        pace = next
        timePitch.rate = next.speed
        timePitch.pitch = next.pitch > 0 ? 1200 * log2(next.pitch) : 0
        timePitch.bypass = next.speed == 1 && next.pitch == 1
        let latency = latencyFrames
        render.orders.withLock {
            $0.speed = next.speed
            $0.latencyFrames = latency
        }
    }

    /// The time-pitch stage's latency in bus frames, 0 while it is bypassed.
    var latencyFrames: Double { timePitch.bypass ? 0 : timePitch.latency * deckRate }

    /// The stage's latency read again (it is known once the engine runs).
    func refreshLatency() {
        let latency = latencyFrames
        render.orders.withLock { $0.latencyFrames = latency }
    }

    var skipSilence = false {
        didSet {
            current?.skipSilence.store(skipSilence, ordering: .relaxed)
            following?.skipSilence.store(skipSilence, ordering: .relaxed)
        }
    }

    var state: DeckState {
        guard let current else { return .idle }
        if current.failure != nil { return .ended }
        // Sound of an earlier song or from before a jump says nothing of this one yet.
        guard soundIsCurrent else { return .buffering }
        return DeckState(code: render.phaseCode.load(ordering: .relaxed))
    }

    /// Whether the last sound handed out was of this song, since its latest jump.
    private var soundIsCurrent: Bool {
        guard let current else { return false }
        return render.renderedSerial.load(ordering: .relaxed) == current.serial
            && render.renderedGeneration.load(ordering: .relaxed) == current.ring.generation.load(ordering: .relaxed)
    }

    var isPlaying: Bool { playWhenReady && state == .ready }

    /// Whether the song has enough sound waiting to start playing now, by the start-after rule.
    var hasEnoughToStart: Bool {
        guard let current, current.failure == nil else { return false }
        if state == .ready || current.decodedToEnd.load(ordering: .relaxed) { return true }
        let waiting = current.ring.waitingFrames
        guard waiting > 0 else { return false }
        if waiting >= current.ring.capacity * PCMRing.chunkFrames * 3 / 4 { return true }
        let bufferedUs = Int(Double(waiting) * 1_000_000 / deckRate)
        return OctoKit.hasEnoughToStart(bufferedUs: bufferedUs, speed: pace.speed, rebuffering: false,
                                        startAfterMs: current.isLocal ? 250 : startAfterMs)
    }

    /// Where the song is, as heard.
    var positionMs: Int {
        guard let current else { return 0 }
        guard soundIsCurrent else { return current.jumpMs.load(ordering: .relaxed) }
        let ms = Double(bitPattern: render.songMsBits.load(ordering: .relaxed))
        let latency = Double(processor.latency) * 1000 / deckRate + outputDelayMs * Double(pace.speed)
        return max(0, Int(ms - latency))
    }

    /// The song's length as the deck measured it, when it has.
    var durationMs: Int? { current?.measuredMs }

    // MARK: Events

    /// What happened since the last look.
    func poll() -> [DeckEvent] {
        var events: [DeckEvent] = []
        let (renderCurrent, renderFollowing, advances, endings, paused) = render.orders.withLock {
            ($0.current, $0.following, $0.advances, $0.endings, !$0.playing)
        }
        if advances != seenAdvances {
            seenAdvances = advances
            if let from = current, let to = renderCurrent, to !== from {
                current = to
                following = renderFollowing
                retire(from)
                reportedFailure = nil
                events.append(.advanced(from: from.item, to: to.item))
            }
        }
        if endings != seenEndings {
            seenEndings = endings
            if let current { events.append(.ended(current.item, paused: paused)) }
        }
        if let current, let failure = current.failure, reportedFailure != ObjectIdentifier(current) {
            reportedFailure = ObjectIdentifier(current)
            events.append(.failed(current.item, failure))
        }
        let now = Date()
        retired.removeAll { entry in now.timeIntervalSince(entry.at) > 3 }
        return events
    }

    private func retire(_ source: DeckSource?) {
        guard let source else { return }
        source.close()
        retired.append((source, Date()))
    }
}

/// The sound processor's stream as a deck's sound stage.
final class SoundStage: DeckSoundStage {
    let stream: SoundStream

    init(_ stream: SoundStream) {
        self.stream = stream
    }

    @discardableResult
    func configure(sampleRate: Double, channels: Int) -> Bool { stream.configure(sampleRate: sampleRate, channels: channels) }
    func flush() { stream.flush() }
    func process(_ input: [UnsafeMutablePointer<Float>], frames: Int, output: [UnsafeMutablePointer<Float>]) -> Int {
        stream.process(input, frames: frames, output: output)
    }
    func endOfStream(into output: [UnsafeMutablePointer<Float>]) -> Int { stream.endOfStream(into: output) }
    var latency: Int { stream.latency }
}

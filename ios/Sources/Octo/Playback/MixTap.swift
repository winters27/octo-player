#if DEBUG
import Foundation
import OctoKit
import os
import Synchronization

/// The debug build's tap on the phone's mix, for the device measures: each deck's sound after its
/// level unit (what reaches the main mixer), on bus frames, with every click's onset found there;
/// every message from main, every action and event of the session, and each engine restart, with
/// its bus frame. Nothing of it is in a release build, and it does nothing until switched on.
///
/// For each blend it logs one line on device ("mixtap: ..."): the start frame S the session
/// decided, the incoming song's first click and the outgoing song's click near S, each against
/// where the plan puts it, from a click track with a click every `periodMs` from 0. It also
/// writes the last seconds of both decks, aligned on bus frames, to Documents/MixTap.
///
/// The render thread only copies values: each entry carries the session's own kinds, and main
/// turns them into numbers. Its buffers are made on main before it is switched on.
final class MixTap: PhoneMixObserver, @unchecked Sendable {
    let enabled = Atomic<Bool>(false)

    struct Entry: Sendable, Equatable {
        var code: Int32
        /// The deck of an onset, or 1 when the limiter came on.
        var flag: Int32 = 0
        var input: BlendMainKind = .restarted
        var action: BlendActionKind = .startB
        var event: BlendEventKind = .proposed
        var cause: BlendCause = .none
        var bus: Int64
        var a: Int64 = 0
        var b: Int64 = 0
        var x: Double = 0
    }

    static let input: Int32 = 1
    static let action: Int32 = 2
    static let event: Int32 = 3
    static let onset: Int32 = 4
    static let limiting: Int32 = 5
    static let restart: Int32 = 6

    let log = BlendRing<Entry>(capacity: 8_192, filler: Entry(code: 0, bus: 0))

    /// Each deck's sound, mono, at the slot of its bus frame, for the last `seconds`: deck 0's
    /// slots first, then deck 1's.
    static let seconds = 20
    static let capacity = 48_000 * seconds
    private var audio: UnsafeMutablePointer<Float>?
    /// Per deck: whether the next loud frame is a click's onset, and the quiet frames since one.
    private let armed: UnsafeMutablePointer<Bool>
    private let quiet: UnsafeMutablePointer<Int>
    private var wasLimiting = false

    init() {
        armed = .allocate(capacity: 2)
        armed.initialize(repeating: true, count: 2)
        quiet = .allocate(capacity: 2)
        quiet.initialize(repeating: 0, count: 2)
    }

    deinit {
        armed.deallocate()
        quiet.deallocate()
        audio?.deallocate()
    }

    /// Main thread: makes the capture buffers and starts.
    func start() {
        if audio == nil {
            let buffer = UnsafeMutablePointer<Float>.allocate(capacity: Self.capacity * 2)
            buffer.initialize(repeating: 0, count: Self.capacity * 2)
            audio = buffer
        }
        enabled.store(true, ordering: .releasing)
    }

    func stop() { enabled.store(false, ordering: .releasing) }

    // MARK: Render thread

    func note(input: BlendMainInput, bus: Int64) {
        guard enabled.load(ordering: .acquiring) else { return }
        log.push(Entry(code: Self.input, input: input.kind, bus: bus, a: input.generation, b: Int64(input.entryMs),
                       x: input.kind == .paceChanged ? input.pace : input.arm.startMs))
    }

    func note(action: BlendAction, bus: Int64) {
        guard enabled.load(ordering: .acquiring) else { return }
        log.push(Entry(code: Self.action, action: action.kind, bus: bus, a: action.frame, b: action.value, x: action.ms))
    }

    func note(event: BlendEvent) {
        guard enabled.load(ordering: .acquiring) else { return }
        log.push(Entry(code: Self.event, event: event.kind, cause: event.cause, bus: event.frame, a: event.generation, x: event.ms))
    }

    func note(restartAt bus: Int64, replayBus: Double) {
        guard enabled.load(ordering: .acquiring) else { return }
        log.push(Entry(code: Self.restart, bus: bus, x: replayBus))
    }

    func cycle(bus: Int64, limiterLevel: Float, limiting: Bool) {
        guard enabled.load(ordering: .acquiring), limiting != wasLimiting else { return }
        wasLimiting = limiting
        log.push(Entry(code: Self.limiting, flag: limiting ? 1 : 0, bus: bus, x: Double(limiterLevel)))
    }

    /// A deck's frames after its level unit, the first at bus frame `firstBus`.
    func deck(_ index: Int, _ channels: UnsafeMutablePointer<UnsafeMutablePointer<Float>>, count: Int, frames: Int, firstBus: Int64) {
        guard enabled.load(ordering: .acquiring), index >= 0, index < 2, count > 0, let audio else { return }
        let into = audio + index * Self.capacity
        for i in 0..<frames {
            var sum: Float = 0
            for ch in 0..<count { sum += channels[ch][i] }
            let x = sum / Float(count)
            let bus = firstBus + Int64(i)
            into[Int(bus % Int64(Self.capacity))] = x
            let level = abs(x)
            if armed[index] && level > 0.25 {
                armed[index] = false
                quiet[index] = 0
                log.push(Entry(code: Self.onset, flag: Int32(index), bus: bus, a: Int64(index), x: Double(level)))
            } else if level < 0.02 {
                quiet[index] += 1
                // 50 ms of quiet before the next click counts.
                if quiet[index] > 2_400 { armed[index] = true }
            } else {
                quiet[index] = 0
            }
        }
    }

    // MARK: Main thread

    /// Copies the last seconds of both decks from bus frame `from`, interleaved, A's deck first.
    func capture(from: Int64, frames: Int) -> [Float] {
        guard let audio else { return [] }
        var out = [Float](repeating: 0, count: frames * 2)
        for i in 0..<frames {
            let slot = Int((from + Int64(i)) % Int64(Self.capacity))
            out[i * 2] = audio[slot]
            out[i * 2 + 1] = audio[Self.capacity + slot]
        }
        return out
    }
}

/// Main thread: reads the tap and reports each blend.
@MainActor
final class MixTapReport {
    static let shared = MixTapReport()
    private let logger = Logger(subsystem: "app.winters.octo", category: "mixtap")
    private var entries: [MixTap.Entry] = []
    /// Clicks of the test tracks come every this many ms from 0.
    var periodMs = 500.0
    /// The plans registered: generation, start, entry, rate and pace, and which deck played out.
    private var plans: [Int64: (startMs: Double, entryMs: Double, rate: Double, pace: Double, outgoing: Int)] = [:]
    /// Each kind's number in the written log, by its place among the kinds.
    private let actionCodes = Dictionary(uniqueKeysWithValues: BlendActionKind.allCases.enumerated().map { ($1, $0) })
    private let eventCodes = Dictionary(uniqueKeysWithValues: BlendEventKind.allCases.enumerated().map { ($1, $0) })
    private let causeCodes = Dictionary(uniqueKeysWithValues: BlendCause.allCases.enumerated().map { ($1, $0) })

    func planned(generation: Int64, startMs: Double, entryMs: Double, rate: Double, pace: Double, outgoing: Int) {
        plans[generation] = (startMs, entryMs, rate, pace, outgoing)
    }

    /// Every tick: takes what the render logged, and reports each blend once it ended.
    func poll(_ output: DeckOutput) {
        let tap = output.tap
        guard tap.enabled.load(ordering: .acquiring) else { return }
        while let entry = tap.log.pop() {
            entries.append(entry)
            switch entry.code {
            case MixTap.event:
                logger.info("mixtap: event \(entry.event.rawValue, privacy: .public) gen \(entry.a) frame \(entry.bus) ms \(entry.x)")
                if entry.event == .blendEnd || entry.event == .incomingNotReady || entry.event == .cancelled { report(entry.a, output) }
            case MixTap.action:
                logger.info("mixtap: action \(entry.action.rawValue, privacy: .public) at \(entry.a) value \(entry.b) ms \(entry.x) (render at \(entry.bus))")
            case MixTap.input:
                logger.info("mixtap: from main \(Self.inputCode(entry.input)) gen \(entry.a) at \(entry.bus)")
            case MixTap.limiting:
                logger.info("mixtap: limiter \(entry.flag == 1 ? "on" : "off", privacy: .public) at \(entry.bus) level \(entry.x)")
            case MixTap.restart:
                logger.info("mixtap: restart at \(entry.bus) replay \(entry.x)")
            default:
                break
            }
        }
        if entries.count > 20_000 { entries.removeFirst(entries.count - 10_000) }
    }

    /// One line for a blend: S, the incoming song's first click after it and the outgoing song's
    /// first click at or after it, each against the plan, in ms (positive: late).
    private func report(_ generation: Int64, _ output: DeckOutput) {
        guard let plan = plans[generation],
              let s = entries.last(where: { $0.code == MixTap.action && $0.action == .startB })?.a else {
            logger.info("mixtap: gen \(generation) ended with no start")
            return
        }
        let incoming = 1 - plan.outgoing
        let period = periodMs
        let firstB = entries.first { $0.code == MixTap.onset && Int($0.a) == incoming && $0.bus >= s - 480 }?.bus
        let firstA = entries.first { $0.code == MixTap.onset && Int($0.a) == plan.outgoing && $0.bus >= s - 480 }?.bus
        let clickB = (plan.entryMs / period).rounded(.up) * period
        let clickA = (plan.startMs / period).rounded(.up) * period
        let planB = Double(s) + (clickB - plan.entryMs) / (plan.rate * plan.pace) * 48
        let planA = Double(s) + (clickA - plan.startMs) / plan.pace * 48
        let offB = firstB.map { (Double($0) - planB) / 48 }
        let offA = firstA.map { (Double($0) - planA) / 48 }
        let line = "mixtap: gen \(generation) S \(s) B click \(firstB.map(String.init) ?? "none") off \(offB.map { String(format: "%.2f", $0) } ?? "-") ms, "
            + "A click \(firstA.map(String.init) ?? "none") off \(offA.map { String(format: "%.2f", $0) } ?? "-") ms, "
            + "B to A \(firstA.flatMap { a in firstB.map { b in String(format: "%.2f", (Double(b - a) - (planB - planA)) / 48) } } ?? "-") ms, "
            + "rate \(plan.rate) pace \(plan.pace)"
        logger.info("\(line, privacy: .public)")
        write(generation, line, from: s - 96_000, output)
    }

    private static func inputCode(_ kind: BlendMainKind) -> Int {
        switch kind {
        case .arm: 0
        case .disarm: 1
        case .failed: 2
        case .followUp: 3
        case .paceChanged: 4
        case .outgoingStopped: 5
        case .restarted: 6
        }
    }

    /// The number an entry's kind is written as: its place among its kinds.
    private func kindCode(_ entry: MixTap.Entry) -> Int {
        switch entry.code {
        case MixTap.input: Self.inputCode(entry.input)
        case MixTap.action: actionCodes[entry.action] ?? 0
        case MixTap.event: eventCodes[entry.event] ?? 0
        default: Int(entry.flag)
        }
    }

    /// Writes both decks from 2 s before S for up to 12 s, and the lines, to Documents/MixTap.
    func write(_ generation: Int64, _ line: String, from: Int64, _ output: DeckOutput) {
        let frames = min(MixTap.capacity, 48_000 * 12)
        let samples = output.tap.capture(from: max(0, from), frames: frames)
        guard let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let folder = documents.appendingPathComponent("MixTap", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let stamp = Int(Date().timeIntervalSince1970)
        let data = samples.withUnsafeBufferPointer { Data(buffer: $0) }
        try? data.write(to: folder.appendingPathComponent("gen\(generation)-\(stamp)-from\(max(0, from))-48k-2ch-f32.raw"))
        let lines = entries.map { entry in
            let b = entry.code == MixTap.event ? Int64(causeCodes[entry.cause] ?? 0) : entry.b
            return "\(entry.code) \(kindCode(entry)) bus \(entry.bus) a \(entry.a) b \(b) x \(entry.x)"
        }.joined(separator: "\n")
        try? (line + "\n" + lines).write(to: folder.appendingPathComponent("gen\(generation)-\(stamp).txt"), atomically: true, encoding: .utf8)
    }

    /// Writes what the tap holds now.
    func dump(_ output: DeckOutput) {
        let now = output.mix.nowBus
        write(-1, "mixtap: dump at \(now)", from: max(0, now - Int64(MixTap.capacity) + 4_800), output)
    }
}
#endif

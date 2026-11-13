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
final class MixTap: @unchecked Sendable {
    let enabled = Atomic<Bool>(false)

    struct Entry: Sendable, Equatable {
        var code: Int32
        var kind: Int32
        var bus: Int64
        var a: Int64
        var b: Int64
        var x: Double
    }

    static let input: Int32 = 1
    static let action: Int32 = 2
    static let event: Int32 = 3
    static let onset: Int32 = 4
    static let limiting: Int32 = 5

    let log = BlendRing<Entry>(capacity: 8_192, filler: Entry(code: 0, kind: 0, bus: 0, a: 0, b: 0, x: 0))

    /// Each deck's sound, mono, at the slot of its bus frame, for the last `seconds`.
    static let seconds = 20
    static let capacity = 48_000 * seconds
    private var audio: [UnsafeMutablePointer<Float>] = []
    private var armed = [true, true]
    private var quiet = [0, 0]
    private var wasLimiting = false

    /// Main thread: makes the capture buffers and starts.
    func start() {
        if audio.isEmpty {
            audio = (0..<2).map { _ in
                let buffer = UnsafeMutablePointer<Float>.allocate(capacity: Self.capacity)
                buffer.initialize(repeating: 0, count: Self.capacity)
                return buffer
            }
        }
        enabled.store(true, ordering: .releasing)
    }

    func stop() { enabled.store(false, ordering: .releasing) }

    // MARK: Render thread

    func note(input: BlendMainInput, bus: Int64) {
        guard enabled.load(ordering: .acquiring) else { return }
        let kind: Int32 = switch input.kind {
        case .arm: 0
        case .disarm: 1
        case .failed: 2
        case .followUp: 3
        case .paceChanged: 4
        case .outgoingStopped: 5
        case .restarted: 6
        }
        log.push(Entry(code: Self.input, kind: kind, bus: bus, a: input.generation, b: Int64(input.entryMs), x: input.kind == .paceChanged ? input.pace : input.arm.startMs))
    }

    func note(action: BlendAction, bus: Int64) {
        guard enabled.load(ordering: .acquiring) else { return }
        log.push(Entry(code: Self.action, kind: Int32(BlendActionKind.allCases.firstIndex(of: action.kind) ?? 0), bus: bus,
                       a: action.frame, b: action.value, x: action.ms))
    }

    func note(event: BlendEvent) {
        guard enabled.load(ordering: .acquiring) else { return }
        log.push(Entry(code: Self.event, kind: Int32(BlendEventKind.allCases.firstIndex(of: event.kind) ?? 0), bus: event.frame,
                       a: event.generation, b: Int64(BlendCause.allCases.firstIndex(of: event.cause) ?? 0), x: event.ms))
    }

    func cycle(bus: Int64, limiterLevel: Float, limiting: Bool) {
        guard enabled.load(ordering: .acquiring), limiting != wasLimiting else { return }
        wasLimiting = limiting
        log.push(Entry(code: Self.limiting, kind: limiting ? 1 : 0, bus: bus, a: 0, b: 0, x: Double(limiterLevel)))
    }

    /// A deck's frames after its level unit, the first at bus frame `firstBus`.
    func deck(_ index: Int, _ channels: [UnsafeMutablePointer<Float>], frames: Int, firstBus: Int64) {
        guard enabled.load(ordering: .acquiring), index >= 0, index < 2, audio.count == 2, !channels.isEmpty else { return }
        let into = audio[index]
        for i in 0..<frames {
            var sum: Float = 0
            for ch in 0..<channels.count { sum += channels[ch][i] }
            let x = sum / Float(channels.count)
            let bus = firstBus + Int64(i)
            into[Int(bus % Int64(Self.capacity))] = x
            let level = abs(x)
            if armed[index] && level > 0.25 {
                armed[index] = false
                quiet[index] = 0
                log.push(Entry(code: Self.onset, kind: Int32(index), bus: bus, a: Int64(index), b: 0, x: Double(level)))
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
        guard audio.count == 2 else { return [] }
        var out = [Float](repeating: 0, count: frames * 2)
        for i in 0..<frames {
            let slot = Int((from + Int64(i)) % Int64(Self.capacity))
            out[i * 2] = audio[0][slot]
            out[i * 2 + 1] = audio[1][slot]
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

    func planned(generation: Int64, startMs: Double, entryMs: Double, rate: Double, pace: Double, outgoing: Int) {
        plans[generation] = (startMs, entryMs, rate, pace, outgoing)
    }

    /// Every tick: takes what the render logged, and reports each blend once it ended.
    func poll(_ mix: MixRender) {
        guard mix.tap.enabled.load(ordering: .acquiring) else { return }
        while let entry = mix.tap.log.pop() {
            entries.append(entry)
            if entry.code == MixTap.event {
                let kind = BlendEventKind.allCases[Int(entry.kind)]
                logger.info("mixtap: event \(kind.rawValue, privacy: .public) gen \(entry.a) frame \(entry.bus) ms \(entry.x)")
                if kind == .blendEnd || kind == .incomingNotReady || kind == .cancelled { report(entry.a, mix) }
            } else if entry.code == MixTap.action {
                let kind = BlendActionKind.allCases[Int(entry.kind)]
                logger.info("mixtap: action \(kind.rawValue, privacy: .public) at \(entry.a) value \(entry.b) ms \(entry.x) (render at \(entry.bus))")
            } else if entry.code == MixTap.input {
                logger.info("mixtap: from main \(entry.kind) gen \(entry.a) at \(entry.bus)")
            } else if entry.code == MixTap.limiting {
                logger.info("mixtap: limiter \(entry.kind == 1 ? "on" : "off", privacy: .public) at \(entry.bus) level \(entry.x)")
            }
        }
        if entries.count > 20_000 { entries.removeFirst(entries.count - 10_000) }
    }

    /// One line for a blend: S, the incoming song's first click after it and the outgoing song's
    /// first click at or after it, each against the plan, in ms (positive: late).
    private func report(_ generation: Int64, _ mix: MixRender) {
        let startB = Int32(BlendActionKind.allCases.firstIndex(of: .startB) ?? 0)
        guard let plan = plans[generation],
              let s = entries.last(where: { $0.code == MixTap.action && $0.kind == startB })?.a else {
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
        write(generation, line, from: s - 96_000, mix)
    }

    /// Writes both decks from 2 s before S for up to 12 s, and the lines, to Documents/MixTap.
    func write(_ generation: Int64, _ line: String, from: Int64, _ mix: MixRender) {
        let frames = min(MixTap.capacity, 48_000 * 12)
        let samples = mix.tap.capture(from: max(0, from), frames: frames)
        guard let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let folder = documents.appendingPathComponent("MixTap", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let stamp = Int(Date().timeIntervalSince1970)
        let data = samples.withUnsafeBufferPointer { Data(buffer: $0) }
        try? data.write(to: folder.appendingPathComponent("gen\(generation)-\(stamp)-from\(max(0, from))-48k-2ch-f32.raw"))
        let lines = entries.map { "\($0.code) \($0.kind) bus \($0.bus) a \($0.a) b \($0.b) x \($0.x)" }.joined(separator: "\n")
        try? (line + "\n" + lines).write(to: folder.appendingPathComponent("gen\(generation)-\(stamp).txt"), atomically: true, encoding: .utf8)
    }

    /// Writes what the tap holds now.
    func dump(_ mix: MixRender) {
        let now = mix.nowBus
        write(-1, "mixtap: dump at \(now)", from: max(0, now - Int64(MixTap.capacity) + 4_800), mix)
    }
}
#endif

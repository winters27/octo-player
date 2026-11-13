import Foundation
import Testing
@testable import OctoKit

/// Every case in automix-vectors.json, the file every engine's copy of the automix code runs
/// too. The songs are rebuilt from their layers (SyntheticSong), scouted, analyzed and planned,
/// and every result must match within the file's tolerances. A failure lists every case that is
/// off, not only the first.
@Suite struct AutomixVectorsTest {
    @Test func everyVectorMatches() throws {
        let vectors = try #require(JSONSerialization.jsonObject(with: Data(automixVectorsJSON.utf8)) as? [String: Any])
        let failures = check(vectors)
        #expect(failures.isEmpty, "\(failures.joined(separator: "\n"))")
    }

    @Test func theFileHoldsEveryPartChecked() throws {
        let vectors = try #require(JSONSerialization.jsonObject(with: Data(automixVectorsJSON.utf8)) as? [String: Any])
        for part in ["sections", "plans", "gains", "introGains", "filters", "steppedLowPass", "biquads", "tempoTrust", "rates", "dropCuts"] {
            #expect(((vectors[part] as? [Any])?.count ?? 0) > 0, "\(part)")
        }
        let plans = vectors["plans"] as! [[String: Any]]
        #expect(plans.contains { ($0["expected"] as! [String: Any]).str("kind") == "intro" })
        #expect(plans.contains { $0["headBodyLevelDb"] is NSNumber })
        #expect((vectors["sections"] as! [[String: Any]]).contains { $0["bodyLevelDb"] is NSNumber })
    }

    static let filterVectorSamples = 512

    func check(_ vectors: [String: Any]) -> [String] {
        var failures: [String] = []
        let tol = vectors["tolerances"] as! [String: Any]
        func t(_ name: String) -> Double { (tol[name] as! NSNumber).doubleValue }
        func near(_ what: String, _ expected: Double?, _ actual: Double?, _ within: Double) {
            guard let expected, let actual else {
                if (expected == nil) != (actual == nil) {
                    failures.append("\(what): expected \(String(describing: expected)), got \(String(describing: actual))")
                }
                return
            }
            if abs(expected - actual) > within { failures.append("\(what): expected \(expected), got \(actual) (within \(within))") }
        }

        let songs = (vectors["songs"] as! [String: Any]).mapValues { songOf($0 as! [String: Any]) }
        var analyses: [String: SectionAnalysis] = [:]
        // Heads measured against a whole song's body, by song and body level.
        var againstBody: [String: SectionAnalysis] = [:]

        // Every section is rendered and analyzed at once, each on a thread of its own; the checks
        // below run in order.
        let sections = vectors["sections"] as! [[String: Any]]
        let asked = sections.map { s in
            (song: songs[s.str("song")]!, tail: s.str("part") == "tail", tag: s.numOrNil("tagBpm"), bodyDb: s.numOrNil("bodyLevelDb"),
             fromMs: s.num("fromMs"), toMs: s.num("toMs"))
        }
        let made = Analyses(asked.count)
        DispatchQueue.concurrentPerform(iterations: asked.count) { i in
            let a = asked[i]
            let envelope = a.song.envelope(a.fromMs, a.toMs)
            made.put(i, a.tail ? analyzeTail(envelope, tagBpm: a.tag) : analyzeHead(envelope, tagBpm: a.tag, bodyLevelDb: a.bodyDb))
        }
        let analyzed = made.all

        for (index, section) in sections.enumerated() {
            let name = section.str("song")
            let part = section.str("part")
            let tag = section.numOrNil("tagBpm")
            let bodyDb = section.numOrNil("bodyLevelDb")
            let what = if let tag { "\(name) \(part) tagged \(tag)" } else if let bodyDb { "\(name) \(part) against a body of \(bodyDb) dB" } else { "\(name) \(part)" }
            let analysis = analyzed[index]
            if tag == nil && bodyDb == nil { analyses["\(name)/\(part)"] = analysis }
            if tag == nil, let bodyDb, part == "head" { againstBody["\(name)@\(bodyDb)"] = analysis }
            let envelope = analysis.envelope
            if envelope.size != section.int("hops") { failures.append("\(what): \(envelope.size) hops") }
            if envelope.startMs != section.int("startMs") { failures.append("\(what): starts at \(envelope.startMs)") }
            for probe in section["probes"] as! [[String: Any]] {
                let i = probe.int("index")
                func at(_ values: [Float]) -> Double? { values.indices.contains(i) ? Double(values[i]) : nil }
                near("\(what) hop \(i) db", probe.num("db"), at(envelope.db), t("db"))
                near("\(what) hop \(i) lowDb", probe.num("lowDb"), at(envelope.lowDb), t("db"))
                near("\(what) hop \(i) onset", probe.num("onset"), at(envelope.onset), t("onset"))
                near("\(what) hop \(i) lowOnset", probe.num("lowOnset"), at(envelope.lowOnset), t("onset"))
            }
            let expected = section["features"] as! [String: Any]
            let f = analysis.features
            near("\(what) bodyDb", expected.num("bodyDb"), f.bodyDb, t("db"))
            near("\(what) gateDb", expected.num("gateDb"), f.gateDb, t("db"))
            near("\(what) soundStartMs", expected.numOrNil("soundStartMs"), f.soundStartMs.map(Double.init), t("ms"))
            near("\(what) soundEndMs", expected.numOrNil("soundEndMs"), f.soundEndMs.map(Double.init), t("ms"))
            near("\(what) outroStartMs", expected.numOrNil("outroStartMs"), f.outroStartMs.map(Double.init), t("ms"))
            near("\(what) introEndMs", expected.numOrNil("introEndMs"), f.introEndMs.map(Double.init), t("ms"))
            let boundaries = (expected["boundariesMs"] as! [NSNumber]).map(\.doubleValue)
            if boundaries.count != f.boundariesMs.count {
                failures.append("\(what) boundaries: expected \(boundaries), got \(f.boundariesMs)")
            } else {
                for (e, a) in zip(boundaries, f.boundariesMs) { near("\(what) boundary", e, Double(a), t("ms")) }
            }
            if let e = expected["tempo"] as? [String: Any] {
                if let a = f.tempo {
                    near("\(what) bpm", e.num("bpm"), a.bpm, t("bpm"))
                    near("\(what) confidence", e.num("confidence"), a.confidence, e.num("confidence") * t("confidenceShare"))
                    near("\(what) consistency", e.num("consistency"), a.consistency, 0.02)
                    if e.bool("steady") != a.steady { failures.append("\(what): steady \(a.steady)") }
                    if e.bool("confident") != a.confident { failures.append("\(what): confident \(a.confident)") }
                    near("\(what) beatMs", e.num("beatMs"), a.beatMs, t("ms") / 10)
                    near("\(what) firstBeatMs", e.num("firstBeatMs"), a.firstBeatMs, t("ms"))
                    near("\(what) downbeatMs", e.num("downbeatMs"), a.downbeatMs, t("ms"))
                } else {
                    failures.append("\(what): expected a tempo, got none")
                }
            } else if f.tempo != nil {
                failures.append("\(what): expected no tempo, got \(f.tempo!)")
            }
        }

        for c in vectors["plans"] as! [[String: Any]] {
            let name = c.str("name")
            let settings = c["settings"] as! [String: Any]
            let headBody = c.numOrNil("headBodyLevelDb")
            let head: SectionAnalysis? = if c.bool("headMissing") {
                nil
            } else if let headBody {
                againstBody["\(c.str("b"))@\(headBody)"]!
            } else {
                analyses["\(c.str("b"))/head"]!
            }
            let plan = planTransition(
                current: fadeSongOf(c["current"] as! [String: Any]),
                next: fadeSongOf(c["next"] as! [String: Any]),
                tail: c.bool("tailMissing") ? nil : analyses["\(c.str("a"))/tail"]!,
                head: head,
                settings: AutomixSettings(maxOverlapMs: settings.int("maxOverlapMs"), longestIntroMs: settings.int("longestIntroMs"),
                                          smart: settings.bool("smart"), filterSweeps: settings.bool("filterSweeps"),
                                          beatMatch: settings.bool("beatMatch")),
                context: contextOf(c["context"] as! [String: Any]))
            let e = c["expected"] as! [String: Any]
            let what = "plan \(name)"
            if e.str("kind") != plan.kind.rawValue.lowercased() { failures.append("\(what): kind \(plan.kind), expected \(e.str("kind"))") }
            near("\(what) startMs", e.num("startMs"), Double(plan.startMs), t("ms"))
            near("\(what) entryMs", e.num("entryMs"), Double(plan.entryMs), t("ms"))
            near("\(what) overlapMs", e.num("overlapMs"), Double(plan.overlapMs), t("ms"))
            near("\(what) k", e.num("k"), plan.k, t("curve"))
            near("\(what) filterStrength", e.num("filterStrength"), plan.filterStrength, t("curve"))
            near("\(what) beatMatchRate", e.numOrNil("beatMatchRate"), plan.beatMatchRate, t("rate") * 10)
            near("\(what) beatMs", e.numOrNil("beatMs"), plan.beatMs, t("ms") / 10)
            near("\(what) beatAnchorMs", e.numOrNil("beatAnchorMs"), plan.beatAnchorMs, t("ms"))
            near("\(what) headroomDb", e.num("headroomDb"), plan.headroomDb, t("curve"))
            if e.bool("late") != plan.late { failures.append("\(what): late \(plan.late)") }
            near("\(what) holdShare", e.num("holdShare"), plan.holdShare, t("curve"))
            near("\(what) riseShare", e.num("riseShare"), plan.riseShare, t("curve"))
            near("\(what) introLevel", e.num("introLevel"), plan.introLevel, t("level"))
            near("\(what) latestEndMs", e.num("latestEndMs"), Double(plan.latestEndMs), t("ms"))
            if e.int("lateMoveBars") != plan.lateMoveBars { failures.append("\(what): lateMoveBars \(plan.lateMoveBars), expected \(e.int("lateMoveBars"))") }
            near("\(what) bothHeardStartMs", e.num("bothHeardStartMs"), Double(plan.bothHeardStartMs), t("ms"))
            near("\(what) bothHeardEndMs", e.num("bothHeardEndMs"), Double(plan.bothHeardEndMs), t("ms"))
            near("\(what) lockMs", e.num("lockMs"), plan.lockMs, t("ms") / 10)
            near("\(what) driftGap", e.num("driftGap"), plan.driftGap, t("rate"))
            near("\(what) driftAtEndMs", e.num("driftAtEndMs"), plan.driftAtEndMs, t("ms") / 10)
            near("\(what) dropCutAtHalfSpeedMs", e.numOrNil("dropCutAtHalfSpeedMs"), dropCutAtMs(plan, 0.5, 0), t("ms"))
            if e.str("reason") != plan.reason { failures.append("\(what): reason \"\(plan.reason)\", expected \"\(e.str("reason"))\"") }
        }

        for g in vectors["gains"] as! [[String: Any]] {
            let time = g.num("t"), k = g.num("k")
            near("out gain at \(time) k \(k)", g.num("out"), automixOutGain(time, k), t("curve"))
            near("in gain at \(time) k \(k)", g.num("in"), automixInGain(time, k), t("curve"))
        }
        for g in vectors["introGains"] as! [[String: Any]] {
            let time = g.num("t"), hold = g.num("holdShare"), rise = g.num("riseShare"), level = g.num("level")
            near("intro out gain at \(time) holding to \(hold)", g.num("out"), introOutGain(time, hold), t("curve"))
            near("intro in gain at \(time) rising to \(rise) at \(level)", g.num("in"), introInGain(time, rise, hold, level), t("curve"))
        }
        for f in vectors["filters"] as! [[String: Any]] {
            let time = f.num("t"), s = f.num("strength")
            near("low-pass at \(time) strength \(s)", f.num("outgoingLowPassHz"), outgoingLowPassHz(time, s), f.num("outgoingLowPassHz") * t("hzShare"))
            near("bass cut at \(time) strength \(s)", f.num("outgoingHighPassHz"), outgoingHighPassHz(time, s), f.num("outgoingHighPassHz") * t("hzShare"))
            near("incoming high-pass at \(time) strength \(s)", f.num("incomingHighPassHz"), incomingHighPassHz(time, s),
                 f.num("incomingHighPassHz") * t("hzShare"))
        }
        for f in vectors["steppedLowPass"] as! [[String: Any]] {
            let beats = (f["beats"] as! [NSNumber]).map(\.doubleValue)
            let actual = steppedOutgoingLowPassHz(f.num("t"), f.num("strength"), beats, f.num("glide"))
            near("stepped low-pass at \(f.num("t"))", f.num("hz"), actual, f.num("hz") * t("hzShare"))
        }
        for f in vectors["biquads"] as! [[String: Any]] {
            let type = f.str("type"), rate = f.int("rate"), hz = f.num("hz")
            let what = "\(type) at \(hz) Hz, \(rate) Hz"
            let filter = biquadOf(type, rate, hz)
            let expected = (f["coefficients"] as! [NSNumber]).map(\.doubleValue)
            for (i, c) in [filter.b0, filter.b1, filter.b2, filter.a1, filter.a2].enumerated() {
                near("\(what) coefficient \(i)", expected[i], c, t("sample"))
            }
            let impulse = impulseOf(filter)
            for (i, x) in (f["impulse"] as! [NSNumber]).enumerated() { near("\(what) impulse \(i)", x.doubleValue, impulse[i], t("sample")) }
            let sine = sineOf(biquadOf(type, rate, hz), rate)
            for (i, x) in (f["sine"] as! [NSNumber]).enumerated() { near("\(what) sine \(i)", x.doubleValue, sine[i], t("sample")) }
        }
        for c in vectors["tempoTrust"] as! [[String: Any]] {
            let confidence = c.num("confidence"), steady = c.bool("steady")
            let actual = trusted(confidence, steady)
            if actual != c.bool("confident") { failures.append("tempo scoring \(confidence), steady \(steady): confident \(actual)") }
        }
        for r in vectors["rates"] as! [[String: Any]] {
            let actual = beatMatchRateAt(r.num("rate"), r.num("sinceEntryMs"), r.num("overlapMs"))
            near("rate at \(r.num("sinceEntryMs"))", r.num("value"), actual, t("curve"))
        }
        for d in vectors["dropCuts"] as! [[String: Any]] {
            var plan = fixedCrossfadePlan(lenA: 200_000, length: 8_000, why: "test")
            plan.bothHeardStartMs = d.int("bothHeardStartMs")
            plan.bothHeardEndMs = d.int("bothHeardEndMs")
            plan.lockMs = d.num("lockMs")
            plan.driftGap = d.num("driftGap")
            plan.holdShare = d.num("holdEndMs") / 8_000
            let speed = d.num("speed"), from = d.num("fromMs")
            let what = "drop cut over \(plan.bothHeardStartMs) to \(plan.bothHeardEndMs), hold to \(d.num("holdEndMs")), lock \(plan.lockMs), gap \(plan.driftGap), speed \(speed) from \(from)"
            near(what, d.numOrNil("cutAtMs"), dropCutAtMs(plan, speed, from), t("curve"))
        }
        return failures
    }

    /// Whether a 120 BPM grid with this period score, steady or not, is trusted for bar lock.
    func trusted(_ confidence: Double, _ steady: Bool) -> Bool {
        Tempo(bpm: 120, confidence: confidence, consistency: 1, beatMs: 500, firstBeatMs: 0, downbeatMs: 0, steady: steady).confident
    }

    func biquadOf(_ type: String, _ rate: Int, _ hz: Double) -> Biquad {
        type == "lowPass" ? Biquad.lowPass(sampleRate: rate, hz: hz) : Biquad.highPass(sampleRate: rate, hz: hz)
    }

    /// The filter's answer to a 1 at sample 0.
    func impulseOf(_ filter: Biquad) -> [Double] { (0..<Self.filterVectorSamples).map { filter.process($0 == 0 ? 1 : 0) } }

    /// The filter's answer to 0.5 * sin(2 pi 1000 n / rate).
    func sineOf(_ filter: Biquad, _ rate: Int) -> [Double] {
        (0..<Self.filterVectorSamples).map { filter.process(0.5 * sin(2 * Double.pi * 1_000 * Double($0) / Double(rate))) }
    }

    func songOf(_ o: [String: Any]) -> SyntheticSong {
        SyntheticSong(rate: o.int("rate"), channels: o.int("channels"), lengthMs: o.int("lengthMs"),
                      layers: (o["layers"] as! [[String: Any]]).map { l in
                          SongLayer(l.str("kind"), l.num("fromMs"), l.num("toMs"), hz: l.num("hz"), db: l.num("db"), endDb: l.num("endDb"),
                                    bpm: l.numOrNil("bpm") ?? 0, firstMs: l.numOrNil("firstMs") ?? 0,
                                    every: (l["every"] as? NSNumber)?.intValue ?? 1, decayMs: l.numOrNil("decayMs") ?? 0)
                      })
    }

    func contextOf(_ o: [String: Any]) -> TransitionContext {
        TransitionContext(nowMs: o.int("nowMs"), playedMs: o.int("playedMs"), repeatOne: o.bool("repeatOne"),
                          stopAtEndOfSong: o.bool("stopAtEndOfSong"), pace: o.num("pace"), skipSilence: o.bool("skipSilence"),
                          currentGenre: o["currentGenre"] as? String, nextGenre: o["nextGenre"] as? String,
                          bodyLevelDb: o.numOrNil("bodyLevelDb"), tempoPrior: o.numOrNil("tempoPrior"),
                          lockAtPace: (o["lockAtPace"] as? NSNumber)?.boolValue ?? false,
                          incomingGainDb: o.num("incomingGainDb"))
    }

    func fadeSongOf(_ o: [String: Any]) -> FadeSong {
        FadeSong(albumId: o["albumId"] as? String, albumOrder: (o["albumOrder"] as? NSNumber)?.intValue, durationMs: o.int("durationMs"))
    }
}

/// Analyses made on several threads at once, each in its own place.
private final class Analyses: @unchecked Sendable {
    private let lock = NSLock()
    private var made: [SectionAnalysis?]

    init(_ count: Int) { made = Array(repeating: nil, count: count) }

    func put(_ index: Int, _ analysis: SectionAnalysis) { lock.withLock { made[index] = analysis } }

    var all: [SectionAnalysis] { lock.withLock { made.map { $0! } } }
}

fileprivate extension Dictionary where Key == String, Value == Any {
    func str(_ key: String) -> String { self[key] as! String }
    func num(_ key: String) -> Double { (self[key] as! NSNumber).doubleValue }
    func int(_ key: String) -> Int { (self[key] as! NSNumber).intValue }
    func bool(_ key: String) -> Bool { (self[key] as! NSNumber).boolValue }
    func numOrNil(_ key: String) -> Double? { (self[key] as? NSNumber)?.doubleValue }
}

import Foundation
import Testing
@testable import OctoKit

/// Runs blend-session-vectors.json (embedded in BlendSessionVectorsData.swift), the file the shared
/// Kotlin session runs too; its format is described at the top of the Kotlin
/// BlendSessionVectorsTest. Each vector runs its steps (preludes expanded) on a fresh session and
/// commit word; after every step, all the actions the session gives and all the events it lets out
/// at the head of the latest advance must equal the step's lists, in order. Kinds, inputs, states
/// and causes are the Swift case names. A failure lists every step that is off, not only the first.
@Suite struct BlendSessionVectorsTest {
    var file: [String: Any] { try! JSONSerialization.jsonObject(with: Data(blendSessionVectorsJSON.utf8)) as! [String: Any] }

    @Test func everyScriptGivesItsActionsAndEvents() {
        let file = self.file
        let profiles = file.object("profiles")
        let preludes = file.object("preludes")
        var failures: [String] = []
        var names = Set<String>()
        for vector in file.list("vectors") {
            let name = vector.text("name")
            if !names.insert(name).inserted { failures.append("\(name): the name is used twice") }
            var steps: [[String: Any]] = []
            expand(vector.list("steps"), preludes, into: &steps)
            failures += run(name, profiles.object(vector.text("profile")), steps)
        }
        #expect(names.count >= 40)
        #expect(failures.isEmpty, "\(failures.joined(separator: "\n"))")
    }

    /// The runner itself: a step whose action is one frame off, and one that waits for an event
    /// that never comes, are both reported.
    @Test func aWrongExpectationIsReported() throws {
        let steps = [
            #"{"op": "advance", "to": 360000, "head": 336000, "aSongMs": 177500}"#,
            #"{"op": "arm", "gen": 1, "startMs": 180000, "overlapMs": 2000, "latestEndMs": 185000, "outgoingEndMs": 185250}"#,
            #"{"op": "post", "deck": "B", "input": "ready", "frame": 380000}"#,
            #"{"op": "post", "deck": "A", "input": "startAt", "frame": 451200, "value": 480000}"#,
            #"{"op": "advance", "to": 456000, "head": 446400, "aSongMs": 179500, "actions": [{"kind": "startB", "frame": 480001}, {"kind": "limiterOn", "frame": 480000}]}"#,
            #"{"op": "advance", "to": 460800, "head": 451200, "aSongMs": 179600, "events": [{"kind": "proposed", "gen": 1, "frame": 480000}]}"#,
        ].map { try! JSONSerialization.jsonObject(with: Data($0.utf8)) as! [String: Any] }
        let failures = run("wrong", file.object("profiles").object("android"), steps)
        #expect(failures.count == 2, "\(failures.joined(separator: "\n"))")
    }

    @Test func armTimes() {
        for c in file.list("armAt") {
            let at = blendArmAtMs(startMs: c.num("startMs"), preRollLeadMs: c.num("preRollLeadMs"), proposalLeadMs: c.num("proposalLeadMs"),
                                  readyLeadMs: c.num("readyLeadMs"), speed: c.num("speed"))
            #expect(abs(at - c.num("expect")) <= 1e-9, "at \(c.num("speed"))x: \(at)")
        }
    }

    @Test func replanNow() {
        for c in file.list("replanNow") {
            let now = blendReplanNowMs(outgoingAtHeadMs: c.num("outgoingAtHeadMs"), proposalLeadFrames: c.int64("proposalLeadFrames"),
                                       sampleRate: Int(c.int64("sampleRate")), speed: c.num("speed"))
            #expect(abs(now - c.num("expect")) <= 1e-9)
        }
    }

    @Test func easing() {
        for c in file.list("ease") {
            let rate = blendIncomingRate(rate: c.num("rate"), incomingMs: c.num("incomingMs"), entryMs: c.num("entryMs"), easeFromMs: c.num("easeFromMs"))
            #expect(abs(rate - c.num("expect")) <= 1e-9, "at \(c.num("incomingMs")): \(rate)")
        }
    }

    @Test func dropCuts() {
        for c in file.list("dropCut") {
            let at = blendDropCutAtMs(bothHeardStartMs: c.num("bothHeardStartMs"), bothHeardEndMs: c.num("bothHeardEndMs"),
                                      holdEndMs: c.num("holdEndMs"), lockMs: c.num("lockMs"), driftGap: c.num("driftGap"),
                                      pace: c.num("pace"), fromMs: c.num("fromMs"))
            let expect = (c.present("expect") as? NSNumber)?.doubleValue
            if let expect {
                #expect(at.map { abs($0 - expect) <= 1e-9 } == true, "from \(c.num("fromMs")): \(String(describing: at))")
            } else {
                #expect(at == nil, "from \(c.num("fromMs")): \(String(describing: at))")
            }
        }
    }

    @Test func commitWords() {
        for c in file.list("commitWords") {
            let state = CommitState.allCases.first { "\($0)" == c.text("state") }!
            let word = commitWord(generation: c.int64("gen"), state: state)
            #expect(word == c.int64("word"))
            #expect(commitGeneration(of: word) == c.int64("gen"))
            #expect(commitState(of: word) == state)
        }
    }

    private func expand(_ steps: [[String: Any]], _ preludes: [String: Any], into: inout [[String: Any]]) {
        for step in steps {
            if step.text("op") == "prelude" {
                expand(preludes[step.text("name")] as! [[String: Any]], preludes, into: &into)
            } else {
                into.append(step)
            }
        }
    }

    private func run(_ name: String, _ profile: [String: Any], _ steps: [[String: Any]]) -> [String] {
        let word = CommitWord()
        let timing = BlendTiming(sampleRate: Int(profile.int64("sampleRate")), placementHorizonFrames: profile.int64("placementHorizonFrames"),
                                 proposalLeadFrames: profile.int64("proposalLeadFrames"), proposeAtFrames: profile.int64("proposeAtFrames"),
                                 commitAtFrames: profile.int64("commitAtFrames"), ringLeadMs: profile.num("ringLeadMs"),
                                 sinkBufferMs: profile.num("sinkBufferMs"))
        let session = BlendSession(word: word, timing: timing)
        var failures: [String] = []
        var head: Int64 = 0
        for (index, step) in steps.enumerated() {
            let op = step.text("op")
            let place = "\(name), step \(index + 1) (\(op))"
            switch op {
            case "advance":
                head = step.int64("head")
                session.advance(to: step.int64("to"), head: head, outgoingMs: step.num("aSongMs"))
            case "arm":
                let gen = step.int64("gen")
                word.open(gen)
                session.arm(BlendArm(generation: gen, startMs: step.num("startMs"), overlapMs: step.num("overlapMs"),
                                     latestEndMs: step.num("latestEndMs"), outgoingEndMs: step.num("outgoingEndMs"),
                                     lateMoveBars: step.moveBars("lateMoveBars"), barMs: step.numOr("barMs", .nan),
                                     holdShare: step.numOr("holdShare", 0), speed: step.numOr("speed", 1),
                                     bothHeardStartMs: step.numOr("bothHeardStartMs", 0), bothHeardEndMs: step.numOr("bothHeardEndMs", 0),
                                     lockMs: step.numOr("lockMs", 0), driftGap: step.numOr("driftGap", 0),
                                     commitBeforeAck: step.flag("commitBeforeAck")))
            case "paceChanged":
                session.paceChanged(pace: step.num("pace"), switchFrame: step.int64("frame"))
            case "post":
                let posted = session.post(BlendDeck(rawValue: step.text("deck"))!, BlendInput(rawValue: step.text("input"))!,
                                          frame: step.int64("frame"), value: step.int64Or("value", 0))
                if !posted { failures.append("\(place): the input buffer is full") }
            case "ack":
                if word.ack(step.int64("gen")) != step.flag("ok") { failures.append("\(place): the ack should give \(step.flag("ok"))") }
            case "cancel":
                if word.cancel(step.int64("gen")) != step.flag("ok") { failures.append("\(place): the cancel should give \(step.flag("ok"))") }
            case "word":
                let state = word.state(of: step.int64("gen")).map { "\($0)" }
                if state != step.text("state") { failures.append("\(place): the word is \(state ?? "another generation's"), expected \(step.text("state"))") }
            case "disarm":
                session.disarm(BlendDisarm(rawValue: step.text("reason"))!, frame: step.int64("frame"),
                               settledHead: step.int64Or("settledHead", step.int64("frame")))
            case "failed":
                session.failed(BlendDeck(rawValue: step.text("deck"))!, followUp: step["pass"].map(pass) ?? blendNoAnswer)
            case "followUp":
                session.followUp(generation: step.int64("gen"), pass: pass(step["pass"]!))
            case "stopped":
                session.outgoingStopped(generation: step.int64("gen"))
            case "cleared":
                session.cleared(from: step.int64("from"))
            default:
                failures.append("\(place): no such op")
            }
            var actions: [String] = []
            while let action = session.takeAction() {
                actions.append(describe(action.kind.rawValue, action.frame, action.value, action.ms))
            }
            let wantedActions = ((step["actions"] as? [[String: Any]]) ?? []).map {
                describe($0.text("kind"), $0.int64("frame"), $0.int64Or("value", 0), $0.numOr("ms", 0))
            }
            if actions != wantedActions { failures.append("\(place): actions \(actions), expected \(wantedActions)") }
            var events: [String] = []
            while let event = session.takeEvent(head: head) {
                events.append(describe(event.kind.rawValue, event.generation, event.frame, event.ms, event.ms2, event.cause.rawValue))
            }
            let wantedEvents = ((step["events"] as? [[String: Any]]) ?? []).map {
                describe($0.text("kind"), $0.int64("gen"), $0.int64("frame"), $0.numOr("ms", 0), $0.numOr("ms2", 0),
                         ($0["cause"] as? String) ?? "none")
            }
            if events != wantedEvents { failures.append("\(place): events \(events), expected \(wantedEvents)") }
        }
        if session.lost != 0 { failures.append("\(name): \(session.lost) inputs, actions or events did not fit") }
        return failures
    }

    private func describe(_ kind: String, _ frame: Int64, _ value: Int64, _ ms: Double) -> String {
        "\(kind)@\(frame)" + (value != 0 ? " value=\(value)" : "") + (abs(ms) > 5e-7 ? " ms=\(format(ms))" : "")
    }

    private func describe(_ kind: String, _ gen: Int64, _ frame: Int64, _ ms: Double, _ ms2: Double, _ cause: String) -> String {
        "\(kind) gen=\(gen) @\(frame)" + (abs(ms) > 5e-7 ? " ms=\(format(ms))" : "") + (abs(ms2) > 5e-7 ? " ms2=\(format(ms2))" : "")
            + (cause != "none" ? " cause=\(cause)" : "")
    }

    private func format(_ x: Double) -> String { String(format: "%.6f", x) }

    private func pass(_ value: Any) -> Int64 {
        switch value as? String {
        case "none": blendPassNone
        case "held": blendPassHeld
        default: (value as! NSNumber).int64Value
        }
    }
}

fileprivate extension Dictionary where Key == String, Value == Any {
    func text(_ key: String) -> String { self[key] as! String }
    func num(_ key: String) -> Double { (self[key] as! NSNumber).doubleValue }
    func int64(_ key: String) -> Int64 { (self[key] as! NSNumber).int64Value }
    func object(_ key: String) -> [String: Any] { self[key] as! [String: Any] }
    func list(_ key: String) -> [[String: Any]] { self[key] as! [[String: Any]] }
    func present(_ key: String) -> Any? { self[key].flatMap { $0 is NSNull ? nil : $0 } }
    func numOr(_ key: String, _ fallback: Double) -> Double { (present(key) as? NSNumber)?.doubleValue ?? fallback }
    func int64Or(_ key: String, _ fallback: Int64) -> Int64 { (present(key) as? NSNumber)?.int64Value ?? fallback }
    /// A bar budget: a number, "unbounded", or 0 when missing.
    func moveBars(_ key: String) -> Int {
        guard let value = present(key) else { return 0 }
        if value as? String == "unbounded" { return lateMoveUnbounded }
        return (value as! NSNumber).intValue
    }

    func flag(_ key: String) -> Bool {
        guard let value = present(key) else { return false }
        if let flag = value as? Bool { return flag }
        return (value as! NSNumber).boolValue
    }
}

import Foundation
import Testing
@testable import OctoKit

/// The sheet's states against the shared fixture's cases, the ones the Android sheet runs too.
@Suite struct StreamVersionsSheetStateTests {
    func event(_ step: [String: Any], _ fixture: [String: Any]) throws -> StreamVersionsEvent {
        let text = { (name: String) in step[name] as? String }
        switch text("event") {
        case "open":
            return .open(VersionsSong(entryId: text("entry") ?? "", songId: text("song") ?? "", title: text("title") ?? "",
                                      artist: text("artist") ?? ""))
        case "listed": return .listed(try streamVersionsAnswer(text("answer") ?? "", in: fixture))
        case "listFailed": return .listFailed(message: text("message"))
        case "retry": return .retry
        case "pick": return .pick(videoId: text("video") ?? "", playingEntryId: text("entry"), playingSongId: text("song"))
        case "undo": return .undo(playingEntryId: text("entry"), playingSongId: text("song"))
        case "picked": return .picked(try streamVersionsAnswer(text("answer") ?? "", in: fixture))
        case "pickFailed": return .pickFailed(message: text("message") ?? "")
        case "entryChanged": return .entryChanged(text("entry"))
        case "close": return .close
        default: throw FixtureMissing(name: "event \(text("event") ?? "none")")
        }
    }

    /// What a case checks of a state, by the shared fixture's names.
    struct Observed {
        var texts: [String: String?]
        var rows: [String]
        var canUndo: Bool
    }

    func observed(_ state: StreamVersionsSheetState) -> Observed {
        var shown: StreamVersionsShown?
        let name: String
        switch state {
        case .closed: name = "closed"
        case .looking: name = "looking"
        case .couldNotReach: name = "couldNotReach"
        case .unavailable: name = "unavailable"
        case .shown(let value):
            name = "shown"
            shown = value
        }
        let texts: [String: String?] = [
            "state": name,
            "message": StreamVersionWords.stateLine(state),
            "playing": shown?.playing?.videoId,
            "checking": shown?.checking,
            "refused": shown?.refused,
            "refusal": shown?.refusal,
            "empty": shown?.emptyText,
        ]
        return Observed(texts: texts, rows: shown?.rows.map(\.videoId) ?? [], canUndo: shown?.canUndo ?? false)
    }

    @Test func everyCaseOfTheSharedFixture() throws {
        let fixture = try streamVersionsFixture()
        let cases = try #require(fixture["reducer"] as? [[String: Any]])
        #expect(cases.count >= 16)
        for testCase in cases {
            let name = testCase["name"] as? String ?? ""
            var state = StreamVersionsSheetState.closed
            let steps = try #require(testCase["steps"] as? [[String: Any]])
            for (at, step) in steps.enumerated() {
                state = state.on(try event(step, fixture))
                let now = observed(state)
                let expect = try #require(step["expect"] as? [String: Any])
                for (key, want) in expect {
                    let label: Comment = "\(name), step \(at + 1), \(key)"
                    switch key {
                    case "rows": #expect(now.rows == (want as? [String] ?? []), label)
                    case "canUndo": #expect(now.canUndo == (want as? Bool ?? false), label)
                    default:
                        #expect(now.texts.keys.contains(key), label)
                        #expect((now.texts[key] ?? nil) == (want as? String), label)
                    }
                }
            }
        }
    }
}

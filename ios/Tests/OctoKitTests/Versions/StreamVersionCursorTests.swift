import Foundation
import Testing
@testable import OctoKit

/// The change feed's cursor rules against the shared fixture's cases.
@Suite struct StreamVersionCursorTests {
    func cursor(_ object: [String: Any]) -> StreamVersionCursor {
        StreamVersionCursor(epoch: object["epoch"] as? String ?? "", seq: Int64(object["seq"] as? Int ?? -1))
    }

    @Test func everyFeedCaseOfTheSharedFixture() throws {
        for feedCase in try #require(try streamVersionsFixture()["feed"] as? [[String: Any]]) {
            let name: Comment = "\(feedCase["name"] as? String ?? "")"
            let stored = (feedCase["stored"] as? [String: Any]).map(cursor)
            let body = try JSONSerialization.data(withJSONObject: try #require(feedCase["answer"]))
            let answer = try JSONDecoder().decode(StreamVersionChanges.self, from: body)
            let plan = planFeed(stored: stored, answer: answer)
            let expect = try #require(feedCase["expect"] as? [String: Any])
            #expect(plan.dropAll == (expect["dropAll"] as? Bool), name)
            #expect(plan.dropIds == Set(expect["dropIds"] as? [String] ?? ["missing"]), name)
            #expect(plan.next == cursor(try #require(expect["next"] as? [String: Any])), name)
        }
    }

    /// The cursor is kept as JSON on a device.
    @Test func aCursorReadsBackAsItWasKept() throws {
        let kept = StreamVersionCursor(epoch: "e1", seq: 7)
        let read = try JSONDecoder().decode(StreamVersionCursor.self, from: JSONEncoder().encode(kept))
        #expect(read == kept)
    }
}

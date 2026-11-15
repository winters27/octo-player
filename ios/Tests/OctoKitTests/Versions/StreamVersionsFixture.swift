import Foundation
@testable import OctoKit

/// fixtures/stream-versions-words.json at the repository root, which the Android and desktop tests
/// read too, found by walking up from this file.
func streamVersionsFixture() throws -> [String: Any] {
    var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
    while dir.path != "/" && !dir.path.isEmpty {
        let candidate = dir.appendingPathComponent("fixtures/stream-versions-words.json")
        if FileManager.default.fileExists(atPath: candidate.path) {
            guard let object = try JSONSerialization.jsonObject(with: Data(contentsOf: candidate)) as? [String: Any] else {
                throw FixtureMissing(name: "stream-versions-words.json")
            }
            return object
        }
        dir = dir.deletingLastPathComponent()
    }
    throw FixtureMissing(name: "stream-versions-words.json")
}

/// One of the fixture's `answers`, read as the client reads the server's.
func streamVersionsAnswer(_ name: String, in fixture: [String: Any]) throws -> StreamVersions {
    guard let answers = fixture["answers"] as? [String: Any], let answer = answers[name] else {
        throw FixtureMissing(name: "answer \(name)")
    }
    return try JSONDecoder().decode(StreamVersions.self, from: JSONSerialization.data(withJSONObject: answer))
}

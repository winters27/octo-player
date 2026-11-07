import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import OctoKit

/// A shared gateway fixture (fixtures/gateway at the repository root, written for every app),
/// found by walking up from this file.
func gatewayFixture(_ name: String) throws -> Data {
    var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
    while dir.path != "/" && !dir.path.isEmpty {
        let candidate = dir.appendingPathComponent("fixtures/gateway/\(name)")
        if FileManager.default.fileExists(atPath: candidate.path) { return try Data(contentsOf: candidate) }
        dir = dir.deletingLastPathComponent()
    }
    throw FixtureMissing(name: name)
}

struct FixtureMissing: Error {
    let name: String
}

/// What a sign-in page in front of a FakeServer answers. FakeServer cannot follow a redirect on
/// Linux, so a gateway's 302 to its page reads here as the page itself.
enum GatewayPages {
    static let authentik = #"<!DOCTYPE html><html><head><title>authentik</title></head><body><ak-flow-executor aria-label="authentik"></ak-flow-executor></body></html>"#

    static func reply(_ status: Int, _ body: String = authentik, type: String = "text/html; charset=utf-8") -> FakeServer.Reply {
        FakeServer.Reply(status: status, body: Data(body.utf8), headers: ["Content-Type": type])
    }
}

/// A clock a test moves.
final class GatewayTestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var current: Date

    init(_ iso: String) {
        current = ISO8601DateFormatter().date(from: iso)!
    }

    var now: Date {
        get { lock.withLock { current } }
        set { lock.withLock { current = newValue } }
    }
}

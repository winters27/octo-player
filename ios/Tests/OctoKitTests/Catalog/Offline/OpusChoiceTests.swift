import Foundation
import Testing
@testable import OctoKit

/// What the bundled tone's try says about this phone playing the Opus files it writes.
@Suite struct OpusChoiceTests {
    @Test func aFinishedDecodeOfTheToneIsYesOnlyAtItsLengthAndLevel() {
        #expect(probeAnswer(decodedFrames: 48_000, rms: opusProbeRms) == .yes)
        #expect(probeAnswer(decodedFrames: 47_200, rms: opusProbeRms * 0.8) == .yes)
        #expect(probeAnswer(decodedFrames: 48_000, rms: 0) == .no)                  // silent: the reported failure
        #expect(probeAnswer(decodedFrames: 24_000, rms: opusProbeRms) == .no)       // half of it
        #expect(probeAnswer(decodedFrames: 48_000, rms: opusProbeRms * 0.5) == .no) // 6 dB off
    }

    @Test func theSystemRefusingTheFormatIsNoAndADiskErrorIsNothing() {
        func status(_ code: String) -> Int32 { Int32(bitPattern: fourCharCode(code)) }
        #expect(probeAnswer(forStatus: status("fmt?")) == .no)
        #expect(probeAnswer(forStatus: status("typ?")) == .no)
        #expect(probeAnswer(forStatus: status("dta?")) == .no)
        #expect(probeAnswer(forStatus: status("!dat")) == .no)
        #expect(probeAnswer(forStatus: status("wht?")) == nil)
        #expect(probeAnswer(forStatus: status("prm?")) == nil)
        #expect(probeAnswer(forStatus: 28) == nil)        // ENOSPC
        #expect(probeAnswer(forStatus: 0) == nil)
    }

    /// The tone reaches the app in both builds: SwiftPM's (xtool) and the Xcode project's.
    @Test func bothBuildsCarryTheTone() throws {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        var found: URL?
        while dir.path != "/" && !dir.path.isEmpty {
            let candidate = dir.appendingPathComponent("ios/Package.swift")
            if FileManager.default.fileExists(atPath: candidate.path) { found = dir.appendingPathComponent("ios"); break }
            dir = dir.deletingLastPathComponent()
        }
        let ios = try #require(found, "ios/Package.swift not found above \(#filePath)")
        let package = try String(contentsOf: ios.appendingPathComponent("Package.swift"), encoding: .utf8)
        #expect(package.contains(#"resources: [.copy("CoverArt"), .copy("Probe"), .copy("Licenses")]"#))
        // A Windows checkout ends its lines with CR LF.
        let project = try String(contentsOf: ios.appendingPathComponent("project.yml"), encoding: .utf8)
            .replacingOccurrences(of: "\r\n", with: "\n")
        #expect(project.contains("- path: Sources/Octo/Probe\n        type: folder\n        buildPhase: resources"))
        #expect(project.contains("- \"Probe/**\""))
        #expect(project.contains("- path: Sources/Octo/Licenses\n        type: folder\n        buildPhase: resources"))
        #expect(project.contains("- \"Licenses/**\""))
        // The notice About shows is the one the release carries.
        let notice = ios.appendingPathComponent("Sources/Octo/Licenses/opus-COPYING.txt")
        #expect(try String(contentsOf: notice, encoding: .utf8).contains("Xiph.Org"))
    }

    @Test func thePlayersOwnRefusalIsNo() {
        #expect(probeAnswer(forOpenError: .decodingFormatUnsupported) == .no)
        #expect(probeAnswer(forOpenError: .parsingContainerMalformed) == .no)
        #expect(probeAnswer(forOpenError: .fileNotFound) == nil)
        #expect(probeAnswer(forOpenError: .ioUnspecified) == nil)
    }
}

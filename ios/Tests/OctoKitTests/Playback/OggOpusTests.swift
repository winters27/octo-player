import Foundation
import Testing
@testable import OctoKit

/// Reading the Ogg Opus a server sends, and the CAF layout made from it.
@Suite struct OggOpusTests {
    /// One Ogg page of `packets`, its granule, and whether its first packet goes on from the page
    /// before or its last runs on to the next (`open`).
    static func page(_ packets: [[UInt8]], granule: UInt64, sequence: UInt32, open: Bool = false, serial: UInt32 = 7) -> [UInt8] {
        var lacing: [UInt8] = []
        for (at, packet) in packets.enumerated() {
            var left = packet.count
            while left >= 255 { lacing.append(255); left -= 255 }
            if !(open && at == packets.count - 1) { lacing.append(UInt8(left)) }
        }
        var out = Array("OggS".utf8) + [0, 0]
        out += (0..<8).map { UInt8((granule >> (8 * UInt64($0))) & 0xFF) }
        out += (0..<4).map { UInt8((serial >> (8 * UInt32($0))) & 0xFF) }
        out += (0..<4).map { UInt8((sequence >> (8 * UInt32($0))) & 0xFF) }
        out += [0, 0, 0, 0, UInt8(lacing.count)] + lacing
        for packet in packets { out += packet }
        return out
    }

    static func opusHead(channels: UInt8, preSkip: UInt16) -> [UInt8] {
        Array("OpusHead".utf8) + [1, channels, UInt8(preSkip & 0xFF), UInt8(preSkip >> 8), 0x80, 0xBB, 0, 0, 0, 0, 0]
    }

    static let tags = Array("OpusTags".utf8) + [0, 0, 0, 0, 0, 0, 0, 0]
    /// A 20 ms CELT fullband packet (configuration 31, one frame) and a 10 ms one (configuration 30).
    static let packet20: [UInt8] = [0xF8] + [UInt8](repeating: 7, count: 40)
    static let packet10: [UInt8] = [0xF0] + [UInt8](repeating: 9, count: 30)

    static func stream(_ audio: [[UInt8]], granule: UInt64) -> [UInt8] {
        page([opusHead(channels: 2, preSkip: 312)], granule: 0, sequence: 0) + page([tags], granule: 0, sequence: 1)
            + page(audio, granule: granule, sequence: 2)
    }

    @Test func aStreamReadsAsItsHeadPacketsFramesAndEnd() throws {
        let read = try #require(OggOpus.read(Self.stream([Self.packet20, Self.packet20, Self.packet20], granule: 2_880)))
        #expect(read.channels == 2)
        #expect(read.preSkip == 312)
        #expect(read.packets.count == 3)
        #expect(read.frames == [960, 960, 960])
        #expect(read.lastGranule == 2_880)
        #expect(read.head.count == 19)
    }

    @Test func aPacketAcrossTwoPagesIsOnePacket() throws {
        let long: [UInt8] = [0xF8] + [UInt8](repeating: 1, count: 299)
        let bytes = Self.page([Self.opusHead(channels: 1, preSkip: 0)], granule: 0, sequence: 0)
            + Self.page([Self.tags], granule: 0, sequence: 1)
            + Self.page([Array(long.prefix(255))], granule: UInt64.max, sequence: 2, open: true)
            + Self.page([Array(long.dropFirst(255)), Self.packet20], granule: 1_920, sequence: 3)
        let read = try #require(OggOpus.read(bytes))
        #expect(read.packets == [long, Self.packet20])
        #expect(read.lastGranule == 1_920)
    }

    @Test func paddingAfterTheLastPageEndsTheStreamAndACutPageIsRefused() throws {
        let whole = Self.stream([Self.packet20, Self.packet20], granule: 1_920)
        let padded = try #require(OggOpus.read(whole + [UInt8](repeating: 0, count: 4_096)))
        #expect(padded.packets.count == 2)
        #expect(OggOpus.read(Array(whole.dropLast(5))) == nil)
    }

    @Test func aVorbisStreamIsNotOpus() {
        let vorbis = Self.page([[1] + Array("vorbis".utf8) + [UInt8](repeating: 0, count: 23)], granule: 0, sequence: 0)
        #expect(OggOpus.read(vorbis + Self.page([Self.packet20], granule: 960, sequence: 1)) == nil)
    }

    @Test func eachTableOfContentsGivesItsFrames() {
        #expect(opusPacketFrames([0x00]) == 480)                 // SILK 10 ms
        #expect(opusPacketFrames([0x18]) == 2_880)               // SILK 60 ms
        #expect(opusPacketFrames([0x60]) == 480)                 // hybrid 10 ms
        #expect(opusPacketFrames([0x80]) == 120)                 // CELT 2.5 ms
        #expect(opusPacketFrames([0xF9]) == 1_920)               // CELT 20 ms, two frames
        #expect(opusPacketFrames([0xFB, 0x03]) == 2_880)         // CELT 20 ms, three frames by count
        #expect(opusPacketFrames([]) == 0)
    }

    @Test func theCafLayoutSkipsThePreSkipAndEndsAtTheLastGranule() throws {
        let read = try #require(OggOpus.read(Self.stream([Self.packet20, Self.packet20, Self.packet20], granule: 2_500)))
        let layout = CafPlan.from(read)
        #expect(layout.sampleRate == 48_000)
        #expect(layout.formatID == 0x6F70_7573)
        #expect(layout.channels == 2)
        #expect(layout.framesPerPacket == 960)
        #expect(layout.cookie == read.head)
        #expect(layout.primingFrames == 312)
        #expect(layout.validFrames == 2_188)
        #expect(layout.remainderFrames == 380)
        #expect(layout.totalFrames == 2_880)
    }

    @Test func mixedPacketsSayTheirOwnFrames() throws {
        let read = try #require(OggOpus.read(Self.stream([Self.packet20, Self.packet10], granule: 1_440)))
        let layout = CafPlan.from(read)
        #expect(layout.framesPerPacket == 0)
        #expect(layout.packetFrames == [960, 480])
        #expect(layout.validFrames == 1_128)
        #expect(layout.remainderFrames == 0)
    }

    @Test func theBundledToneIsOneSecondOfMonoOpus() throws {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        var found: URL?
        while dir.path != "/" && !dir.path.isEmpty {
            let candidate = dir.appendingPathComponent("ios/Sources/Octo/Probe/opus-probe.opus")
            if FileManager.default.fileExists(atPath: candidate.path) { found = candidate; break }
            dir = dir.deletingLastPathComponent()
        }
        let url = try #require(found, "ios/Sources/Octo/Probe/opus-probe.opus not found above \(#filePath)")
        let read = try #require(OggOpus.read([UInt8](try Data(contentsOf: url))))
        #expect(read.channels == 1)
        let layout = CafPlan.from(read)
        #expect(abs(Int(layout.expectedFrames) - opusProbeFrames) <= 960)
    }
}

import Foundation
import Testing
@testable import OctoKit

/// Octo's own Opus decoder (the bundled libopus) on the bundled tone: one second of a 440 Hz sine at
/// half of full scale, mono, 48 kHz, made by ffmpeg's libopus with its usual pre-skip.
@Suite struct OggOpusPlayerTests {
    func tone() throws -> OggOpusStream {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        var found: URL?
        while dir.path != "/" && !dir.path.isEmpty {
            let candidate = dir.appendingPathComponent("ios/Sources/Octo/Probe/opus-probe.opus")
            if FileManager.default.fileExists(atPath: candidate.path) { found = candidate; break }
            dir = dir.deletingLastPathComponent()
        }
        let url = try #require(found, "ios/Sources/Octo/Probe/opus-probe.opus not found above \(#filePath)")
        return try #require(OggOpus.read([UInt8](try Data(contentsOf: url))))
    }

    /// Everything the player hands out from where it is.
    func readAll(_ player: OggOpusPlayer) -> [Float] {
        var all: [Float] = []
        var chunk = [Float](repeating: 0, count: 1_000 * player.channels)
        while true {
            let got = chunk.withUnsafeMutableBufferPointer { player.read(into: $0.baseAddress!, frames: 1_000) }
            if got == 0 { return all }
            all += chunk.prefix(got * player.channels)
        }
    }

    func rms(_ samples: ArraySlice<Float>) -> Double {
        (samples.reduce(0.0) { $0 + Double($1) * Double($1) } / Double(max(1, samples.count))).squareRoot()
    }

    /// The tone in 100 ms from `from`, in Hz, by its zero crossings.
    func hz(_ samples: [Float], from: Int) -> Double {
        let window = samples[from..<(from + 4_800)]
        let crossings = zip(window, window.dropFirst()).filter { ($0 < 0) != ($1 < 0) }.count
        return Double(crossings) / 2 / 0.1
    }

    @Test func theToneDecodesWholeAtItsLevelWithItsPreSkipLeftOut() throws {
        let stream = try tone()
        let player = try #require(OggOpusPlayer(stream: stream))
        #expect(player.channels == 1)
        #expect(player.totalFrames == stream.lastGranule - Int64(stream.preSkip))
        let all = readAll(player)
        #expect(Int64(all.count) == player.totalFrames, "the end cut at the last granule, no more, no less")
        #expect(abs(all.count - opusProbeFrames) <= 960)
        // The middle half second, past the codec's own start: the sine's level within 1 dB.
        let middle = all[12_000..<36_000]
        #expect(abs(20 * log10(rms(middle) / opusProbeRms)) <= 1)
        #expect(abs(hz(all, from: 24_000) - 440) <= 20)
    }

    @Test func aSeekLandsAtItsPlaceWithTheRestOfTheSongAfterIt() throws {
        let player = try #require(OggOpusPlayer(stream: try tone()))
        player.seek(toFrame: 24_000)
        let rest = readAll(player)
        #expect(Int64(rest.count) == player.totalFrames - 24_000)
        // Decoded from its lead-in, the sound at the place is the tone at its level, not a cold start.
        #expect(abs(hz(rest, from: 0) - 440) <= 20)
        #expect(abs(20 * log10(rms(rest[0..<4_800]) / opusProbeRms)) <= 1)
        // Back to the start and past the end.
        player.seek(toFrame: 0)
        #expect(Int64(readAll(player).count) == player.totalFrames)
        player.seek(toFrame: player.totalFrames + 10_000)
        #expect(readAll(player).isEmpty)
    }

    @Test func theOutputGainIsOpusHeadsQ78Decibels() {
        var head = [UInt8](repeating: 0, count: 19)
        #expect(OggOpusPlayer.outputGain(head) == 1)
        // +6.02 dB doubles; -6.02 dB halves (1541 = 6.02 x 256).
        head[16] = UInt8(1_541 & 0xFF)
        head[17] = UInt8(1_541 >> 8)
        #expect(abs(OggOpusPlayer.outputGain(head) - 2) < 0.01)
        let minus = UInt16(bitPattern: -1_541)
        head[16] = UInt8(minus & 0xFF)
        head[17] = UInt8(minus >> 8)
        #expect(abs(OggOpusPlayer.outputGain(head) - 0.5) < 0.01)
        #expect(OggOpusPlayer.outputGain([1, 2, 3]) == 1)
    }

    @Test func aStreamThePlayerCannotTakeIsRefused() throws {
        var stream = try tone()
        stream.channels = 6
        #expect(OggOpusPlayer(stream: stream) == nil)
        stream.channels = 1
        var family = stream
        family.head[18] = 1
        #expect(OggOpusPlayer(stream: family) == nil, "mapping family 1 is refused")
        stream.packets = []
        stream.frames = []
        #expect(OggOpusPlayer(stream: stream) == nil)
    }

    @Test func theOutputGainRaisesTheDecodedSound() throws {
        var stream = try tone()
        let plain = readAll(try #require(OggOpusPlayer(stream: stream)))
        stream.head[16] = UInt8(1_541 & 0xFF)
        stream.head[17] = UInt8(1_541 >> 8)
        let louder = readAll(try #require(OggOpusPlayer(stream: stream)))
        #expect(abs(20 * log10(rms(louder[12_000..<36_000]) / rms(plain[12_000..<36_000])) - 6.02) <= 0.5)
    }

    /// An Opus CAF laid out as `Rewrap` writes it (`CafPlan`): `desc`, `kuki`, `pakt`, `data`.
    func caf(_ layout: PacketLayout) -> [UInt8] {
        func be(_ value: UInt64, _ bytes: Int) -> [UInt8] { (0..<bytes).reversed().map { UInt8(truncatingIfNeeded: value >> (8 * UInt64($0))) } }
        func chunk(_ type: String, _ body: [UInt8]) -> [UInt8] { Array(type.utf8) + be(UInt64(body.count), 8) + body }
        func varint(_ value: Int) -> [UInt8] {
            var groups = [UInt8(value & 0x7F)]
            var rest = value >> 7
            while rest > 0 {
                groups.insert(UInt8(rest & 0x7F) | 0x80, at: 0)
                rest >>= 7
            }
            return groups
        }
        let desc = be(layout.sampleRate.bitPattern, 8) + be(UInt64(layout.formatID), 4) + be(UInt64(layout.formatFlags), 4)
            + be(0, 4) + be(UInt64(layout.framesPerPacket), 4) + be(UInt64(layout.channels), 4) + be(0, 4)
        var entries: [UInt8] = []
        for (index, packet) in layout.packets.enumerated() {
            entries += varint(packet.count)
            if layout.framesPerPacket == 0 { entries += varint(layout.packetFrames[index]) }
        }
        let pakt = be(UInt64(layout.packets.count), 8) + be(UInt64(layout.expectedFrames), 8)
            + be(UInt64(UInt32(bitPattern: layout.primingFrames)), 4) + be(UInt64(UInt32(bitPattern: layout.remainderFrames)), 4) + entries
        return Array("caff".utf8) + [0, 1, 0, 0] + chunk("desc", desc) + chunk("kuki", layout.cookie) + chunk("pakt", pakt)
            + chunk("data", be(0, 4) + layout.packets.flatMap { $0 })
    }

    @Test func anOpusCafReadsBackAsTheStreamItWasWrittenFrom() throws {
        let stream = try tone()
        let read = try #require(CafOpus.read(caf(CafPlan.from(stream))))
        #expect(read.packets == stream.packets)
        #expect(read.frames == stream.frames)
        #expect(read.preSkip == stream.preSkip)
        #expect(read.lastGranule == stream.lastGranule)
        let player = try #require(OggOpusPlayer(stream: read))
        #expect(Int64(readAll(player).count) == stream.lastGranule - Int64(stream.preSkip))
        // A CAF of another codec, or cut in its packet data, is not one.
        #expect(CafOpus.isOpus(Array(caf(CafPlan.from(stream)).prefix(64))))
        var notOpus = caf(CafPlan.from(stream))
        notOpus[8 + 12 + 8] = UInt8(ascii: "x")
        #expect(!CafOpus.isOpus(notOpus))
        #expect(CafOpus.read(notOpus) == nil)
        #expect(CafOpus.read(Array(caf(CafPlan.from(stream)).dropLast(50))) == nil)
        // A damaged packet count is turned away, never a trap or a huge reservation.
        var damaged = caf(CafPlan.from(stream))
        let paktChunk = try #require((0..<(damaged.count - 4)).first { Array(damaged[$0..<($0 + 4)]) == Array("pakt".utf8) })
        for index in (paktChunk + 12)..<(paktChunk + 20) { damaged[index] = 0xFF }
        #expect(CafOpus.read(damaged) == nil)
        // One case per bound: a count larger than the data could hold, and frames past what it could.
        damaged.replaceSubrange((paktChunk + 12)..<(paktChunk + 20), with: [0, 0, 0x10, 0, 0, 0, 0, 0])
        #expect(CafOpus.read(damaged) == nil)
        var tooLong = caf(CafPlan.from(stream))
        tooLong.replaceSubrange((paktChunk + 20)..<(paktChunk + 28), with: [0x7F, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF])
        #expect(CafOpus.read(tooLong) == nil)
        // So is a damaged chunk size: the cookie then runs to the end and no packet table is found.
        var oversized = caf(CafPlan.from(stream))
        let kukiChunk = try #require((0..<(oversized.count - 4)).first { Array(oversized[$0..<($0 + 4)]) == Array("kuki".utf8) })
        oversized.replaceSubrange((kukiChunk + 4)..<(kukiChunk + 12), with: [0x7F, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF])
        #expect(CafOpus.read(oversized) == nil)
    }
}

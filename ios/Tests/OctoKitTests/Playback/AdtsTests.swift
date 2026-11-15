import Foundation
import Testing
@testable import OctoKit

/// Reading ADTS AAC and the M4A layout made from it.
@Suite struct AdtsTests {
    /// One ADTS frame: AAC LC, 44.1 kHz, stereo, no checksum, `payload` bytes of payload.
    static func frame(_ payload: Int, fill: UInt8 = 0x21) -> [UInt8] {
        let length = 7 + payload
        let header: [UInt8] = [0xFF, 0xF1, 0x50, 0x80 | UInt8((length >> 11) & 0x03), UInt8((length >> 3) & 0xFF),
                               UInt8(((length & 0x07) << 5) | 0x1F), 0xFC]
        return header + [UInt8](repeating: fill, count: payload)
    }

    @Test func framesReadWithTheirConfig() throws {
        let read = try #require(Adts.read(Self.frame(100) + Self.frame(120) + Self.frame(90)))
        #expect(read.audioConfig == [0x12, 0x10])
        #expect(read.objectType == 2)
        #expect(read.sampleRate == 44_100)
        #expect(read.channels == 2)
        #expect(read.frames.map(\.count) == [100, 120, 90])
    }

    @Test func aCutLastFrameIsLeftOutAndPaddingEndsTheStream() throws {
        let whole = Self.frame(100) + Self.frame(100)
        #expect(try #require(Adts.read(whole + Array(Self.frame(100).prefix(50)))).frames.count == 2)
        #expect(try #require(Adts.read(whole + [UInt8](repeating: 0, count: 300))).frames.count == 2)
        #expect(Adts.read([UInt8](repeating: 0, count: 64)) == nil)
    }

    @Test func junkAfterTheFramesAndFramesOfChangingSettingsAreRefused() {
        let whole = Self.frame(100) + Self.frame(100)
        #expect(Adts.read(whole + [0x00, 0x00, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46]) == nil)
        // A frame at 48 kHz (rate index 3) after frames at 44.1 kHz.
        var other = Self.frame(100)
        other[2] = 0x4C
        #expect(Adts.read(whole + other) == nil)
    }

    @Test func theM4aLayoutCarriesAnElementaryStreamDescriptor() throws {
        let read = try #require(Adts.read(Self.frame(100) + Self.frame(100)))
        let layout = M4aPlan.from(read)
        #expect(layout.formatID == 0x6161_6320)
        #expect(layout.formatFlags == 2)
        #expect(layout.sampleRate == 44_100)
        #expect(layout.channels == 2)
        #expect(layout.framesPerPacket == 1024)
        #expect(layout.packetFrames == [1024, 1024])
        #expect(layout.validFrames == nil)
        #expect(layout.expectedFrames == 2_048)
        let bitrate = UInt32(Int(Double(200 * 8) / (2_048.0 / 44_100.0)))
        func be32(_ v: UInt32) -> [UInt8] { [UInt8(v >> 24), UInt8((v >> 16) & 0xFF), UInt8((v >> 8) & 0xFF), UInt8(v & 0xFF)] }
        let expected: [UInt8] = [0x03, 25, 0x00, 0x00, 0x00, 0x04, 17, 0x40, 0x15, 0x00, 0x00, 0x00] + be32(bitrate) + be32(bitrate)
            + [0x05, 0x02, 0x12, 0x10, 0x06, 0x01, 0x02]
        #expect(layout.cookie == expected)
    }
}

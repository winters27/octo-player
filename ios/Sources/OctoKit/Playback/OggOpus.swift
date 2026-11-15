import Foundation

/// An Ogg Opus stream as it arrived: its OpusHead, its audio packets and the frames each makes, and
/// the last page's granule position (the end of the song, pre-skip included).
public struct OggOpusStream: Sendable, Equatable {
    public var head: [UInt8]
    public var channels: Int
    public var preSkip: Int
    public var packets: [[UInt8]]
    public var frames: [Int]
    public var lastGranule: Int64
}

public enum OggOpus {
    /// Reads the first logical stream of an Ogg file as Opus. Reading stops at the first byte after
    /// a whole page that does not start a page (padding a server added at the end); a page cut short
    /// is refused (nil), as is a stream that is not Opus or has no audio.
    public static func read(_ bytes: [UInt8]) -> OggOpusStream? {
        var at = 0
        var packets: [[UInt8]] = []
        var pending: [UInt8] = []
        var lastGranule: Int64 = 0
        var serial: UInt32?
        while at < bytes.count {
            guard at + 4 <= bytes.count, bytes[at] == 0x4F, bytes[at + 1] == 0x67, bytes[at + 2] == 0x67, bytes[at + 3] == 0x53 else {
                break
            }
            guard at + 27 <= bytes.count else { return nil }
            let segments = Int(bytes[at + 26])
            let body = at + 27 + segments
            guard body <= bytes.count else { return nil }
            let lacing = Array(bytes[(at + 27)..<body])
            let size = lacing.reduce(0) { $0 + Int($1) }
            guard body + size <= bytes.count else { return nil }
            let granule = (0..<8).reduce(UInt64(0)) { $0 | (UInt64(bytes[at + 6 + $1]) << (8 * UInt64($1))) }
            let pageSerial = (0..<4).reduce(UInt32(0)) { $0 | (UInt32(bytes[at + 14 + $1]) << (8 * UInt32($1))) }
            if serial == nil { serial = pageSerial }
            if pageSerial == serial {
                var offset = body
                for length in lacing {
                    pending.append(contentsOf: bytes[offset..<offset + Int(length)])
                    offset += Int(length)
                    if length < 255 {
                        packets.append(pending)
                        pending = []
                    }
                }
                // A granule of all ones marks a page on which no packet ends.
                if granule != UInt64.max { lastGranule = Int64(bitPattern: granule) }
            }
            at = body + size
        }
        let opusHead = Array("OpusHead".utf8)
        let opusTags = Array("OpusTags".utf8)
        guard packets.count >= 3, packets[0].count >= 19, Array(packets[0].prefix(8)) == opusHead,
              Array(packets[1].prefix(8)) == opusTags else { return nil }
        let head = packets[0]
        let channels = Int(head[9])
        let preSkip = Int(head[10]) | (Int(head[11]) << 8)
        let audio = packets.dropFirst(2).filter { !$0.isEmpty }
        let frames = audio.map(opusPacketFrames)
        guard channels > 0, !audio.isEmpty, frames.allSatisfy({ $0 > 0 }) else { return nil }
        return OggOpusStream(head: head, channels: channels, preSkip: preSkip, packets: Array(audio), frames: frames,
                             lastGranule: lastGranule)
    }
}

/// How many 48 kHz frames an Opus packet makes, from its table of contents byte: the frame length its
/// configuration names (SILK 10 to 60 ms, hybrid 10 or 20 ms, CELT 2.5 to 20 ms) times the frames
/// its code says it holds. 0 for a packet that says nothing.
public func opusPacketFrames(_ packet: [UInt8]) -> Int {
    guard let toc = packet.first else { return 0 }
    let config = Int(toc >> 3)
    let length: Int
    switch config {
    case 0...11: length = [480, 960, 1920, 2880][config % 4]
    case 12...15: length = [480, 960][config % 2]
    default: length = [120, 240, 480, 960][config % 4]
    }
    let count: Int
    switch toc & 0x3 {
    case 0: count = 1
    case 1, 2: count = 2
    default:
        guard packet.count >= 2 else { return 0 }
        count = Int(packet[1] & 0x3F)
    }
    return length * count
}

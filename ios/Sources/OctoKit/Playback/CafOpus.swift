import Foundation

/// An Opus CAF, as the phone writes them (`Rewrap`), read back as its Opus stream for Octo's own
/// decoder: what plays an Opus CAF kept before the system version it runs on now was found not to
/// play them. The CAF's `desc` names Opus, its `kuki` is the OpusHead, its `pakt` sizes the packets
/// and counts the frames, its `data` holds the packets one after another. All big-endian.
public enum CafOpus {
    /// Whether a CAF's first bytes name Opus (its `desc` chunk always comes first), so a CAF of
    /// another codec is never read whole to find out.
    public static func isOpus(_ head: [UInt8]) -> Bool {
        head.count >= 32 && Array(head[0..<4]) == Array("caff".utf8) && Array(head[8..<12]) == Array("desc".utf8)
            && be32(head, 28) == fourCharCode("opus")
    }

    public static func read(_ bytes: [UInt8]) -> OggOpusStream? {
        guard bytes.count >= 8, Array(bytes[0..<4]) == Array("caff".utf8) else { return nil }
        var at = 8
        var formatID: UInt32?
        var bytesPerPacket = 0
        var framesPerPacket = 0
        var cookie: [UInt8]?
        var table: ArraySlice<UInt8>?
        var data: ArraySlice<UInt8>?
        while at + 12 <= bytes.count {
            let type = Array(bytes[at..<(at + 4)])
            let size = Int64(bitPattern: be64(bytes, at + 4))
            let start = at + 12
            // Compared against what is left, so a damaged size never overflows.
            let end = size < 0 || size > Int64(bytes.count - start) ? bytes.count : start + Int(size)
            guard end >= start else { return nil }
            let body = bytes[start..<end]
            switch type {
            case Array("desc".utf8):
                guard body.count >= 32 else { return nil }
                formatID = be32(bytes, start + 8)
                bytesPerPacket = Int(be32(bytes, start + 16))
                framesPerPacket = Int(be32(bytes, start + 20))
            case Array("kuki".utf8):
                cookie = Array(body)
            case Array("pakt".utf8):
                table = body
            case Array("data".utf8):
                guard body.count >= 4 else { return nil }
                data = body.dropFirst(4)
            default:
                break
            }
            if size < 0 { break }
            at = end
        }
        guard formatID == fourCharCode("opus"), let cookie, cookie.count >= 19, Array(cookie[0..<8]) == Array("OpusHead".utf8),
              let table, table.count >= 24, let data else {
            return nil
        }
        let base = table.startIndex
        let validFrames = Int64(bitPattern: be64(bytes, base + 8))
        // A damaged count or length never traps: every packet takes at least one byte of `data`,
        // and an Opus packet holds at most 5760 frames (120 ms at 48 kHz).
        guard let count = Int(exactly: be64(bytes, base)), count <= data.count,
              validFrames >= 0, validFrames <= Int64(count) * 5_760 else {
            return nil
        }
        var cursor = base + 24
        var packets: [[UInt8]] = []
        var frames: [Int] = []
        packets.reserveCapacity(count)
        var offset = data.startIndex
        for _ in 0..<count {
            var size = bytesPerPacket
            if size == 0 {
                guard let read = varint(bytes, &cursor, end: table.endIndex) else { return nil }
                size = read
            }
            var packetFrames = framesPerPacket
            if packetFrames == 0 {
                guard let read = varint(bytes, &cursor, end: table.endIndex) else { return nil }
                packetFrames = read
            }
            guard size > 0, offset + size <= data.endIndex else { return nil }
            packets.append(Array(bytes[offset..<(offset + size)]))
            frames.append(packetFrames)
            offset += size
        }
        let preSkip = Int(cookie[10]) | Int(cookie[11]) << 8
        return OggOpusStream(head: cookie, channels: Int(cookie[9]), preSkip: preSkip, packets: packets, frames: frames,
                             lastGranule: Int64(preSkip) + validFrames)
    }

    private static func be32(_ bytes: [UInt8], _ at: Int) -> UInt32 {
        bytes[at..<(at + 4)].reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
    }

    private static func be64(_ bytes: [UInt8], _ at: Int) -> UInt64 {
        bytes[at..<(at + 8)].reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
    }

    /// A packet table number: seven bits a byte, most significant first, every byte but the last with
    /// its top bit set.
    private static func varint(_ bytes: [UInt8], _ cursor: inout Int, end: Int) -> Int? {
        var value = 0
        for _ in 0..<5 {
            guard cursor < end else { return nil }
            let byte = bytes[cursor]
            cursor += 1
            value = value << 7 | Int(byte & 0x7F)
            if byte & 0x80 == 0 { return value }
        }
        return nil
    }
}

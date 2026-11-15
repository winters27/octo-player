import Foundation

public enum CafPlan {
    /// Opus as a CAF holds it: 48 kHz, the OpusHead as the magic cookie, each packet with its own
    /// frames when they differ, and the packet table saying the pre-skip is skipped and the last
    /// granule ends the song.
    public static func from(_ stream: OggOpusStream) -> PacketLayout {
        let total = stream.frames.reduce(Int64(0)) { $0 + Int64($1) }
        let constant = stream.frames.allSatisfy { $0 == stream.frames[0] }
        let priming = Int64(stream.preSkip)
        let ending = stream.lastGranule > priming && stream.lastGranule <= total ? stream.lastGranule : total
        let valid = max(0, ending - priming)
        let remainder = max(0, total - priming - valid)
        return PacketLayout(sampleRate: 48_000, formatID: fourCharCode("opus"), formatFlags: 0, channels: UInt32(stream.channels),
                            framesPerPacket: constant ? UInt32(stream.frames[0]) : 0, cookie: stream.head, packets: stream.packets,
                            packetFrames: stream.frames, validFrames: valid, primingFrames: Int32(clamping: priming),
                            remainderFrames: Int32(clamping: remainder))
    }
}

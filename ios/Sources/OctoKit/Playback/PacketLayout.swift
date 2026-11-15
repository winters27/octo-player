import Foundation

/// Everything the phone hands its audio file writer to put received packets into a container of
/// its own: the stream's description, its magic cookie, every packet and how many frames each makes,
/// and, when the codec starts with frames to skip, how many of the decoded frames are the song.
/// Worked out from the bytes alone, so it can be tested anywhere.
public struct PacketLayout: Sendable, Equatable {
    public var sampleRate: Double
    /// The format id (a four-character code: `opus`, `aac `).
    public var formatID: UInt32
    public var formatFlags: UInt32
    public var channels: UInt32
    /// The frames every packet makes, or 0 when packets differ (each then says its own).
    public var framesPerPacket: UInt32
    public var cookie: [UInt8]
    public var packets: [[UInt8]]
    public var packetFrames: [Int]
    /// The frames that are the song (nil when the container is not told).
    public var validFrames: Int64?
    /// Frames to skip at the start and left over at the end.
    public var primingFrames: Int32
    public var remainderFrames: Int32

    /// All the frames the packets make, skipped and left-over ones included.
    public var totalFrames: Int64 { packetFrames.reduce(Int64(0)) { $0 + Int64($1) } }

    /// The frames a decoder of this file should hand out: the song's when the container is told,
    /// all of them otherwise.
    public var expectedFrames: Int64 { validFrames ?? totalFrames }
}

/// A four-character code as the audio frameworks keep it.
public func fourCharCode(_ text: String) -> UInt32 {
    text.utf8.prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
}

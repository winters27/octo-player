import COpus
import Foundation

/// An Ogg Opus stream played from any place: its packets decoded by libopus into 48 kHz float
/// sound, interleaved; the stream's pre-skip left out at its start, its end cut at its last
/// granule, its output gain applied; a place sought into decoded from at least 80 ms before it, as
/// Opus asks, with that lead-in left out.
public final class OggOpusPlayer {
    /// Opus decodes at 48 kHz.
    public static let rate = 48_000
    /// The lead-in decoded before a place sought into, in frames.
    static let preRoll: Int64 = 3_840
    /// The most frames one packet can hold (120 ms).
    static let maxPacketFrames = 5_760

    /// One or two.
    public let channels: Int
    /// The song's frames, its pre-skip left out.
    public let totalFrames: Int64

    private let stream: OggOpusStream
    /// The stream frame each packet starts at, the pre-skip counted.
    private let starts: [Int64]
    /// The stream frame the song ends at.
    private let end: Int64
    private let gain: Float
    private var decoder: OpaquePointer?
    private var nextPacket = 0
    /// The decoded frames of the last packet, interleaved, and the stream frame they start at.
    private var held: [Float]
    private var heldFrames = 0
    private var heldAt: Int64 = 0
    /// The stream frame the next frame handed out is.
    private var position: Int64

    /// None for a stream of more than two channels, with no packets, or that libopus will not take.
    public init?(stream: OggOpusStream) {
        // Mapping family 0 only (one stream, mono or stereo): what every Opus download is.
        guard (1...2).contains(stream.channels), stream.head.count >= 19, stream.head[18] == 0,
              !stream.packets.isEmpty, stream.packets.count == stream.frames.count else {
            return nil
        }
        var at: Int64 = 0
        var starts: [Int64] = []
        starts.reserveCapacity(stream.frames.count)
        for frames in stream.frames {
            starts.append(at)
            at += Int64(frames)
        }
        let preSkip = Int64(stream.preSkip)
        let end = stream.lastGranule > 0 ? min(stream.lastGranule, at) : at
        guard let made = Self.makeDecoder(channels: stream.channels) else { return nil }
        self.stream = stream
        self.starts = starts
        self.end = end
        channels = stream.channels
        totalFrames = max(0, end - preSkip)
        gain = Self.outputGain(stream.head)
        held = [Float](repeating: 0, count: Self.maxPacketFrames * stream.channels)
        position = preSkip
        decoder = made
    }

    deinit {
        if let decoder { opus_decoder_destroy(decoder) }
    }

    /// The stream's output gain, OpusHead's bytes 16 and 17 (Q7.8 dB, little-endian), as a factor.
    static func outputGain(_ head: [UInt8]) -> Float {
        guard head.count >= 18 else { return 1 }
        let q = Int16(bitPattern: UInt16(head[16]) | UInt16(head[17]) << 8)
        return Float(pow(10, Double(q) / (20 * 256)))
    }

    private static func makeDecoder(channels: Int) -> OpaquePointer? {
        var error: Int32 = 0
        let made = opus_decoder_create(Int32(rate), Int32(channels), &error)
        guard error == OPUS_OK, let made else { return nil }
        return made
    }

    /// Up to `frames` frames into `out` (interleaved, `channels` values a frame); 0 at the end.
    public func read(into out: UnsafeMutablePointer<Float>, frames: Int) -> Int {
        var written = 0
        while written < frames && position < end {
            let offset = position - heldAt
            if offset >= 0 && offset < Int64(heldFrames) {
                let count = min(frames - written, heldFrames - Int(offset), Int(end - position))
                let from = Int(offset) * channels
                held.withUnsafeBufferPointer { source in
                    (out + written * channels).update(from: source.baseAddress! + from, count: count * channels)
                }
                written += count
                position += Int64(count)
            } else if !decodeNext() {
                break
            }
        }
        return written
    }

    /// Moves to `frame` of the song (its pre-skip left out): the decoder starts afresh at least 80 ms
    /// of packets before it, and what those make before the place is left out.
    public func seek(toFrame frame: Int64) {
        let target = min(max(0, frame), totalFrames) + Int64(stream.preSkip)
        var low = 0
        var high = starts.count - 1
        while low < high {
            let middle = (low + high + 1) / 2
            if starts[middle] <= target { low = middle } else { high = middle - 1 }
        }
        var first = low
        while first > 0 && target - starts[first] < Self.preRoll { first -= 1 }
        if let decoder { opus_decoder_destroy(decoder) }
        decoder = Self.makeDecoder(channels: channels)
        nextPacket = first
        heldFrames = 0
        heldAt = starts[first]
        position = target
    }

    /// Decodes the next packet into `held`. A packet libopus refuses becomes silence of its length,
    /// so the song keeps its time. False when no packet is left.
    private func decodeNext() -> Bool {
        guard nextPacket < stream.packets.count, let decoder else { return false }
        let index = nextPacket
        nextPacket += 1
        let packet = stream.packets[index]
        let produced: Int32 = packet.withUnsafeBufferPointer { bytes in
            held.withUnsafeMutableBufferPointer { pcm in
                opus_decode_float(decoder, bytes.baseAddress, Int32(bytes.count), pcm.baseAddress!, Int32(Self.maxPacketFrames), 0)
            }
        }
        heldAt = starts[index]
        if produced > 0 {
            heldFrames = Int(produced)
            if gain != 1 {
                for i in 0..<(heldFrames * channels) { held[i] *= gain }
            }
        } else {
            heldFrames = min(stream.frames[index], Self.maxPacketFrames)
            for i in 0..<(heldFrames * channels) { held[i] = 0 }
        }
        return true
    }
}

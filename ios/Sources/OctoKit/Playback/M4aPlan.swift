import Foundation

public enum M4aPlan {
    /// AAC as an M4A holds it: its rate and channels, 1024 frames a packet, its object type as the
    /// format flags, and an MPEG-4 elementary stream descriptor around its AudioSpecificConfig as the
    /// magic cookie. The container is not told of priming: an M4A's length is then its frames, within
    /// one packet of the song's.
    public static func from(_ stream: AdtsStream) -> PacketLayout {
        let frames = stream.frames.count
        let bytes = stream.frames.reduce(0) { $0 + $1.count }
        let seconds = Double(frames * 1024) / Double(stream.sampleRate)
        let bitrate = seconds > 0 ? UInt32(clamping: Int(Double(bytes * 8) / seconds)) : 0
        return PacketLayout(sampleRate: Double(stream.sampleRate), formatID: fourCharCode("aac "), formatFlags: UInt32(stream.objectType),
                            channels: UInt32(stream.channels), framesPerPacket: 1024, cookie: esDescriptor(stream.audioConfig, bitrate: bitrate),
                            packets: stream.frames, packetFrames: Array(repeating: 1024, count: frames), validFrames: nil,
                            primingFrames: 0, remainderFrames: 0)
    }

    /// The ES descriptor: ES id 0, a decoder config for MPEG-4 audio (object 0x40, audio stream 0x15)
    /// holding `config`, and the plain sync layer config.
    public static func esDescriptor(_ config: [UInt8], bitrate: UInt32) -> [UInt8] {
        func be32(_ value: UInt32) -> [UInt8] { [UInt8(value >> 24), UInt8((value >> 16) & 0xFF), UInt8((value >> 8) & 0xFF), UInt8(value & 0xFF)] }
        let specific: [UInt8] = [0x05, UInt8(config.count)] + config
        let decoder: [UInt8] = [0x04, UInt8(13 + specific.count), 0x40, 0x15, 0x00, 0x00, 0x00] + be32(bitrate) + be32(bitrate) + specific
        let sync: [UInt8] = [0x06, 0x01, 0x02]
        let body: [UInt8] = [0x00, 0x00, 0x00] + decoder + sync
        return [0x03, UInt8(body.count)] + body
    }
}

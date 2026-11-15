import Foundation

/// An AAC stream in ADTS as it arrived: the AudioSpecificConfig its first header makes, its rate and
/// channels, and every frame's payload (the headers left out).
public struct AdtsStream: Sendable, Equatable {
    public var audioConfig: [UInt8]
    public var objectType: Int
    public var sampleRate: Int
    public var channels: Int
    public var frames: [[UInt8]]
}

public enum Adts {
    /// The sampling rates an ADTS header names by index.
    public static let rates = [96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350]

    /// Reads every whole frame. Zeros after the last frame (padding a server added) end the stream,
    /// and a last frame cut short is left out. Nil for a stream with no frame, anything other than
    /// zeros after a frame, frames whose settings change partway, a rate or channel count no header
    /// may name, or frames of several raw blocks (a layout the phone's writer is not handed).
    public static func read(_ bytes: [UInt8]) -> AdtsStream? {
        var at = 0
        var frames: [[UInt8]] = []
        var first: (profile: Int, rate: Int, channels: Int)?
        while at + 7 <= bytes.count {
            guard bytes[at] == 0xFF, bytes[at + 1] & 0xF6 == 0xF0 else {
                guard bytes[at...].allSatisfy({ $0 == 0 }) else { return nil }
                break
            }
            let protectionAbsent = bytes[at + 1] & 0x01 == 1
            let profile = Int(bytes[at + 2] >> 6)
            let rate = Int((bytes[at + 2] >> 2) & 0x0F)
            let channels = (Int(bytes[at + 2] & 0x01) << 2) | Int(bytes[at + 3] >> 6)
            let length = (Int(bytes[at + 3] & 0x03) << 11) | (Int(bytes[at + 4]) << 3) | Int(bytes[at + 5] >> 5)
            let blocks = Int(bytes[at + 6] & 0x03) + 1
            let header = protectionAbsent ? 7 : 9
            guard rate < rates.count, channels > 0, blocks == 1, length > header else { return nil }
            if let first, first.profile != profile || first.rate != rate || first.channels != channels { return nil }
            guard at + length <= bytes.count else { break }
            if first == nil { first = (profile, rate, channels) }
            frames.append(Array(bytes[(at + header)..<(at + length)]))
            at += length
        }
        guard let first, !frames.isEmpty else { return nil }
        let objectType = first.profile + 1
        let config: [UInt8] = [UInt8((objectType << 3) | (first.rate >> 1)), UInt8(((first.rate & 1) << 7) | (first.channels << 3))]
        return AdtsStream(audioConfig: config, objectType: objectType, sampleRate: rates[first.rate], channels: first.channels,
                          frames: frames)
    }
}

import Foundation

/// What a download asks the server for before any of its bytes arrive.
public enum DownloadAsk: Sendable, Equatable {
    /// The server's file as it is.
    case asItIs
    /// A copy of at most this many kbps, in the Download format.
    case smaller(maxKbps: Int)
    /// A listed Ogg file: its first bytes are looked at first (`oggDecision`), since only an Ogg Opus
    /// file can be made into one the phone plays without being made again.
    case lookFirst
}

/// The listed types of Ogg files.
private let oggTypes: Set<String> = ["audio/ogg", "application/ogg", "audio/opus", "audio/vorbis"]

/// What a download asks for: a lossy file at or under the quality's cap comes as it is, whatever the
/// format; a lossless or bigger one is made smaller. A file the phone cannot open as it is comes
/// smaller too, at the cap or 320 kbps for Original, except an Ogg file, which is looked at first.
public func downloadAsk(mimeType: String?, bitrate: Int?, quality: StreamQuality) -> DownloadAsk {
    if let kbps = downloadMaxKbps(mimeType: mimeType, bitrate: bitrate, quality: quality) { return .smaller(maxKbps: kbps) }
    if phoneOpens(mimeType) { return .asItIs }
    if let type = mimeType?.lowercased(), oggTypes.contains(trimmed(type.before(";"))) { return .lookFirst }
    return .smaller(maxKbps: quality.kbps ?? 320)
}

/// What a listed Ogg file's first bytes decide.
public enum OggDecision: Sendable, Equatable {
    /// It comes as it is: Ogg Opus (put into a CAF when it arrives on a phone whose system plays
    /// those, else kept and played by Octo's own Opus decoder), or not Ogg at all (a family limit
    /// had the server send another file).
    case asItIs
    /// It comes as a smaller copy in the Download format: Ogg Vorbis or another Ogg codec.
    case smaller
}

public func oggDecision(head: [UInt8]) -> OggDecision {
    switch sniffDownloadAudio(head) {
    case "audio/opus": .asItIs
    case "audio/ogg": .smaller
    default: .asItIs
    }
}

/// About how many bytes a download made at `maxKbps` comes to, for its progress when the server
/// sends no length; 0 for the file as it is or a song of no known length.
public func expectedDownloadBytes(durationMs: Int64, maxKbps: Int?) -> Int64 {
    guard let maxKbps, durationMs > 0 else { return 0 }
    return durationMs * Int64(maxKbps) / 8
}

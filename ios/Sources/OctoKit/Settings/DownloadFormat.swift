import Foundation

/// The type a download is made as when it is made smaller than the server's file. `param` is the
/// Subsonic `format` the server is asked for, `mimeType` what a download of it is labeled before its
/// bytes say otherwise, and `label` what people read. The order is the order the choices show in.
public enum DownloadFormat: String, CaseIterable, StoredChoice {
    case opus = "Opus"
    case aac = "Aac"
    case mp3 = "Mp3"

    public var param: String {
        switch self {
        case .opus: "opus"
        case .aac: "aac"
        case .mp3: "mp3"
        }
    }

    public var mimeType: String {
        switch self {
        case .opus: "audio/opus"
        case .aac: "audio/aac"
        case .mp3: "audio/mpeg"
        }
    }

    public var label: String {
        switch self {
        case .opus: "Opus"
        case .aac: "AAC"
        case .mp3: "MP3"
        }
    }

    /// What one choice says under its name.
    public var detail: String {
        switch self {
        case .opus: "Best sound for its size"
        case .aac: "Close to Opus, opens almost anywhere"
        case .mp3: "Opens anywhere, a little worse for its size"
        }
    }

    /// The choice stored under `name`; anything else, a name from a newer app included, is Opus.
    public static func named(_ name: String?) -> DownloadFormat { stored(name, or: .opus) }

    /// The setting's title and the line under it, also its search line.
    public static let title = "Download format"
    public static let help = "What smaller downloads are saved as"
}

/// Whether this phone's own system plays the Opus files it writes (Opus in CAF), as a short bundled
/// tone found on this version of the system. Nothing stored means it is not known yet. Opus plays
/// either way: without it, the Ogg Opus that arrived is kept and Octo's own Opus decoder plays it.
public enum OpusAbility: String, Sendable, Equatable {
    case yes
    case no
}

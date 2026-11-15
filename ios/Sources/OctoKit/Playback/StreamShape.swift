import Foundation

/// What a server answered a song's stream with: its type and the whole file's length, each when
/// it said. A server can send a song as another file than it listed (a whole FLAC for a listed
/// preview), so a saved part of one file is never continued with the bytes of another.
public struct StreamShape: Equatable, Sendable {
    public var type: String?
    public var total: Int64?
    /// The YouTube video an outside song's stream named (`X-Octo-Video`), when it named one.
    public var video: String?

    public init(type: String?, total: Int64?, video: String? = nil) {
        let plain = type?.split(separator: ";").first.map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
        // A server that does not know the type says it is plain bytes, which names no file.
        self.type = plain.flatMap { $0.isEmpty || $0 == "application/octet-stream" ? nil : $0 }
        self.total = total.flatMap { $0 > 0 ? $0 : nil }
        self.video = video.map { $0.trimmingCharacters(in: .whitespaces) }.flatMap { $0.isEmpty ? nil : $0 }
    }

    /// Whether two answers are of different files: both name a type and the types differ, both
    /// give a whole length and the lengths differ, or both name a video and the videos differ (two
    /// versions of a song found online can share a type and even a length). What one leaves out is
    /// no difference.
    public func differs(from other: StreamShape) -> Bool {
        if let type, let otherType = other.type, type != otherType { return true }
        if let total, let otherTotal = other.total, total != otherTotal { return true }
        if let video, let otherVideo = other.video, video != otherVideo { return true }
        return false
    }
}

/// The shape of a successful answer: a part (206) gives the whole length after the slash of its
/// Content-Range; a whole answer (200) is the file from its start, so its length is the whole.
public func streamShapeOf(status: Int, contentType: String?, contentRange: String?, contentLength: Int64?,
                          video: String? = nil) -> StreamShape {
    let total: Int64?
    if status == 206 {
        total = contentRange?.split(separator: "/").last.flatMap { Int64($0.trimmingCharacters(in: .whitespaces)) }
    } else {
        total = contentLength
    }
    return StreamShape(type: contentType, total: total, video: video)
}

/// A find's stream: no listed bitrate goes with it. A find's bitrate is the preview's, and the
/// server may send a whole FLAC in its place, so a cap applies as it does to a lossless song.
public func findStreamRef(sourceId: String, serverId: String, mimeType: String?) -> StreamRef {
    StreamRef(sourceId: sourceId, serverId: serverId, mimeType: mimeType, bitrate: nil)
}

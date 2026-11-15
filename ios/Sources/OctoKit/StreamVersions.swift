import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// The OpenSubsonic extension an Octo server lists for a caller who may choose the YouTube video
/// an outside song streams from (getStreamVersions, setStreamVersion). A choice plays for everyone
/// on the server; a family's Listener and Kid accounts are not offered it.
public let STREAM_VERSIONS_EXTENSION = "octoStreamVersions"

/// The extension every signed-in caller is offered: the outside songs whose chosen version
/// changed after a cursor (getStreamVersionChanges), so a device drops its saved copies of them.
public let STREAM_VERSION_CHANGES_EXTENSION = "octoStreamVersionChanges"

/// The `video` setStreamVersion takes to go back to Octo's own pick.
public let STREAM_VERSION_AUTO = "auto"

/// The request parameter every stream request of one play carries: see `playToken`.
public let PLAY_TOKEN_PARAM = "octoPlay"

/// The response header an outside song's stream names the video it was made from in.
public let VIDEO_HEADER = "X-Octo-Video"

/// Where an outside song plays from for the caller (`StreamVersions.playsFrom`).
public let PLAYS_FROM_STREAM = "stream"
public let PLAYS_FROM_FILE = "file"
public let PLAYS_FROM_DOWNLOAD = "download"

/// Who picked the video playing (`StreamVersionPlaying.picked`).
public let PICKED_PERSON = "person"
public let PICKED_EARLIER = "earlier"
public let PICKED_OCTO = "octo"

/// What a video is (`StreamVersion.kind`).
public let VERSION_KIND_RELEASE = "release"
public let VERSION_KIND_MUSIC_VIDEO = "musicVideo"
public let VERSION_KIND_VIDEO = "video"

/// The sources a listing could not hear from (`StreamVersions.missed`).
public let MISSED_YOUTUBE_MUSIC = "youtubeMusic"
public let MISSED_YOUTUBE = "youtube"

/// An outside song's versions, as getStreamVersions and setStreamVersion answer: where it plays
/// from for the caller, the video playing and who picked it, the caller's latest heard video when
/// it is another, whether the caller's own download of the song runs, the other videos it can play
/// from, the sources that could not answer, and every id the song has (a pick's answer lists the
/// ids whose saved copies go).
public struct StreamVersions: Decodable, Sendable, Equatable {
    public var id: String
    public var playsFrom: String
    public var playing: StreamVersionPlaying?
    public var heardVideoId: String?
    public var busy: Bool
    public var version: [StreamVersion]
    public var missed: [String]
    public var ids: [String]

    enum CodingKeys: String, CodingKey { case id, playsFrom, playing, heardVideoId, busy, version, missed, ids }

    public init(id: String, playsFrom: String, playing: StreamVersionPlaying? = nil, heardVideoId: String? = nil,
                busy: Bool = false, version: [StreamVersion] = [], missed: [String] = [], ids: [String] = []) {
        self.id = id
        self.playsFrom = playsFrom
        self.playing = playing
        self.heardVideoId = heardVideoId
        self.busy = busy
        self.version = version
        self.missed = missed
        self.ids = ids
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.string(.id) ?? ""
        playsFrom = c.string(.playsFrom) ?? ""
        playing = try? c.decodeIfPresent(StreamVersionPlaying.self, forKey: .playing)
        heardVideoId = c.string(.heardVideoId)
        busy = c.bool(.busy) ?? false
        version = (try? c.decodeIfPresent([StreamVersion].self, forKey: .version)) ?? []
        missed = (try? c.decodeIfPresent([String].self, forKey: .missed)) ?? []
        ids = (try? c.decodeIfPresent([String].self, forKey: .ids)) ?? []
    }
}

/// One other video the song can stream from. `duration` is the video's, in seconds.
public struct StreamVersion: Decodable, Sendable, Equatable {
    public var videoId: String
    public var title: String
    public var channel: String?
    public var duration: Int?
    public var kind: String

    enum CodingKeys: String, CodingKey { case videoId, title, channel, duration, kind }

    public init(videoId: String, title: String, channel: String? = nil, duration: Int? = nil, kind: String) {
        self.videoId = videoId
        self.title = title
        self.channel = channel
        self.duration = duration
        self.kind = kind
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        videoId = c.string(.videoId) ?? ""
        title = c.string(.title) ?? ""
        channel = c.string(.channel)
        duration = c.int(.duration)
        kind = c.string(.kind) ?? ""
    }
}

/// The video the song streams from now, with what is known of it and who picked it. `mine` is
/// whether the caller did; `pickedAt` is ISO 8601.
public struct StreamVersionPlaying: Decodable, Sendable, Equatable {
    public var videoId: String
    public var title: String?
    public var channel: String?
    public var duration: Int?
    public var kind: String?
    public var picked: String
    public var pickedBy: String?
    public var mine: Bool
    public var pickedAt: String?

    enum CodingKeys: String, CodingKey { case videoId, title, channel, duration, kind, picked, pickedBy, mine, pickedAt }

    public init(videoId: String, title: String? = nil, channel: String? = nil, duration: Int? = nil, kind: String? = nil,
                picked: String, pickedBy: String? = nil, mine: Bool = false, pickedAt: String? = nil) {
        self.videoId = videoId
        self.title = title
        self.channel = channel
        self.duration = duration
        self.kind = kind
        self.picked = picked
        self.pickedBy = pickedBy
        self.mine = mine
        self.pickedAt = pickedAt
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        videoId = c.string(.videoId) ?? ""
        title = c.string(.title)
        channel = c.string(.channel)
        duration = c.int(.duration)
        kind = c.string(.kind)
        picked = c.string(.picked) ?? PICKED_OCTO
        pickedBy = c.string(.pickedBy)
        mine = c.bool(.mine) ?? false
        pickedAt = c.string(.pickedAt)
    }
}

/// The outside songs whose chosen version changed after a cursor, and the cursor to send next time
/// (`epoch`, `seq`). Not `complete`: another epoch, a cursor older than the server keeps, or more
/// changes than one answer lists, so every saved outside song goes. An answer that leaves
/// `complete` out is read as not complete.
public struct StreamVersionChanges: Decodable, Sendable, Equatable {
    public var epoch: String
    public var seq: Int64
    public var complete: Bool
    public var change: [StreamVersionChange]

    enum CodingKeys: String, CodingKey { case epoch, seq, complete, change }

    public init(epoch: String, seq: Int64, complete: Bool, change: [StreamVersionChange] = []) {
        self.epoch = epoch
        self.seq = seq
        self.complete = complete
        self.change = change
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        epoch = c.string(.epoch) ?? ""
        seq = c.int64(.seq) ?? 0
        complete = c.bool(.complete) ?? false
        change = (try? c.decodeIfPresent([StreamVersionChange].self, forKey: .change)) ?? []
    }
}

/// One change: its number and every id of its song.
public struct StreamVersionChange: Decodable, Sendable, Equatable {
    public var seq: Int64
    public var ids: [String]

    enum CodingKeys: String, CodingKey { case seq, ids }

    public init(seq: Int64, ids: [String]) {
        self.seq = seq
        self.ids = ids
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        seq = c.int64(.seq) ?? 0
        ids = (try? c.decodeIfPresent([String].self, forKey: .ids)) ?? []
    }
}

/// The token every stream request of one play carries (`PLAY_TOKEN_PARAM`): the queue entry's id
/// and how many times a version pick restarted it. A rebuffer, seek, reconnect, Repeat One or
/// Previous is the same play and keeps it.
public func playToken(entryId: String, restarts: Int) -> String { "\(entryId).\(restarts)" }

extension SubsonicClient {
    /// An outside song's versions: the video it streams from and the other videos it can, which
    /// the server lists from YouTube (up to ten seconds). `id` is the song's id as it streams. Only
    /// for servers that list `STREAM_VERSIONS_EXTENSION`.
    public func streamVersions(_ id: String) async throws -> StreamVersions {
        try await get("getStreamVersions", [("id", id)], key: "streamVersions", timeout: 30)
    }

    /// Makes `video` (one of streamVersions' rows) the one the outside song streams from for
    /// everyone on the server, from each play's next start, or with `STREAM_VERSION_AUTO` goes back
    /// to Octo's own pick. Answers the song's versions as they are now, `ids` every id the song
    /// has. A refusal is the server's error in its own words (`OctoError`).
    @discardableResult
    public func setStreamVersion(_ id: String, video: String) async throws -> StreamVersions {
        try await get("setStreamVersion", [("id", id), ("video", video)], key: "streamVersions", timeout: 30)
    }

    /// The outside songs whose chosen version changed after a cursor, as the last answer gave it;
    /// both nil asks for every change the server keeps. Only for servers that list
    /// `STREAM_VERSION_CHANGES_EXTENSION`.
    public func streamVersionChanges(epoch: String?, since: Int64?) async throws -> StreamVersionChanges {
        var params: [(String, String)] = []
        if let epoch { params.append(("epoch", epoch)) }
        if let since { params.append(("since", String(since))) }
        return try await get("getStreamVersionChanges", params, key: "streamVersionChanges")
    }
}

import Foundation

/// The song a Choose the version sheet was opened for: the queue entry playing it, its id as it
/// streams, and what to call it.
public struct VersionsSong: Sendable, Equatable {
    public var entryId: String
    public var songId: String
    public var title: String
    public var artist: String

    public init(entryId: String, songId: String, title: String, artist: String) {
        self.entryId = entryId
        self.songId = songId
        self.title = title
        self.artist = artist
    }
}

/// The server's answer on the sheet. `checking` is the video a pick waits on
/// (`STREAM_VERSION_AUTO` while going back to Octo's pick); `refused` is the one whose pick the
/// server turned down, its words in `refusal`.
public struct StreamVersionsShown: Sendable, Equatable {
    public var song: VersionsSong
    public var answer: StreamVersions
    public var checking: String?
    public var refused: String?
    public var refusal: String?

    public init(song: VersionsSong, answer: StreamVersions, checking: String? = nil, refused: String? = nil,
                refusal: String? = nil) {
        self.song = song
        self.answer = answer
        self.checking = checking
        self.refused = refused
        self.refusal = refusal
    }

    /// Whether a row can be picked: the song streams for this listener and no download of theirs
    /// is running.
    public var picksOpen: Bool { answer.playsFrom == PLAYS_FROM_STREAM && !answer.busy }

    /// The video playing, while the song streams for this listener.
    public var playing: StreamVersionPlaying? { answer.playsFrom == PLAYS_FROM_STREAM ? answer.playing : nil }

    /// The other videos, while a row can be picked.
    public var rows: [StreamVersion] { picksOpen ? answer.version : [] }

    /// Whether Go back to Octo's pick is offered: a person's pick, or one from before, stands.
    public var canUndo: Bool {
        answer.playsFrom == PLAYS_FROM_STREAM
            && (answer.playing?.picked == PICKED_PERSON || answer.playing?.picked == PICKED_EARLIER)
    }

    public var notice: String? { StreamVersionWords.notice(answer) }

    /// The list's own line when it has no other version.
    public var emptyText: String? { picksOpen && answer.version.isEmpty ? StreamVersionWords.nothing : nil }

    /// Whether the player's current entry is still this sheet's song.
    public func playingHere(entryId: String?, songId: String?) -> Bool {
        entryId == song.entryId && songId == song.songId
    }
}

/// What the Choose the version sheet shows. It changes only through `on`, so every app's sheet
/// behaves the same.
public enum StreamVersionsSheetState: Sendable, Equatable {
    case closed
    /// Asking the server for the list.
    case looking(VersionsSong)
    /// No answer: the server could not be reached (`server`), or it could not reach YouTube at
    /// all. Try again asks once more.
    case couldNotReach(VersionsSong, server: Bool)
    /// The server said no in its own words, as for a song that can't be given another version.
    case unavailable(VersionsSong, message: String)
    case shown(StreamVersionsShown)

    /// The song it is for; nil once closed.
    public var song: VersionsSong? {
        switch self {
        case .closed: nil
        case .looking(let song), .couldNotReach(let song, _), .unavailable(let song, _): song
        case .shown(let shown): shown.song
        }
    }
}

/// What happens to a sheet. A pick names the entry and song playing when it was tapped, so a pick
/// for a song no longer playing is refused.
public enum StreamVersionsEvent: Sendable, Equatable {
    case open(VersionsSong)
    case listed(StreamVersions)
    /// The listing failed: `message` is the server's refusal, nil when the server could not be
    /// reached.
    case listFailed(message: String?)
    case retry
    case pick(videoId: String, playingEntryId: String?, playingSongId: String?)
    case undo(playingEntryId: String?, playingSongId: String?)
    case picked(StreamVersions)
    case pickFailed(message: String)
    /// The player's current entry is now this one (nil: nothing plays).
    case entryChanged(String?)
    case close
}

extension StreamVersionsSheetState {
    /// The sheet after `event`. An event that does not apply leaves the state as it was.
    public func on(_ event: StreamVersionsEvent) -> StreamVersionsSheetState {
        switch event {
        case .open(let song):
            return .looking(song)
        case .close:
            return .closed
        case .entryChanged(let entry):
            guard let current = song, current.entryId != entry else { return self }
            return .closed
        case .listed(let answer):
            guard case .looking(let song) = self, answer.id == song.songId else { return self }
            return Self.listed(song, answer)
        case .listFailed(let message):
            guard case .looking(let song) = self else { return self }
            guard let message else { return .couldNotReach(song, server: true) }
            return .unavailable(song, message: message)
        case .retry:
            guard case .couldNotReach(let song, _) = self else { return self }
            return .looking(song)
        case .pick(let video, let entry, let songId):
            guard case .shown(var shown) = self, shown.checking == nil, shown.playingHere(entryId: entry, songId: songId),
                  shown.rows.contains(where: { $0.videoId == video }) else { return self }
            shown.checking = video
            shown.refused = nil
            shown.refusal = nil
            return .shown(shown)
        case .undo(let entry, let songId):
            guard case .shown(var shown) = self, shown.checking == nil, shown.playingHere(entryId: entry, songId: songId),
                  shown.canUndo else { return self }
            shown.checking = STREAM_VERSION_AUTO
            shown.refused = nil
            shown.refusal = nil
            return .shown(shown)
        case .picked(let answer):
            guard case .shown(let shown) = self, shown.checking != nil, answer.id == shown.song.songId else { return self }
            return .shown(StreamVersionsShown(song: shown.song, answer: answer))
        case .pickFailed(let message):
            guard case .shown(var shown) = self, let checking = shown.checking else { return self }
            shown.checking = nil
            shown.refused = checking
            shown.refusal = message
            return .shown(shown)
        }
    }

    /// A listing that found no video at all because neither source answered is one that could not
    /// reach YouTube.
    private static func listed(_ song: VersionsSong, _ answer: StreamVersions) -> StreamVersionsSheetState {
        if answer.playsFrom == PLAYS_FROM_STREAM, answer.version.isEmpty,
           answer.missed.contains(MISSED_YOUTUBE_MUSIC), answer.missed.contains(MISSED_YOUTUBE) {
            return .couldNotReach(song, server: false)
        }
        return .shown(StreamVersionsShown(song: song, answer: answer))
    }
}

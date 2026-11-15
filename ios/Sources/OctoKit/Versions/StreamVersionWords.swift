import Foundation

/// Every word the apps say about an outside song's versions: the player's More item and the
/// Choose the version sheet. Android and the desktop say the same; both are checked against
/// fixtures/stream-versions-words.json. A streamed outside song is the song itself, so no word
/// calls it anything else.
public enum StreamVersionWords {
    public static let menuRow = "Try another version"
    public static let title = "Choose the version"
    public static let done = "Done"
    public static let playingNow = "Playing now"
    public static let otherVersions = "Other versions"
    public static let yourPick = "Your pick"
    public static let pickedBySomeone = "Picked by someone on this server"
    public static let pickFromBefore = "A pick from before"
    public static let octosPick = "Octo's pick"
    public static let nextStart = "Plays from your next start"
    public static let kindRelease = "Official audio"
    public static let kindMusicVideo = "Music video"
    public static let kindVideo = "Video"
    public static let looking = "Looking on YouTube"
    public static let couldNotReach = "Could not reach YouTube just now."
    public static let serverUnreachable = "Could not reach your server just now."
    public static let tryAgain = "Try again"
    public static let nothing = "No other versions found."
    public static let missedYouTubeMusic = "YouTube Music could not answer this time, so this list may be short."
    public static let missedYouTube = "YouTube could not answer this time, so this list may be short."
    public static let checking = "Checking"
    public static let playsFromDownload = "This server adds every song it plays to the library, so a song's version can't be chosen here."
    public static let playsFromFile = "This song plays from a file in your library, so its version can't be chosen here."
    public static let busy = "This song is being added to your library right now. You can choose its version once that finishes."
    public static let note = "The version you choose plays for everyone on this server, on every device."
    public static let goBack = "Go back to Octo's pick"
    public static let moreBelow = "More below"
    public static let pickUnreachable = "Could not reach your server. Try again."

    /// Between the parts of a row's detail line.
    static let dot = " \u{00B7} "

    public static func pickedBy(_ name: String) -> String { "Picked by \(name)" }

    /// The sheet's muted second line: the song and its artist.
    public static func songLine(title: String, artist: String) -> String {
        artist.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? title : "\(title) by \(artist)"
    }

    /// What a video is, in words; nil for a kind the app does not know.
    public static func kind(_ kind: String?) -> String? {
        switch kind {
        case VERSION_KIND_RELEASE: kindRelease
        case VERSION_KIND_MUSIC_VIDEO: kindMusicVideo
        case VERSION_KIND_VIDEO: kindVideo
        default: nil
        }
    }

    /// A video's length as m:ss.
    public static func length(_ seconds: Int) -> String {
        let rest = seconds % 60
        return "\(seconds / 60):\(rest < 10 ? "0" : "")\(rest)"
    }

    /// A row's line under its title: channel, length and kind, each only when known.
    public static func rowDetail(channel: String?, seconds: Int?, kind: String?) -> String {
        let named = channel?.trimmingCharacters(in: .whitespacesAndNewlines)
        return [
            named.flatMap { $0.isEmpty ? nil : $0 },
            seconds.flatMap { $0 >= 0 ? length($0) : nil },
            Self.kind(kind),
        ].compactMap { $0 }.joined(separator: dot)
    }

    /// Who picked the video playing, and when the caller last heard another, that this one plays
    /// from their next start.
    public static func playingNote(_ playing: StreamVersionPlaying, heardVideoId: String?) -> String {
        let by = playing.pickedBy?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let who: String
        switch playing.picked {
        case PICKED_PERSON: who = playing.mine ? yourPick : (by.isEmpty ? pickedBySomeone : pickedBy(by))
        case PICKED_EARLIER: who = pickFromBefore
        default: who = octosPick
        }
        guard let heardVideoId, heardVideoId != playing.videoId else { return who }
        return who + dot + nextStart
    }

    /// The line above the list when it holds no choices or may be short: the download modes, a
    /// file, the caller's own download running, a source that did not answer. Nil when the list
    /// is whole.
    public static func notice(_ answer: StreamVersions) -> String? {
        if answer.playsFrom == PLAYS_FROM_DOWNLOAD { return playsFromDownload }
        if answer.playsFrom == PLAYS_FROM_FILE { return playsFromFile }
        if answer.busy { return busy }
        if answer.missed.contains(MISSED_YOUTUBE_MUSIC) { return missedYouTubeMusic }
        if answer.missed.contains(MISSED_YOUTUBE) { return missedYouTube }
        return nil
    }

    /// The line a sheet shows for where it stands: looking, no answer, the server's refusal, or
    /// the notice above the list.
    public static func stateLine(_ state: StreamVersionsSheetState) -> String? {
        switch state {
        case .closed: nil
        case .looking: looking
        case .couldNotReach(_, let server): server ? serverUnreachable : couldNotReach
        case .unavailable(_, let message): message
        case .shown(let shown): shown.notice
        }
    }
}

import Foundation

/// How Octo names a play of a server song to the server: the value a scout's reads carry in
/// `X-Octo-Purpose`, so the server tells a look at a song (its start or end, read for a blend)
/// apart from the song starting to play, and the name of this run's plays.
public enum StreamPlays {
    public static let scoutPurpose = "scout"
    /// Names this run's plays: queue entries are numbered from the start again each run.
    public static let launch = String(UUID().uuidString.prefix(8)).lowercased()
}

/// The play token for one play of a queue entry (`playToken`): the entry named with the run, and
/// how many times a version picked for its song started it over.
public func streamPlayToken(entryId: String, restarts: Int, launch: String = StreamPlays.launch) -> String {
    playToken(entryId: "\(launch)-\(entryId)", restarts: restarts)
}

/// Whether "Try another version" shows for the song playing: a song found online, streaming from
/// the server in use while signed in, on a server that lets this person pick; never a station.
public func offersStreamVersions(extensions: [String], signedIn: Bool, isFound: Bool, isStation: Bool) -> Bool {
    signedIn && isFound && !isStation && extensions.contains("\(STREAM_VERSIONS_EXTENSION):1")
}

/// The write generation of each server song's saved copies. Dropping a song's copies (its version
/// changed on the server) moves its generation on first, and a write, description or answer begun
/// under an older generation is refused, so nothing of the old file lands after the drop.
public struct StreamWriteFence: Sendable, Equatable {
    /// By "<source>|<song id there>"; a song never bumped is at 0.
    private var generations: [String: Int] = [:]

    public init() {}

    /// The generation writes for the song saved under `key` are made at now.
    public func generation(of key: String) -> Int { generations[savedSongOf(key)] ?? 0 }

    /// Moves the song's generation on.
    public mutating func bump(sourceId: String, serverId: String) {
        generations["\(sourceId)|\(serverId)", default: 0] += 1
    }

    /// Whether a write begun at `generation` may land for `key`.
    public func admits(_ key: String, generation: Int) -> Bool { generation == self.generation(of: key) }
}

/// Songs whose saved copies wait to be dropped because they play now (the song on now, or the one
/// leaving in a blend): their generation moved on at once, so nothing more of them is saved, and
/// their copies go once they no longer play, so a song mid-play never loses the bytes it reads.
/// Songs are named by their id on the server.
public struct VersionDrops: Sendable, Equatable {
    public private(set) var waiting: Set<String> = []

    public init() {}

    /// Splits the songs to drop: those playing (not the one opening now) wait; the rest, answered,
    /// go now.
    public mutating func take(_ songs: Set<String>, playing: Set<String>, opening: String?) -> Set<String> {
        let wait = songs.filter { playing.contains($0) && $0 != opening }
        waiting.formUnion(wait)
        return songs.subtracting(wait)
    }

    /// The waiting songs that no longer play, or open now: they go now.
    public mutating func settle(playing: Set<String>, opening: String?) -> Set<String> {
        let done = waiting.filter { !playing.contains($0) || $0 == opening }
        waiting.subtract(done)
        return done
    }
}

/// The songs of a server whose saved copies were saved from a video (their stream named one), by
/// their id there: songs found online, whatever their id looks like. With the catalog's finds, what
/// goes when the list of changes cannot say which changed.
public func videoSavedSongs(_ index: StreamCacheIndex, sourceId: String) -> Set<String> {
    let prefix = sourceId + "|"
    var out = Set<String>()
    for (key, entry) in index.entries where entry.video != nil {
        let song = savedSongOf(key)
        guard song.hasPrefix(prefix) else { continue }
        out.insert(String(song.dropFirst(prefix.count)))
    }
    return out
}

/// Where a server's list of changes was read to, as the phone keeps it ("<seq> <epoch>"), and back;
/// nil for anything else.
public func versionCursorText(_ cursor: StreamVersionCursor) -> String { "\(cursor.seq) \(cursor.epoch)" }

public func versionCursor(from text: String) -> StreamVersionCursor? {
    guard let space = text.firstIndex(of: " "), let seq = Int64(text[..<space]) else { return nil }
    let epoch = String(text[text.index(after: space)...])
    return epoch.isEmpty ? nil : StreamVersionCursor(epoch: epoch, seq: seq)
}

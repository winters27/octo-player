import Foundation

// Keeps playlists on the phone and on the server the same, both ways. Each linked playlist is
// compared with what both sides last agreed on: the phone's side changed when it was edited after
// that (updatedAt past syncedAt), the server's when its changed time or song count differ from the
// ones noted then. Only one side changed: that side's playlist is copied to the other. Both
// changed: the server wins for the songs, and a name changed on the phone wins over the server's
// and is sent there. A playlist gone from the server goes from the phone too, unless it was
// changed here since; then it stays as a playlist only on the phone.

/// Octo's stations are also read-only playlists with ids starting "or".
private let stationPrefix = "or"

/// A server playlist as the list of them shows it. The stamp is its changed time and song count
/// together, which move whenever it changes.
public struct ServerPlaylist: Sendable, Hashable {
    public var id: String
    public var name: String
    public var stamp: String

    public init(_ id: String, _ name: String, _ stamp: String) {
        self.id = id
        self.name = name
        self.stamp = stamp
    }
}

/// A playlist on the phone linked to a server one, and what both sides last agreed on.
public struct LinkedPlaylist: Sendable, Hashable {
    public var localId: String
    public var serverId: String
    public var name: String
    /// Changed on the phone since both sides last agreed.
    public var edited: Bool
    public var syncedName: String?
    public var syncedStamp: String?
    /// A family playlist, whose own changes are sent on top of everyone else's.
    public var family: Bool
    /// A family playlist the listener may not change here, or an imported playlist's copy: it only
    /// follows the server.
    public var pullOnly: Bool
    /// The listener's own playlist on a server listing octoPlaylistSongs: changed on both sides, it
    /// sends its own changes on top of the server's, as a family playlist does.
    public var ownRules: Bool

    public init(_ localId: String, _ serverId: String, _ name: String, _ edited: Bool, _ syncedName: String?, _ syncedStamp: String?,
                family: Bool = false, pullOnly: Bool = false, ownRules: Bool = false) {
        self.ownRules = ownRules
        self.localId = localId
        self.serverId = serverId
        self.name = name
        self.edited = edited
        self.syncedName = syncedName
        self.syncedStamp = syncedStamp
        self.family = family
        self.pullOnly = pullOnly
    }
}

public enum PlaylistStep: Sendable, Hashable {
    /// New on the server: make a copy on the phone.
    case importing(serverId: String, stamp: String)
    /// Take the server's songs. With `keepName` the phone's name stays and is sent to the server;
    /// otherwise the server's name is taken too.
    case pull(localId: String, serverId: String, stamp: String, keepName: Bool)
    /// Send the phone's songs and name.
    case push(localId: String, serverId: String)
    /// Make a playlist from the phone on the server, and link the two.
    case create(localId: String)
    /// Gone from the server and not changed here: delete the phone's copy.
    case remove(localId: String)
    /// Gone from the server, or no longer one to keep in step, but changed here: keep it as a
    /// playlist only on the phone.
    case unlink(localId: String)
    /// Deleted on the phone: delete it on the server.
    case deleteOnServer(serverId: String)
    /// A deletion still waiting for a playlist the server no longer has.
    case forgetDeletion(serverId: String)
}

public func stampOf(_ changed: String?, _ songCount: Int) -> String { "\(changed ?? "")|\(songCount)" }

/// Server playlists worth a copy on the phone: the signed-in user's own and the family's they may
/// change, leaving out read-only ones and stations, which the stations shelf shows, and the
/// family's, which a listener without editing keeps as a copy that only follows the server.
/// An imported playlist's copy (octoImport) comes too, as a copy that only follows the server.
public func importable(_ playlists: [ServerPlaylistInfo], stationIds: Set<String>, username: String) -> [ServerPlaylistInfo] {
    playlists.filter {
        !stationIds.contains($0.id)
            && (isFamilyPlaylist(owner: $0.owner, username: username, octoFamily: $0.octoFamily)
                || $0.octoImport
                || (!$0.readonly && ownedBy($0.owner, username)))
    }
}

/// The playlists a new one made on a server too old to answer with it can be: the user's own
/// by that name not linked yet, never the family's.
public func newestNamedCandidates(_ playlists: [ServerPlaylistInfo], name: String, username: String, linked: Set<String>) -> [ServerPlaylistInfo] {
    importable(playlists, stationIds: [], username: username)
        .filter {
            $0.name == name && !linked.contains($0.id) && !$0.octoImport
                && !isFamilyPlaylist(owner: $0.owner, username: username, octoFamily: $0.octoFamily)
        }
}

/// How long the phone's copy of the server's playlists counts as fresh when the playlists come on
/// screen.
public let playlistsFreshMs: Int64 = 60_000

/// Whether the server's playlists are worth bringing in again now.
public func playlistsStale(_ now: Int64, _ lastMs: Int64) -> Bool { now - lastMs >= playlistsFreshMs }

/// Playlists that could be stations the server did not mark read-only, so the list of stations is
/// worth asking for.
public func stationSuspects(_ playlists: [ServerPlaylistInfo]) -> [ServerPlaylistInfo] {
    playlists.filter { !$0.readonly && $0.id.hasPrefix(stationPrefix) }
}

/// A playlist with no owner named, or when the user is not known, counts as the user's own.
private func ownedBy(_ owner: String?, _ username: String) -> Bool {
    guard let owner, !trimmed(owner).isEmpty else { return true }
    return trimmed(username).isEmpty || owner.lowercased() == username.lowercased()
}

/// Everything that brings both sides together. `others` are server playlists that exist but are
/// not to be kept in step; `waiting` are phone playlists to be made on the server; `deleted` are
/// server playlists deleted on the phone and not yet on the server.
public func planPlaylists(server: [ServerPlaylist], others: Set<String>, linked: [LinkedPlaylist], waiting: [String],
                          deleted: Set<String>) -> [PlaylistStep] {
    var byId: [String: ServerPlaylist] = [:]
    for playlist in server where byId[playlist.id] == nil { byId[playlist.id] = playlist }
    var steps: [PlaylistStep] = []
    for id in deleted.sorted() {
        steps.append(byId[id] != nil ? .deleteOnServer(serverId: id) : .forgetDeletion(serverId: id))
    }
    for playlist in linked {
        if let step = planLinked(byId[playlist.serverId], playlist, stillThere: others.contains(playlist.serverId)) { steps.append(step) }
    }
    let known = Set(linked.map(\.serverId))
    for playlist in server where !known.contains(playlist.id) && !deleted.contains(playlist.id) {
        steps.append(.importing(serverId: playlist.id, stamp: playlist.stamp))
    }
    steps += waiting.map { .create(localId: $0) }
    return steps
}

/// What one linked playlist needs, given the server's playlist (nil when the server no longer
/// lists it as one to keep). Nil when both agree.
public func planLinked(_ server: ServerPlaylist?, _ playlist: LinkedPlaylist, stillThere: Bool = false) -> PlaylistStep? {
    guard let server else {
        return playlist.edited || stillThere ? .unlink(localId: playlist.localId) : .remove(localId: playlist.localId)
    }
    let serverChanged = server.stamp != playlist.syncedStamp
    if playlist.pullOnly {
        return serverChanged || playlist.edited
            ? .pull(localId: playlist.localId, serverId: server.id, stamp: server.stamp, keepName: false) : nil
    }
    if serverChanged && playlist.edited {
        // Both changed: a family playlist, and the listener's own on a server listing
        // octoPlaylistSongs, sends the phone's own changes on top of everyone else's.
        if playlist.family || playlist.ownRules { return .push(localId: playlist.localId, serverId: server.id) }
        // Both changed: the server's songs, and the phone's name if it was renamed here.
        let renamedHere = playlist.name != playlist.syncedName && playlist.name != server.name
        return .pull(localId: playlist.localId, serverId: server.id, stamp: server.stamp, keepName: renamedHere)
    }
    if serverChanged { return .pull(localId: playlist.localId, serverId: server.id, stamp: server.stamp, keepName: false) }
    if playlist.edited { return .push(localId: playlist.localId, serverId: server.id) }
    return nil
}

/// One song of a phone playlist as syncing sees it.
public struct PlaylistSyncRow: Sendable, Hashable {
    public var trackId: String
    public var serverSongId: String?

    public init(_ trackId: String, _ serverSongId: String? = nil) {
        self.trackId = trackId
        self.serverSongId = serverSongId
    }
}

/// The server song to send for a row: the copy it came from while the song still has it,
/// otherwise the song's copy on the server (the same one every time), otherwise the server song it
/// came from, like a find. Nil for a song only on the phone, which the server's copy goes without.
public func serverSongFor(_ row: PlaylistSyncRow, _ copies: [String]) -> String? {
    if let serverSongId = row.serverSongId, copies.contains(serverSongId) { return serverSongId }
    if let lowest = copies.min() { return lowest }
    if isFind(row.trackId) { return String(row.trackId.dropFirst(findPrefix.count)) }
    return row.serverSongId
}

/// The server song to send for a row on a server listing octoPlaylistSongs: the server's own id
/// for a row that came from it as a song found online (an id that is none of the song's copies and
/// that the library links to no song: `libraryIds` are the server ids the library links), never a
/// library song matched to it by title; otherwise serverSongFor's. A library row whose copy moved
/// keeps the id the server's list still holds.
public func outsideSongFor(_ row: PlaylistSyncRow, _ copies: [String], libraryIds: Set<String>) -> String? {
    if let own = row.serverSongId, !copies.contains(own), !libraryIds.contains(own) { return own }
    return serverSongFor(row, copies)
}

/// The changed time a stamp (stampOf) holds, as the server sent it; nil when it holds none.
public func changedOf(_ stamp: String?) -> String? {
    guard let stamp, let bar = stamp.lastIndex(of: "|") else { return nil }
    let changed = String(stamp[..<bar])
    return changed.isEmpty ? nil : changed
}

/// Songs as pushed, less the ones the server skipped: the base a push keeps when its write-back
/// was refused.
public func withoutSkipped(_ songs: [String], _ skipped: [String]) -> [String] {
    let out = Set(skipped)
    return songs.filter { !out.contains($0) }
}

/// A server playlist's songs as phone rows: each server song with the library song or find it is.
/// `trackIds` line up with `serverSongIds`.
public func serverRows(_ serverSongIds: [String], _ trackIds: [String]) -> [PlaylistSyncRow] {
    zip(serverSongIds, trackIds).map { PlaylistSyncRow($1, $0) }
}

/// The rows after taking the server's songs: the server's in its order, with each song only on the
/// phone kept after the song it followed here, or at the start when none came before it.
public func withPhoneOnly(_ server: [PlaylistSyncRow], _ local: [PlaylistSyncRow],
                          phoneOnly: (PlaylistSyncRow) -> Bool) -> [PlaylistSyncRow] {
    var firstAt: [String: Int] = [:]
    for (index, row) in server.enumerated() where firstAt[row.trackId] == nil { firstAt[row.trackId] = index }
    var after: [Int: [PlaylistSyncRow]] = [:]
    var anchor = -1
    for row in local {
        if phoneOnly(row) {
            after[anchor, default: []].append(row)
        } else if let at = firstAt[row.trackId] {
            anchor = at
        }
    }
    var rows = after[-1] ?? []
    for (index, row) in server.enumerated() {
        rows.append(row)
        rows += after[index] ?? []
    }
    return rows
}

/// The line under a playlist kept with a server that holds songs only on this phone.
public func phoneOnlyNote(_ count: Int) -> String {
    count == 1
        ? "1 song is only on this phone and is not in the server's copy"
        : "\(count) songs are only on this phone and are not in the server's copy"
}

/// What the phone changed in a family playlist, as Octo takes it: songs taken out by their place
/// in the server's list now, and songs added at the end. `base` is the server's songs when both
/// last agreed, `phone` the songs the phone holds now, `current` the server's songs now. Only
/// counts matter (a family playlist keeps the server's order): a song the phone has fewer of than
/// `base` comes out of `current` that many times, last places first, unless someone else took it
/// out already; a song the phone has more of goes on the end.
public struct FamilyChanges: Equatable, Sendable {
    public var removeAt: [Int]
    public var add: [String]
    public init(removeAt: [Int], add: [String]) {
        self.removeAt = removeAt
        self.add = add
    }
    public var none: Bool { removeAt.isEmpty && add.isEmpty }
}

public func familyChanges(base: [String], phone: [String], current: [String]) -> FamilyChanges {
    var had: [String: Int] = [:]
    for id in base { had[id, default: 0] += 1 }
    var has: [String: Int] = [:]
    for id in phone { has[id, default: 0] += 1 }
    var removeAt: [Int] = []
    for (id, count) in had {
        let out = count - (has[id] ?? 0)
        guard out > 0 else { continue }
        removeAt += current.indices.filter { current[$0] == id }.suffix(out)
    }
    var seen: [String: Int] = [:]
    let add = phone.filter { id in
        seen[id, default: 0] += 1
        return seen[id, default: 0] > (had[id] ?? 0)
    }
    return FamilyChanges(removeAt: removeAt.sorted(), add: add)
}

/// The songs both sides last agreed on, as the phone keeps them, or nil when none were kept.
public func syncedSongsOf(_ stored: String?) -> [String]? {
    stored.map { $0.split(separator: "\n").map(String.init) }
}

public func syncedSongsText(_ ids: [String]) -> String { ids.joined(separator: "\n") }

/// The ids rows keep when a playlist's songs are swapped for the server's: each new row takes the
/// id of the first unused phone row of the same song, in order, so a row that is picked, or that
/// Undo is about to take back out, is still there. Nil for a row new to the phone.
public func keptRowIds(_ local: [(id: Int64, trackId: String)], _ rows: [PlaylistSyncRow]) -> [Int64?] {
    var free: [String: [Int64]] = [:]
    for row in local { free[row.trackId, default: []].append(row.id) }
    return rows.map { row in
        guard var ids = free[row.trackId], !ids.isEmpty else { return nil }
        let id = ids.removeFirst()
        free[row.trackId] = ids
        return id
    }
}

/// What a family push sends and keeps. `changes` go to the server; `baseAfterSend` is kept as the
/// songs both sides agree on once they are there; `held` are the songs (one entry per copy) whose
/// removal waits.
public struct FamilySend: Equatable, Sendable {
    public var changes: FamilyChanges
    public var baseAfterSend: [String]
    public var held: [String]
    public init(changes: FamilyChanges, baseAfterSend: [String], held: [String]) {
        self.changes = changes
        self.baseAfterSend = baseAfterSend
        self.held = held
    }
}

/// Decides a family push. Removals count places over the list Octo shows, so they wait while Octo
/// lists fewer songs than it holds (`listedCount`, the playlist's count in the list of playlists,
/// nil when not read) and while it is not known whether the listener hears only clean songs
/// (`cleanOnly` nil). A listener who hears only clean songs is shown too few songs to count from,
/// so their removals are dropped and the songs come back with the server's list.
public func familySend(base: [String], phone: [String], current: [String], listedCount: Int?, cleanOnly: Bool?) -> FamilySend {
    let wanted = familyChanges(base: base, phone: phone, current: current)
    if wanted.removeAt.isEmpty { return FamilySend(changes: wanted, baseAfterSend: phone, held: []) }
    let adds = FamilyChanges(removeAt: [], add: wanted.add)
    if cleanOnly == true { return FamilySend(changes: adds, baseAfterSend: phone, held: []) }
    if cleanOnly == nil || listedCount != current.count {
        return FamilySend(changes: adds, baseAfterSend: base + wanted.add, held: wanted.removeAt.map { current[$0] })
    }
    return FamilySend(changes: wanted, baseAfterSend: phone, held: [])
}

/// Whether a family send reached the server although its answer was lost: every song it added
/// is there as many more times as it was added, and every song it took out is there as many
/// fewer times. Changes by others meanwhile do not hide it.
public func familySendLanded(current: [String], after: [String], changes: FamilyChanges) -> Bool {
    func counts(_ ids: [String]) -> [String: Int] {
        var out: [String: Int] = [:]
        for id in ids { out[id, default: 0] += 1 }
        return out
    }
    let before = counts(current)
    let now = counts(after)
    let added = counts(changes.add)
    let removed = counts(changes.removeAt.filter { current.indices.contains($0) }.map { current[$0] })
    return added.allSatisfy { now[$0.key, default: 0] >= before[$0.key, default: 0] + $0.value }
        && removed.allSatisfy { now[$0.key, default: 0] <= before[$0.key, default: 0] - $0.value }
}

/// The rows after taking the server's songs, less the songs whose removal waits: for each held
/// song, its last row from the server goes.
public func withoutHeld(_ rows: [PlaylistSyncRow], _ held: [String]) -> [PlaylistSyncRow] {
    var out = rows
    for id in held {
        if let at = out.lastIndex(where: { $0.serverSongId == id }) { out.remove(at: at) }
    }
    return out
}

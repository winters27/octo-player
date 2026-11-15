import Foundation

/// The kinds of folder change waiting to reach the server.
public enum FolderOpKind {
    public static let save = "save"
    public static let delete = "delete"
    public static let place = "place"
}

/// One folder change waiting to reach the server, in the order it was made: a folder saved (made,
/// renamed or moved) or deleted, or playlists placed by their server ids (`folderId` nil for the
/// top level).
public struct FolderOp: Sendable, Equatable {
    public var seq: Int64
    public var space: String
    public var kind: String
    public var folderId: String?
    public var name: String?
    public var parent: String?
    public var playlistKeys: [String]
    public var at: Int64
}

// Playlist folders on the phone, per account (`space`: the kept server's id, or "" with no
// server in use): the folders as the server last gave them with the changes made here on top,
// where each playlist sits by its placement key, and the changes waiting to reach the server.
// Each change and the change it sends are written in one transaction; a change made with no
// server in use stays on the phone. The one-time base step's mark per server, and the server's
// upkeep and import marks on its playlists' copies, are kept here too.
extension CatalogStore {
    public func playlistFolderTree(_ space: String, locale: Locale = .current) throws -> PlaylistFolderTree {
        PlaylistFolderTree(folders: try folderRows(space), places: try placeRows(space), locale: locale)
    }

    /// A new folder in `parent` (nil: the top); false for a parent that can't hold one more level.
    @discardableResult
    public func createFolder(_ space: String, id: String, name: String, parent: String?, at: Int64) throws -> Bool {
        try batch {
            let tree = try playlistFolderTree(space)
            let under = parent.flatMap { tree.folder($0) == nil ? nil : $0 }
            guard tree.canHoldFolder(under) else { return false }
            try db.run("INSERT OR REPLACE INTO playlist_folder (space, id, name, parent, created) VALUES (?, ?, ?, ?, ?)",
                       [space, id, name, under, Self.isoTime(at)])
            try queueFolderOp(space, FolderOpKind.save, folderId: id, name: name, parent: under, keys: [], at: at)
            changed(.playlists)
            return true
        }
    }

    @discardableResult
    public func renameFolder(_ space: String, id: String, name: String, at: Int64) throws -> Bool {
        try batch {
            guard let folder = try folderRow(space, id) else { return false }
            try db.run("UPDATE playlist_folder SET name = ? WHERE space = ? AND id = ?", [name, space, id])
            try queueFolderOp(space, FolderOpKind.save, folderId: id, name: name, parent: folder.parent, keys: [], at: at)
            changed(.playlists)
            return true
        }
    }

    /// Moves a folder into `parent` (nil: the top); answers why it can't, or nil once moved (or
    /// when the folder is gone).
    public func moveFolder(_ space: String, id: String, into parent: String?, at: Int64) throws -> FolderMoveRefusal? {
        try batch {
            let tree = try playlistFolderTree(space)
            guard let folder = tree.folder(id) else { return nil }
            if let refusal = tree.moveRefusal(id, into: parent) { return refusal }
            try db.run("UPDATE playlist_folder SET parent = ? WHERE space = ? AND id = ?", [parent, space, id])
            try queueFolderOp(space, FolderOpKind.save, folderId: id, name: folder.name, parent: parent, keys: [], at: at)
            changed(.playlists)
            return nil
        }
    }

    /// Deletes a folder: its folders and playlists move to its parent.
    @discardableResult
    public func deleteFolder(_ space: String, id: String, at: Int64) throws -> Bool {
        try batch {
            guard let folder = try folderRow(space, id) else { return false }
            try applyDelete(space, folder)
            try queueFolderOp(space, FolderOpKind.delete, folderId: id, name: nil, parent: nil, keys: [], at: at)
            changed(.playlists)
            return true
        }
    }

    /// Puts playlists (by placement key) in a folder, or at the top with none. Only server
    /// playlists' placements are sent; answers whether one was queued.
    @discardableResult
    public func placePlaylists(_ space: String, keys: [String], in folderId: String?, at: Int64) throws -> Bool {
        guard !keys.isEmpty else { return false }
        return try batch {
            if let folderId, try folderRow(space, folderId) == nil { return false }
            for key in keys { try applyPlace(space, key, folderId) }
            let sent = keys.filter { !PlaylistFolderTree.isLocalKey($0) }
            if !sent.isEmpty {
                try queueFolderOp(space, FolderOpKind.place, folderId: folderId, name: nil, parent: nil, keys: sent, at: at)
            }
            changed(.playlists)
            return !sent.isEmpty && !space.isEmpty
        }
    }

    /// A playlist deleted here: its placement goes from every account, with nothing sent (the
    /// server lets a deleted playlist's placement go itself).
    public func forgetPlacements(_ key: String) throws {
        try db.run("DELETE FROM playlist_place WHERE playlistKey = ?", [key])
        changed(.playlists)
    }

    public func firstFolderOp(_ space: String) throws -> FolderOp? { try folderOps(space, limit: 1).first }

    public func folderOps(_ space: String) throws -> [FolderOp] { try folderOps(space, limit: nil) }

    public func dropFolderOp(_ seq: Int64) throws { try db.run("DELETE FROM folder_op WHERE seq = ?", [seq]) }

    /// The server's folders and places in place of the account's (its phone-only placements
    /// stay), the changes still waiting replayed on top as the server would take them, and
    /// placements whose folder is gone dropped, in one transaction.
    public func applyFolderPull(_ space: String, _ answer: PlaylistFolders) throws {
        try batch {
            try db.run("DELETE FROM playlist_place WHERE space = ? AND localOnly = 0", [space])
            try db.run("DELETE FROM playlist_folder WHERE space = ?", [space])
            for folder in answer.folders {
                try db.run("INSERT OR REPLACE INTO playlist_folder (space, id, name, parent, created) VALUES (?, ?, ?, ?, ?)",
                           [space, folder.id, folder.name, folder.parent, folder.created])
            }
            for place in answer.places {
                try db.run("INSERT OR REPLACE INTO playlist_place (space, playlistKey, folderId, localOnly) VALUES (?, ?, ?, 0)",
                           [space, place.playlist, place.folder])
            }
            for op in try folderOps(space, limit: nil) { try replay(space, op) }
            try db.run("DELETE FROM playlist_place WHERE space = ? AND folderId NOT IN (SELECT id FROM playlist_folder WHERE space = ?)",
                       [space, space])
            changed(.playlists)
        }
    }

    /// Links a phone playlist to its server playlist, as `linkPlaylist` does, and in the same
    /// transaction turns its phone-only placement in `space` into one the server keeps, queueing
    /// the place change. Answers whether it had a placement.
    @discardableResult
    public func linkPlaylist(_ id: String, serverId: String, sourceId: String, syncedAt: Int64, name: String, stamp: String,
                             placingIn space: String, at: Int64) throws -> Bool {
        try batch {
            try linkPlaylist(id, serverId: serverId, sourceId: sourceId, syncedAt: syncedAt, name: name, stamp: stamp)
            let localKey = PlaylistFolderTree.localKey(id)
            guard let folderId = try db.first("SELECT folderId FROM playlist_place WHERE space = ? AND playlistKey = ?",
                                              [space, localKey], { $0.string("folderId") }) else { return false }
            try db.run("DELETE FROM playlist_place WHERE space = ? AND playlistKey = ?", [space, localKey])
            try db.run("INSERT OR REPLACE INTO playlist_place (space, playlistKey, folderId, localOnly) VALUES (?, ?, ?, 0)",
                       [space, serverId, folderId])
            try queueFolderOp(space, FolderOpKind.place, folderId: folderId, name: nil, parent: nil, keys: [serverId], at: at)
            changed(.playlists)
            return !space.isEmpty
        }
    }

    /// A server playlist unlinked here: its placement becomes the phone-only playlist's, in every
    /// account that had one.
    func turnPlacementLocal(serverId: String, rowId: String) throws {
        try db.run("UPDATE OR REPLACE playlist_place SET playlistKey = ?, localOnly = 1 WHERE playlistKey = ?",
                   [PlaylistFolderTree.localKey(rowId), serverId])
    }

    // MARK: The base step and the server's marks

    /// Whether a server's playlists had their bases seeded (PlaylistSteps.ensureBase).
    public func baseSeeded(_ sourceId: String) throws -> Bool {
        try db.scalar("SELECT COUNT(*) FROM playlist_base_mark WHERE sourceId = ?", [sourceId]) > 0
    }

    public func markBaseSeeded(_ sourceId: String, at: Int64) throws {
        try db.run("INSERT OR REPLACE INTO playlist_base_mark (sourceId, at) VALUES (?, ?)", [sourceId, at])
    }

    /// Keeps `songs` as a linked playlist's base only while it is as the base step read it: not
    /// edited (updatedAt and syncedAt both as read), linked to the same server playlist of the
    /// same server, not a family playlist or an imported copy. Answers whether it did.
    public func seedBase(_ row: PlaylistRow, songs: String) throws -> Bool {
        try db.run("""
            UPDATE playlist SET syncedSongs = ?
            WHERE id = ? AND family = 0 AND octoImport = 0 AND updatedAt = ? AND syncedAt IS ? AND serverId IS ? AND sourceId IS ?
            """, [songs, row.id, row.updatedAt, row.syncedAt, row.serverId, row.sourceId])
        return db.changes == 1
    }

    /// Lets go of an edited playlist's base, under the same conditions: one a push wrote no base
    /// for has none, so the rules for a playlist with no base decide it.
    public func clearBase(_ row: PlaylistRow) throws -> Bool {
        try db.run("""
            UPDATE playlist SET syncedSongs = NULL
            WHERE id = ? AND family = 0 AND octoImport = 0 AND updatedAt = ? AND syncedAt IS ? AND serverId IS ? AND sourceId IS ?
            """, [row.id, row.updatedAt, row.syncedAt, row.serverId, row.sourceId])
        return db.changes == 1
    }

    /// The server's upkeep mark for its copy of a playlist (upkeepMark).
    public func markUpkeep(_ id: String, upkeep: String?) throws {
        try db.run("UPDATE playlist SET upkeep = ? WHERE id = ?", [upkeep, id])
        changed(.playlists)
    }

    /// Whether the server marks its copy as an imported playlist's copy.
    public func markImport(_ id: String, _ octoImport: Bool) throws {
        try db.run("UPDATE playlist SET octoImport = ? WHERE id = ?", [octoImport ? 1 : 0, id])
        changed(.playlists)
    }

    // MARK: Rows

    private func folderRows(_ space: String) throws -> [PlaylistFolder] {
        try db.query("SELECT id, name, parent, created FROM playlist_folder WHERE space = ?", [space]) {
            PlaylistFolder(id: $0.string("id"), name: $0.string("name"), parent: $0.optionalString("parent"), created: $0.string("created"))
        }
    }

    private func placeRows(_ space: String) throws -> [PlaylistPlace] {
        try db.query("SELECT playlistKey, folderId FROM playlist_place WHERE space = ?", [space]) {
            PlaylistPlace(playlist: $0.string("playlistKey"), folder: $0.string("folderId"))
        }
    }

    private func folderRow(_ space: String, _ id: String) throws -> PlaylistFolder? {
        try db.first("SELECT id, name, parent, created FROM playlist_folder WHERE space = ? AND id = ?", [space, id]) {
            PlaylistFolder(id: $0.string("id"), name: $0.string("name"), parent: $0.optionalString("parent"), created: $0.string("created"))
        }
    }

    private func folderOps(_ space: String, limit: Int?) throws -> [FolderOp] {
        let sql = "SELECT * FROM folder_op WHERE space = ? ORDER BY seq" + (limit.map { " LIMIT \($0)" } ?? "")
        return try db.query(sql, [space]) { row in
            FolderOp(seq: row.int64("seq"), space: row.string("space"), kind: row.string("kind"), folderId: row.optionalString("folderId"),
                     name: row.optionalString("name"), parent: row.optionalString("parent"),
                     playlistKeys: row.string("playlistKeys").split(separator: "\n").map(String.init), at: row.int64("at"))
        }
    }

    private func queueFolderOp(_ space: String, _ kind: String, folderId: String?, name: String?, parent: String?, keys: [String],
                               at: Int64) throws {
        guard !space.isEmpty else { return }
        try db.run("INSERT INTO folder_op (space, kind, folderId, name, parent, playlistKeys, at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                   [space, kind, folderId, name, parent, keys.joined(separator: "\n"), at])
    }

    private func applyDelete(_ space: String, _ folder: PlaylistFolder) throws {
        try db.run("UPDATE playlist_folder SET parent = ? WHERE space = ? AND parent = ?", [folder.parent, space, folder.id])
        if let parent = folder.parent {
            try db.run("UPDATE playlist_place SET folderId = ? WHERE space = ? AND folderId = ?", [parent, space, folder.id])
        } else {
            try db.run("DELETE FROM playlist_place WHERE space = ? AND folderId = ?", [space, folder.id])
        }
        try db.run("DELETE FROM playlist_folder WHERE space = ? AND id = ?", [space, folder.id])
    }

    private func applyPlace(_ space: String, _ key: String, _ folderId: String?) throws {
        if let folderId {
            try db.run("INSERT OR REPLACE INTO playlist_place (space, playlistKey, folderId, localOnly) VALUES (?, ?, ?, ?)",
                       [space, key, folderId, PlaylistFolderTree.isLocalKey(key) ? 1 : 0])
        } else {
            try db.run("DELETE FROM playlist_place WHERE space = ? AND playlistKey = ?", [space, key])
        }
    }

    /// A waiting change applied again over the server's folders, as the server would: a missing
    /// parent or folder means the top.
    private func replay(_ space: String, _ op: FolderOp) throws {
        switch op.kind {
        case FolderOpKind.save:
            guard let id = op.folderId else { return }
            var parent: String?
            if let wanted = op.parent, wanted != id, try folderRow(space, wanted) != nil { parent = wanted }
            if let known = try folderRow(space, id) {
                try db.run("UPDATE playlist_folder SET name = ?, parent = ? WHERE space = ? AND id = ?", [op.name ?? known.name, parent, space, id])
            } else {
                try db.run("INSERT OR REPLACE INTO playlist_folder (space, id, name, parent, created) VALUES (?, ?, ?, ?, ?)",
                           [space, id, op.name ?? "", parent, Self.isoTime(op.at)])
            }
        case FolderOpKind.delete:
            if let id = op.folderId, let folder = try folderRow(space, id) { try applyDelete(space, folder) }
        case FolderOpKind.place:
            var folder: String?
            if let id = op.folderId, try folderRow(space, id) != nil { folder = id }
            for key in op.playlistKeys { try applyPlace(space, key, folder) }
        default:
            return
        }
    }

    /// A time as the server writes its folders' times.
    static func isoTime(_ ms: Int64) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }
}

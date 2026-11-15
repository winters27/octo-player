import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// The extension a server lists when a playlist of the listener's own can hold songs found
/// online: adds take outside ids, a replace takes octoIfStamp and octoOwnIds, removals go by
/// song (octoSongToRemove) on every owned playlist, and answers name the ids it skipped
/// (octoSkipped).
public let playlistSongsExtension = "octoPlaylistSongs"

/// The extension a server lists when it keeps the listener's playlist folders.
public let playlistFoldersExtension = "octoPlaylistFolders"

/// The param a replace carries so Octo refuses it when the playlist changed since the phone read
/// it (the changed time the server sent), and the one that says it names the server's own ids
/// (no 1.12.0 title matches to pair).
public let ifStampParam = "octoIfStamp"
public let ownIdsParam = "octoOwnIds"

/// One playlist folder: its id (made by the app that made it: "pf" and 20 base62 characters),
/// its name, the folder it sits in (nil at the top), and when it was made.
public struct PlaylistFolder: Decodable, Sendable, Hashable, Identifiable {
    public var id: String
    public var name: String
    public var parent: String?
    public var created: String

    public init(id: String, name: String, parent: String? = nil, created: String = "") {
        self.id = id
        self.name = name
        self.parent = parent
        self.created = created
    }

    enum CodingKeys: String, CodingKey { case id, name, parent, created }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.id(.id)
        name = c.string(.name) ?? ""
        parent = c.string(.parent)
        created = c.string(.created) ?? ""
    }
}

/// Which folder a playlist sits in, by its placement key: the server's playlist id, or
/// "local:<row id>" for a playlist only on the phone.
public struct PlaylistPlace: Decodable, Sendable, Hashable {
    public var playlist: String
    public var folder: String

    public init(playlist: String, folder: String) {
        self.playlist = playlist
        self.folder = folder
    }

    enum CodingKeys: String, CodingKey { case playlist, folder }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        playlist = try c.id(.playlist)
        folder = try c.id(.folder)
    }
}

/// The listener's folders and where their playlists sit, as the server keeps them, and when they
/// last changed. Every folder call answers it, as it stands after the call.
public struct PlaylistFolders: Decodable, Sendable, Equatable {
    public var changed: String
    public var folders: [PlaylistFolder]
    public var places: [PlaylistPlace]

    public init(changed: String = "", folders: [PlaylistFolder] = [], places: [PlaylistPlace] = []) {
        self.changed = changed
        self.folders = folders
        self.places = places
    }

    enum CodingKeys: String, CodingKey { case changed, folder, place }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        changed = c.string(.changed) ?? ""
        folders = (try? c.decodeIfPresent([PlaylistFolder].self, forKey: .folder)) ?? []
        places = (try? c.decodeIfPresent([PlaylistPlace].self, forKey: .place)) ?? []
    }
}

/// Octo's answer to a replace sent with octoIfStamp whose playlist changed since that stamp
/// (octoConflict): nothing was changed, and the list wants reading again.
public struct PlaylistChanged: Error, Equatable, LocalizedError {
    public let message: String

    public init(message: String) { self.message = message }

    public var errorDescription: String? { message }
}

private let foldersKey = "playlistFolders"

extension SubsonicClient {
    /// The listener's folders.
    public func playlistFolders() async throws -> PlaylistFolders {
        try await get("getPlaylistFolders", key: foldersKey)
    }

    /// Makes a folder, or renames or moves one; sent again it changes nothing more. A folder
    /// deleted before is refused (`OctoError.notFound`).
    public func savePlaylistFolder(id: String, name: String, parentId: String?) async throws -> PlaylistFolders {
        try await get("savePlaylistFolder", saveFolderParams(id, name, parentId), key: foldersKey)
    }

    /// Deletes a folder: its folders and playlists move to its parent.
    public func deletePlaylistFolder(id: String) async throws -> PlaylistFolders {
        try await get("deletePlaylistFolder", [("id", id)], key: foldersKey)
    }

    /// Puts playlists in a folder, or with no folder at the top level.
    public func setPlaylistFolder(playlistIds: [String], folderId: String?) async throws -> PlaylistFolders {
        try await get("setPlaylistFolder", placeFolderParams(playlistIds, folderId), key: foldersKey)
    }
}

func saveFolderParams(_ id: String, _ name: String, _ parentId: String?) -> [(String, String)] {
    [("id", id), ("name", name)] + (parentId.map { [("parentId", $0)] } ?? [])
}

func placeFolderParams(_ playlistIds: [String], _ folderId: String?) -> [(String, String)] {
    playlistIds.map { ("playlistId", $0) } + (folderId.map { [("folderId", $0)] } ?? [])
}

/// The kinds of library upkeep list a server names (octoUpkeepKind): its library actions, its
/// notices, and a family member's request lists.
public enum UpkeepKind {
    public static let action = "action"
    public static let notice = "notice"
    public static let request = "request"
}

/// How the phone keeps a server row's upkeep mark: its kind, "action" when the server marks one
/// without naming a kind (an older server), nil for any other playlist.
public func upkeepMark(_ upkeep: Bool, kind: String?) -> String? { upkeep ? (kind ?? UpkeepKind.action) : nil }

/// Whether songs found online never go in a playlist with this upkeep mark: Octo's library action
/// and notice lists. A family member's request lists take them, as requests.
public func refusesFinds(_ mark: String?) -> Bool { mark != nil && mark != UpkeepKind.request }

/// The words playlist folders, the upkeep group and playlists holding songs found online show,
/// the same on every app.
public enum PlaylistFolderWords {
    public static let playlistFolder = "Playlist folder"
    public static let folder = "Folder"
    public static let newFolder = "New folder"
    public static let folderName = "Folder name"
    public static let moveToFolder = "Move to folder"
    public static let notInAFolder = "Not in a folder"
    public static let addPlaylists = "Add playlists"
    public static let rename = "Rename"
    public static let renameFolder = "Rename folder"
    public static let deleteFolder = "Delete folder"
    public static let cancel = "Cancel"
    public static let newPlaylistHere = "New playlist here"
    public static let newFolderHere = "New folder here"
    public static let nothingHereYet = "Nothing here yet"
    public static let emptyFolderNote = "Use Move to folder on any playlist, or Add playlists."
    public static let intoItself = "A folder can't go inside itself."
    public static let tooDeep = "Folders can go 4 deep."
    public static let badName = "A folder needs a name of 1 to 100 characters."
    public static let libraryUpkeep = "Library upkeep"
    public static let skippedSongs = "Some songs couldn't be added. Search for them again."
    public static let savedForFinds = "Saved to your server so its songs found online keep playing."
    public static let folderChangeFailed = "A folder change couldn't be saved to your server."
    public static let upkeepRefusesFinds = "Songs found online can't go in Octo's library upkeep playlists."

    public static func moveHere(_ count: Int) -> String { "Move here (\(count))" }

    public static func deleteTitle(_ name: String) -> String { "Delete \"\(name)\"?" }

    /// Where a deleted folder's playlists go: its parent, or the top level.
    public static func deleteBody(parentName: String?) -> String {
        "Its playlists move to \(parentName ?? "the top level"). No playlist is deleted."
    }

    public static func fromThisPhone(_ name: String) -> String { "\(name) (from this phone)" }

    public static func bothChanged(_ name: String) -> String {
        "This playlist changed on your server and on this phone. Your phone's version was saved as \"\(fromThisPhone(name))\"."
    }

    /// The upkeep group's note while it is closed: each notice list holding songs, "Review has 3
    /// songs, Duplicates has 1 song"; nil when none does.
    public static func upkeepNote(_ notices: [(name: String, count: Int)]) -> String? {
        let holding = notices.filter { $0.count > 0 }
        guard !holding.isEmpty else { return nil }
        return holding.map { "\($0.name) has \(counted($0.count, "song"))" }.joined(separator: ", ")
    }

    /// A folder row's line: "3 playlists", "1 playlist, 2 folders".
    public static func folderSubtitle(playlists: Int, folders: Int) -> String {
        counted(playlists, "playlist") + (folders > 0 ? ", " + counted(folders, "folder") : "")
    }

    private static func counted(_ n: Int, _ one: String) -> String { n == 1 ? "1 \(one)" : "\(withCommas(n)) \(one)s" }

    private static func withCommas(_ n: Int) -> String {
        var out: [Character] = []
        for (index, digit) in String(abs(n)).reversed().enumerated() {
            if index > 0 && index % 3 == 0 { out.append(",") }
            out.append(digit)
        }
        return (n < 0 ? "-" : "") + String(out.reversed())
    }
}

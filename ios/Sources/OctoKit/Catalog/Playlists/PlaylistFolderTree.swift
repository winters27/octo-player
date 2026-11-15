import Foundation

/// Why a folder can't move where it was asked to.
public enum FolderMoveRefusal: Sendable, Equatable {
    case intoItself
    case tooDeep

    public var words: String {
        switch self {
        case .intoItself: PlaylistFolderWords.intoItself
        case .tooDeep: PlaylistFolderWords.tooDeep
        }
    }
}

/// A listener's playlist folders as a tree, with where their playlists sit, for every folder
/// screen: each level A to Z in the listener's language (case and accents aside), a folder's path
/// and depth, and the rules a change keeps (no folder inside itself, at most `maxDepth` levels). A
/// folder whose parent is missing, or whose parents loop, sits at the top; a placement whose
/// folder is missing counts as none. Playlists are named by their placement key: the server's
/// playlist id, or "local:<row id>" for one only on the phone.
public struct PlaylistFolderTree: Sendable {
    public static let maxDepth = 4
    public static let maxName = 100
    public static let idPrefix = "pf"
    public static let localPrefix = "local:"
    private static let idLength = 22
    private static let digits = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")

    public static let empty = PlaylistFolderTree(folders: [], places: [])

    private let byId: [String: PlaylistFolder]
    private let placed: [String: String]
    private let kids: [String?: [PlaylistFolder]]

    public init(folders: [PlaylistFolder], places: [PlaylistPlace], locale: Locale = .current) {
        var ids: [String: PlaylistFolder] = [:]
        for folder in folders where ids[folder.id] == nil { ids[folder.id] = folder }
        var at: [String: String] = [:]
        for place in places where ids[place.folder] != nil { at[place.playlist] = place.folder }
        var groups: [String?: [PlaylistFolder]] = [:]
        for folder in ids.values { groups[Self.parent(of: folder.id, in: ids), default: []].append(folder) }
        for (parent, list) in groups { groups[parent] = list.sorted { Self.ordered($0, $1, locale) } }
        byId = ids
        placed = at
        kids = groups
    }

    public var isEmpty: Bool { byId.isEmpty }

    public func folder(_ id: String) -> PlaylistFolder? { byId[id] }

    /// The folder a folder sits in, or nil at the top.
    public func parentOf(_ id: String) -> String? { Self.parent(of: id, in: byId) }

    /// A level's folders, A to Z: the top with `parent` nil.
    public func children(_ parent: String?) -> [PlaylistFolder] { kids[parent] ?? [] }

    /// The folder a playlist sits in, or nil at the top.
    public func folderOf(_ playlistKey: String) -> String? { placed[playlistKey] }

    /// Which of `keys` sit right in `folderId` (nil: in no folder).
    public func playlistsIn(_ folderId: String?, keys: [String]) -> [String] { keys.filter { placed[$0] == folderId } }

    /// From the top folder down to this one.
    public func path(_ id: String) -> [PlaylistFolder] {
        var out: [PlaylistFolder] = []
        var at: String? = id
        while let current = at, let folder = byId[current] {
            out.append(folder)
            at = parentOf(current)
        }
        return out.reversed()
    }

    /// "Road trips / Summer".
    public func pathName(_ id: String, separator: String = " / ") -> String { path(id).map(\.name).joined(separator: separator) }

    /// 1 for a folder at the top.
    public func depth(_ id: String) -> Int { path(id).count }

    /// 1 for a folder with no folders inside it.
    public func height(_ id: String) -> Int { 1 + (children(id).map { height($0.id) }.max() ?? 0) }

    /// The folder and every folder inside it.
    public func subtree(_ id: String) -> Set<String> {
        var out = Set<String>()
        var todo = [id]
        while !todo.isEmpty {
            let at = todo.removeFirst()
            if out.insert(at).inserted { todo += children(at).map(\.id) }
        }
        return out
    }

    /// Why folder `id` can't move into `parent` (nil: the top), or nil when it can.
    public func moveRefusal(_ id: String, into parent: String?) -> FolderMoveRefusal? {
        if let parent, subtree(id).contains(parent) { return .intoItself }
        let above = parent.map { depth($0) } ?? 0
        return above + height(id) > Self.maxDepth ? .tooDeep : nil
    }

    /// Whether a new folder can go in `parent` (nil: the top).
    public func canHoldFolder(_ parent: String?) -> Bool { parent.map { depth($0) < Self.maxDepth } ?? true }

    /// Every folder in tree order, each with its depth (1 at the top), each level A to Z.
    public func inTreeOrder() -> [(folder: PlaylistFolder, depth: Int)] {
        var out: [(folder: PlaylistFolder, depth: Int)] = []
        func walk(_ parent: String?, _ depth: Int) {
            for folder in children(parent) {
                out.append((folder, depth))
                walk(folder.id, depth + 1)
            }
        }
        walk(nil, 1)
        return out
    }

    /// A new folder's id, made here: "pf" and 20 base62 characters.
    public static func newId() -> String {
        var generator = SystemRandomNumberGenerator()
        return newId(using: &generator)
    }

    public static func newId<G: RandomNumberGenerator>(using generator: inout G) -> String {
        var out = idPrefix
        for _ in 0..<(idLength - idPrefix.count) { out.append(digits[Int.random(in: 0..<digits.count, using: &generator)]) }
        return out
    }

    public static func isId(_ id: String) -> Bool { id.count == idLength && id.hasPrefix(idPrefix) && id.allSatisfy { digits.contains($0) } }

    /// A folder name as kept: trimmed, 1 to `maxName` characters; nil otherwise.
    public static func cleanName(_ name: String) -> String? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return (1...maxName).contains(trimmed.unicodeScalars.count) ? trimmed : nil
    }

    /// Whether a playlist can be filed: not one of Octo's generated lists ("or" stations and
    /// "og" lists, Liked Songs among them).
    public static func fileable(_ playlistKey: String) -> Bool {
        !playlistKey.trimmingCharacters(in: .whitespaces).isEmpty
            && !(playlistKey.count == idLength && (playlistKey.hasPrefix("or") || playlistKey.hasPrefix("og")))
    }

    /// A playlist only on the phone, by its row id: placed there, never sent.
    public static func localKey(_ rowId: String) -> String { localPrefix + rowId }

    public static func isLocalKey(_ key: String) -> Bool { key.hasPrefix(localPrefix) }

    private static func parent(of id: String, in byId: [String: PlaylistFolder]) -> String? {
        guard let parent = byId[id]?.parent, parent != id, byId[parent] != nil, !loops(id, byId) else { return nil }
        return parent
    }

    /// Whether a folder's parents come back round to it.
    private static func loops(_ id: String, _ byId: [String: PlaylistFolder]) -> Bool {
        var seen = Set<String>()
        var at: String? = id
        while let current = at {
            if !seen.insert(current).inserted { return true }
            at = byId[current]?.parent.flatMap { byId[$0] == nil ? nil : $0 }
        }
        return false
    }

    private static func ordered(_ a: PlaylistFolder, _ b: PlaylistFolder, _ locale: Locale) -> Bool {
        let left = a.name.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: locale)
        let right = b.name.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: locale)
        return left != right ? left < right : a.id < b.id
    }
}

/// A folder on a playlists page: the folder, and how many playlists and folders sit right in it.
public struct FolderEntry: Sendable, Identifiable {
    public var folder: PlaylistFolder
    public var playlists: Int
    public var folders: Int

    public var id: String { folder.id }

    public init(folder: PlaylistFolder, playlists: Int, folders: Int) {
        self.folder = folder
        self.playlists = playlists
        self.folders = folders
    }
}

/// What a playlists page shows: the folders in it A to Z, then its playlists in the order given;
/// on the top level (`folder` nil) also the library upkeep lists, which never sit in a folder.
public struct PlaylistPageRows<T> {
    public var folders: [FolderEntry]
    public var playlists: [T]
    public var upkeep: [T]

    public init(folders: [FolderEntry], playlists: [T], upkeep: [T]) {
        self.folders = folders
        self.playlists = playlists
        self.upkeep = upkeep
    }
}

/// The rows of the Playlists page (`folder` nil) or a folder's page. With no folders kept
/// (`tree` nil) the top level shows every playlist that is not an upkeep list, and a folder page
/// shows nothing.
public func playlistPageRows<T>(_ items: [T], key: (T) -> String, upkeep: (T) -> Bool, tree: PlaylistFolderTree?,
                                folder: String?) -> PlaylistPageRows<T> {
    let own = items.filter { !upkeep($0) }
    let upkept = folder == nil ? items.filter(upkeep) : []
    guard let tree else { return PlaylistPageRows(folders: [], playlists: folder == nil ? own : [], upkeep: upkept) }
    var counts: [String: Int] = [:]
    for item in own {
        if let at = tree.folderOf(key(item)) { counts[at, default: 0] += 1 }
    }
    let folders = tree.children(folder).map { FolderEntry(folder: $0, playlists: counts[$0.id] ?? 0, folders: tree.children($0.id).count) }
    return PlaylistPageRows(folders: folders, playlists: own.filter { tree.folderOf(key($0)) == folder }, upkeep: upkept)
}

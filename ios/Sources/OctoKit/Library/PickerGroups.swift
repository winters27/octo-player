import Foundation

/// The kinds of group an Add to playlist list shows, in the order shown.
public enum PickerGroupKind: Sendable, Equatable {
    case recent, unfiled, folder, upkeep
}

/// One group of an Add to playlist list: its kind, its heading (a folder's path, or the upkeep
/// group's name; none for the recent and unfiled groups), and its playlists in the order shown.
public struct PickerGroup<T> {
    public let kind: PickerGroupKind
    public let title: String?
    public let items: [T]

    public init(kind: PickerGroupKind, title: String?, items: [T]) {
        self.kind = kind
        self.title = title
        self.items = items
    }
}

extension PickerGroup: Sendable where T: Sendable {}

/// How an Add to playlist list is grouped on every app, under its "New playlist" row: up to
/// `recent` playlists added to lately, then the playlists in no folder A to Z, then each folder's
/// playlists under its path (folders in tree order), then the library upkeep playlists. A playlist
/// added to lately shows in its own group too. Upkeep playlists never show inside a folder. For
/// songs found online (`forFind`) the ones that refuse such songs are left out everywhere. Empty
/// groups are left out. Callers leave out playlists the listener can't add to (an imported
/// playlist's copy, a family playlist they may only hear).
public enum PickerGroups {
    public static let recent = 3

    public static func build<T>(
        _ items: [T],
        id: (T) -> String,
        placeKey: (T) -> String,
        name: (T) -> String,
        upkeep: (T) -> Bool,
        refusesFind: (T) -> Bool,
        recentIds: [String],
        tree: PlaylistFolderTree?,
        forFind: Bool,
        locale: Locale = .current
    ) -> [PickerGroup<T>] {
        func byName(_ a: T, _ b: T) -> Bool {
            let left = name(a).folding(options: [.caseInsensitive, .diacriticInsensitive], locale: locale)
            let right = name(b).folding(options: [.caseInsensitive, .diacriticInsensitive], locale: locale)
            return left != right ? left < right : id(a) < id(b)
        }
        let offered = forFind ? items.filter { !refusesFind($0) } : items
        var byId: [String: T] = [:]
        for item in offered where byId[id(item)] == nil { byId[id(item)] = item }
        var groups: [PickerGroup<T>] = []
        var seen = Set<String>()
        let recentItems = recentIds.filter { seen.insert($0).inserted }.compactMap { byId[$0] }.prefix(recent)
        if !recentItems.isEmpty { groups.append(PickerGroup(kind: .recent, title: nil, items: Array(recentItems))) }
        let own = offered.filter { !upkeep($0) }
        let upkept = offered.filter { upkeep($0) }
        var unfiled: [T] = []
        var filed: [String: [T]] = [:]
        for item in own {
            if let folder = tree?.folderOf(placeKey(item)) { filed[folder, default: []].append(item) } else { unfiled.append(item) }
        }
        if !unfiled.isEmpty { groups.append(PickerGroup(kind: .unfiled, title: nil, items: unfiled.sorted(by: byName))) }
        if let tree {
            for entry in tree.inTreeOrder() {
                guard let held = filed[entry.folder.id] else { continue }
                groups.append(PickerGroup(kind: .folder, title: tree.pathName(entry.folder.id), items: held.sorted(by: byName)))
            }
        }
        if !upkept.isEmpty { groups.append(PickerGroup(kind: .upkeep, title: PlaylistFolderWords.libraryUpkeep, items: upkept.sorted(by: byName))) }
        return groups
    }
}

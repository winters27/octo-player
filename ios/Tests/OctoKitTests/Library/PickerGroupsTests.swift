import Foundation
import Testing
@testable import OctoKit

@Suite struct PickerGroupsTests {
    private struct P {
        let id: String
        let name: String
        var upkeep = false
        var refuses = false
    }

    private var tree: PlaylistFolderTree {
        PlaylistFolderTree(
            folders: [PlaylistFolder(id: "road", name: "Road trips"), PlaylistFolder(id: "summer", name: "Summer", parent: "road"),
                      PlaylistFolder(id: "art", name: "Art")],
            places: [PlaylistPlace(playlist: "p3", folder: "summer"), PlaylistPlace(playlist: "p4", folder: "road"),
                     PlaylistPlace(playlist: "p5", folder: "art"), PlaylistPlace(playlist: "u1", folder: "art")],
            locale: Locale(identifier: "en"))
    }

    private let all = [
        P(id: "p1", name: "zebra"), P(id: "p2", name: "Apple"), P(id: "p3", name: "Beach"), P(id: "p4", name: "Coast"),
        P(id: "p5", name: "Murals"), P(id: "u1", name: "Remove from library", upkeep: true, refuses: true),
        P(id: "u2", name: "Request a copy", upkeep: true),
    ]

    private func build(forFind: Bool, recent: [String] = ["p5", "p1", "u1", "p2"], tree: PlaylistFolderTree?) -> [PickerGroup<P>] {
        PickerGroups.build(all, id: \.id, placeKey: \.id, name: \.name, upkeep: \.upkeep, refusesFind: \.refuses, recentIds: recent,
                           tree: tree, forFind: forFind, locale: Locale(identifier: "en"))
    }

    @Test func recentThenUnfiledThenEachFolderThenUpkeep() {
        let groups = build(forFind: false, tree: tree)
        #expect(groups.map(\.kind) == [.recent, .unfiled, .folder, .folder, .folder, .upkeep])
        #expect(groups.map(\.title) == [nil, nil, "Art", "Road trips", "Road trips / Summer", PlaylistFolderWords.libraryUpkeep])
        #expect(groups[0].items.map(\.id) == ["p5", "p1", "u1"], "three recent, in the order they were used")
        #expect(groups[1].items.map(\.name) == ["Apple", "zebra"])
        #expect(groups.last?.items.map(\.id) == ["u1", "u2"], "upkeep never inside a folder, even when placed")
    }

    @Test func aFindLeavesOutTheUpkeepListsThatRefuseIt() {
        let groups = build(forFind: true, tree: tree)
        #expect(groups[0].items.map(\.id) == ["p5", "p1", "p2"])
        #expect(groups.last?.items.map(\.id) == ["u2"])
    }

    @Test func emptyGroupsAreLeftOutAndNoTreeMeansNoFolders() {
        let groups = PickerGroups.build([P(id: "p1", name: "One")], id: \.id, placeKey: \.id, name: \.name, upkeep: \.upkeep,
                                        refusesFind: \.refuses, recentIds: [], tree: nil, forFind: false)
        #expect(groups.map(\.kind) == [.unfiled])
        #expect(build(forFind: false, recent: [], tree: nil).map(\.kind) == [.unfiled, .upkeep])
    }
}

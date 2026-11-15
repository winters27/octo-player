import Foundation
import Testing
@testable import OctoKit

@Suite struct PlaylistFolderTreeTests {
    private static func folder(_ id: String, _ name: String, _ parent: String? = nil) -> PlaylistFolder {
        PlaylistFolder(id: id, name: name, parent: parent)
    }

    private var tree: PlaylistFolderTree {
        PlaylistFolderTree(
            folders: [
                Self.folder("c", "cherry"), Self.folder("a", "Apple"), Self.folder("e", "Éclair"), Self.folder("b", "banana"),
                Self.folder("a1", "Road trips", "a"), Self.folder("a2", "Summer", "a1"), Self.folder("a3", "Night", "a2"),
                Self.folder("a4", "Late", "a3"), Self.folder("lost", "Lost", "gone"),
            ],
            places: [
                PlaylistPlace(playlist: "pl1", folder: "a2"), PlaylistPlace(playlist: "pl2", folder: "gone"),
                PlaylistPlace(playlist: "local:r1", folder: "b"),
            ],
            locale: Locale(identifier: "en"))
    }

    @Test func eachLevelGoesAToZInTheListenersLanguage() {
        #expect(tree.children(nil).map(\.name) == ["Apple", "banana", "cherry", "Éclair", "Lost"])
    }

    @Test func aFolderWithAMissingParentOrALoopSitsAtTheTop() {
        #expect(tree.parentOf("lost") == nil)
        let looped = PlaylistFolderTree(folders: [Self.folder("x", "X", "y"), Self.folder("y", "Y", "x")], places: [], locale: Locale(identifier: "en"))
        #expect(looped.children(nil).map(\.name) == ["X", "Y"])
        #expect(looped.depth("x") == 1)
    }

    @Test func pathsDepthsAndPlaces() {
        #expect(tree.pathName("a2") == "Apple / Road trips / Summer")
        #expect(tree.depth("a2") == 3)
        #expect(tree.height("a") == 5)
        #expect(tree.folderOf("pl1") == "a2")
        #expect(tree.folderOf("pl2") == nil, "a placement in a missing folder counts as none")
        #expect(tree.playlistsIn("b", keys: ["pl1", "pl2", "local:r1"]) == ["local:r1"])
        #expect(tree.playlistsIn(nil, keys: ["pl1", "pl2", "local:r1"]) == ["pl2"])
        #expect(tree.subtree("a") == ["a", "a1", "a2", "a3", "a4"])
    }

    @Test func aFolderNeverGoesInsideItselfOrPastFourDeep() {
        #expect(tree.moveRefusal("a1", into: "a3") == .intoItself)
        #expect(tree.moveRefusal("a1", into: "a1") == .intoItself)
        #expect(tree.moveRefusal("a1", into: "b") == .tooDeep)
        #expect(tree.moveRefusal("a3", into: "b") == nil)
        #expect(tree.moveRefusal("a3", into: nil) == nil)
        #expect(!tree.canHoldFolder("a3"))
        #expect(tree.canHoldFolder("a2"))
        #expect(tree.canHoldFolder(nil))
        #expect(FolderMoveRefusal.intoItself.words == PlaylistFolderWords.intoItself)
        #expect(FolderMoveRefusal.tooDeep.words == PlaylistFolderWords.tooDeep)
    }

    @Test func treeOrderWalksEachLevelAToZ() {
        #expect(tree.inTreeOrder().map { "\($0.folder.name) \($0.depth)" }
            == ["Apple 1", "Road trips 2", "Summer 3", "Night 4", "Late 5", "banana 1", "cherry 1", "Éclair 1", "Lost 1"])
    }

    @Test func idsNamesAndWhatCanBeFiled() {
        let id = PlaylistFolderTree.newId()
        #expect(id.count == 22)
        #expect(PlaylistFolderTree.isId(id))
        #expect(!PlaylistFolderTree.isId("pf-not-an-id"))
        #expect(PlaylistFolderTree.cleanName("  Night drives ") == "Night drives")
        #expect(PlaylistFolderTree.cleanName("   ") == nil)
        #expect(PlaylistFolderTree.cleanName(String(repeating: "x", count: 101)) == nil)
        #expect(PlaylistFolderTree.cleanName(String(repeating: "😀", count: 100)) == String(repeating: "😀", count: 100))
        #expect(!PlaylistFolderTree.fileable("or1234567890123456789A"))
        #expect(!PlaylistFolderTree.fileable("og1234567890123456789A"))
        #expect(PlaylistFolderTree.fileable("oi1234567890123456789A"))
        #expect(PlaylistFolderTree.fileable("of1234567890123456789A"))
        #expect(PlaylistFolderTree.fileable("local:abc"))
        #expect(PlaylistFolderTree.localKey("abc") == "local:abc")
    }

    @Test func aPagesRowsAreItsFoldersThenItsPlaylistsWithUpkeepOnlyAtTheTop() {
        struct Row {
            let key: String
            var upkeep = false
        }
        let items = [Row(key: "pl1"), Row(key: "pl9"), Row(key: "local:r1"), Row(key: "u1", upkeep: true)]
        let top = playlistPageRows(items, key: \.key, upkeep: \.upkeep, tree: tree, folder: nil)
        #expect(top.folders.map(\.folder.name) == ["Apple", "banana", "cherry", "Éclair", "Lost"])
        #expect(top.playlists.map(\.key) == ["pl9"])
        #expect(top.upkeep.map(\.key) == ["u1"])
        #expect(top.folders.first { $0.folder.id == "b" }?.playlists == 1)
        #expect(top.folders.first { $0.folder.id == "a" }?.folders == 1)
        let summer = playlistPageRows(items, key: \.key, upkeep: \.upkeep, tree: tree, folder: "a2")
        #expect(summer.folders.map(\.folder.id) == ["a3"])
        #expect(summer.playlists.map(\.key) == ["pl1"])
        #expect(summer.upkeep.isEmpty)
        #expect(playlistPageRows(items, key: \.key, upkeep: \.upkeep, tree: nil, folder: nil).playlists.count == 3)
    }

    @Test func theWords() {
        #expect(PlaylistFolderWords.folderSubtitle(playlists: 1, folders: 0) == "1 playlist")
        #expect(PlaylistFolderWords.folderSubtitle(playlists: 1234, folders: 2) == "1,234 playlists, 2 folders")
        #expect(PlaylistFolderWords.folderSubtitle(playlists: 0, folders: 1) == "0 playlists, 1 folder")
        #expect(PlaylistFolderWords.upkeepNote([(name: "Review", count: 3), (name: "Empty", count: 0), (name: "Duplicates", count: 1)])
            == "Review has 3 songs, Duplicates has 1 song")
        #expect(PlaylistFolderWords.upkeepNote([(name: "Review", count: 0)]) == nil)
        #expect(PlaylistFolderWords.deleteBody(parentName: nil) == "Its playlists move to the top level. No playlist is deleted.")
        #expect(PlaylistFolderWords.deleteBody(parentName: "Apple") == "Its playlists move to Apple. No playlist is deleted.")
        #expect(PlaylistFolderWords.deleteTitle("Apple") == "Delete \"Apple\"?")
        #expect(PlaylistFolderWords.moveHere(3) == "Move here (3)")
        #expect(PlaylistFolderWords.bothChanged("Road")
            == "This playlist changed on your server and on this phone. Your phone's version was saved as \"Road (from this phone)\".")
    }
}

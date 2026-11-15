import Foundation
import Testing
@testable import OctoKit

@Suite struct FindGateTests {
    private func track(_ id: String) -> LibraryTrack {
        LibraryTrack(id: id, sourceId: "s", nativeId: id, title: id, searchKey: id, sortKey: id, artist: "", artistId: "", album: "",
                     albumId: "", durationMs: 0, addedAt: 0, relinkKey: "")
    }

    @Test func aFindGoesInAPlaylistOnlyOnAServerThatKeepsIt() {
        #expect(!songActions(find: true, radio: true, lastPlaylist: true).contains(.addToPlaylist))
        #expect(!songActions(find: true, radio: true, lastPlaylist: true).contains(.addToLastPlaylist))
        let opened = songActions(find: true, radio: true, lastPlaylist: true, findsToPlaylists: true)
        #expect(opened.contains(.addToPlaylist))
        #expect(opened.contains(.addToLastPlaylist))
        #expect(!opened.contains(.like), "a find still has no library choices")
        #expect(findsGoInPlaylists(["\(playlistSongsExtension):1", "formPost:1"]))
        #expect(!findsGoInPlaylists(["\(playlistFoldersExtension):1"]))
    }

    @Test func picksAlbumsAndAlbumsFoundOnlineTakeFindsOnlyWithTheExtension() {
        let library = track("s:1")
        let find = track("find:ext-1")
        #expect(playlistAddable([library, find], isFind: { isFind($0.id) }, findsGoIn: false).map(\.id) == ["s:1"])
        #expect(playlistAddable([library, find], isFind: { isFind($0.id) }, findsGoIn: true).map(\.id) == ["s:1", "find:ext-1"])
        #expect(albumSongsToAdd([library], server: [library, find], findsGoIn: true).map(\.id) == ["s:1", "find:ext-1"])
        #expect(albumSongsToAdd([library], server: [library, find], findsGoIn: false).map(\.id) == ["s:1"])
        #expect(albumSongsToAdd([library], server: nil, findsGoIn: true).map(\.id) == ["s:1"])
        #expect(albumSongsToAdd([], server: [find], findsGoIn: true).map(\.id) == ["find:ext-1"], "an album found online")
    }

    @Test func anImportedCopyOffersNoRenameOrDeleteAndAPlaylistFilesWithFolders() {
        let imported = playlistActions(empty: false, imported: true, folders: true)
        #expect(!imported.contains(.rename))
        #expect(!imported.contains(.delete))
        #expect(imported.contains(.duplicate))
        #expect(imported.contains(.moveToFolder))
        #expect(!playlistActions(empty: false).contains(.moveToFolder))
        #expect(collectionMenuGroups(imported)[1] == [.pinToHome, .moveToFolder])
    }

    @Test func onlyActionAndNoticeListsRefuseFinds() {
        #expect(refusesFinds(UpkeepKind.action))
        #expect(refusesFinds(UpkeepKind.notice))
        #expect(!refusesFinds(UpkeepKind.request))
        #expect(!refusesFinds(nil))
        #expect(upkeepMark(true, kind: nil) == UpkeepKind.action, "an older server's upkeep list counts as an action list")
        #expect(upkeepMark(false, kind: UpkeepKind.notice) == nil)
    }
}

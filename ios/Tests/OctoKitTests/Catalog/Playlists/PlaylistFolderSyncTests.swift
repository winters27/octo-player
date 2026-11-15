import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@testable import OctoKit

/// Folder calls for tests: each call is noted, and answers the folders set, or the next failure set.
final class FakeFolderCalls: FolderCalls, @unchecked Sendable {
    private let lock = NSLock()
    private var log: [String] = []
    private var failures: [Error] = []
    private var answer = PlaylistFolders()

    var sent: [String] { lock.withLock { log } }

    func answering(_ folders: PlaylistFolders) { lock.withLock { answer = folders } }

    func failing(_ errors: [Error]) { lock.withLock { failures = errors } }

    private func record(_ line: String) throws -> PlaylistFolders {
        try lock.withLock {
            log.append(line)
            if !failures.isEmpty { throw failures.removeFirst() }
            return answer
        }
    }

    func folders() async throws -> PlaylistFolders { try record("get") }

    func save(id: String, name: String, parent: String?) async throws -> PlaylistFolders { try record("save \(id) \(name) \(parent ?? "top")") }

    func delete(id: String) async throws -> PlaylistFolders { try record("delete \(id)") }

    func place(playlistIds: [String], folderId: String?) async throws -> PlaylistFolders {
        try record("place \(playlistIds.joined(separator: ",")) \(folderId ?? "top")")
    }
}

/// What the listener was told.
final class Told {
    var words: [String] = []
}

@MainActor
@Suite struct PlaylistFolderSyncTests {
    private let a = "pfAAAAAAAAAAAAAAAAAAAA"
    private let b = "pfBBBBBBBBBBBBBBBBBBBB"
    private let c = "pfCCCCCCCCCCCCCCCCCCCC"

    @Test func changesMadeOfflineGoInOrderThenTheServersFoldersAreTaken() async throws {
        let store = try CatalogStore(path: ":memory:")
        try await store.createFolder("acct", id: a, name: "Road trips", parent: nil, at: 1)
        try await store.createFolder("acct", id: b, name: "Summer", parent: a, at: 2)
        try await store.placePlaylists("acct", keys: ["pl1"], in: b, at: 3)
        try await store.renameFolder("acct", id: a, name: "Trips", at: 4)
        let calls = FakeFolderCalls()
        calls.answering(PlaylistFolders(changed: "x", folders: [PlaylistFolder(id: a, name: "Trips"), PlaylistFolder(id: b, name: "Summer", parent: a)],
                                        places: [PlaylistPlace(playlist: "pl1", folder: b)]))
        let end = await syncPlaylistFolders(store: store, space: "acct", calls: calls) { _ in }
        #expect(end == .pulled)
        #expect(calls.sent == ["save \(a) Road trips top", "save \(b) Summer \(a)", "place pl1 \(b)", "save \(a) Trips top", "get"])
        #expect(try await store.folderOps("acct").isEmpty)
        let tree = try await store.playlistFolderTree("acct")
        #expect(tree.pathName(b) == "Trips / Summer")
        #expect(tree.folderOf("pl1") == b)
    }

    @Test func onlyTheSignedInAccountsChangesGo() async throws {
        let store = try CatalogStore(path: ":memory:")
        try await store.createFolder("one", id: a, name: "Mine", parent: nil, at: 1)
        try await store.createFolder("two", id: b, name: "Theirs", parent: nil, at: 2)
        let calls = FakeFolderCalls()
        _ = await syncPlaylistFolders(store: store, space: "one", calls: calls) { _ in }
        #expect(calls.sent == ["save \(a) Mine top", "get"])
        #expect(try await store.folderOps("two").count == 1)
    }

    @Test func aRefusedSignInOrAnUnreachableServerStopsAndKeepsTheChange() async throws {
        let store = try CatalogStore(path: ":memory:")
        try await store.createFolder("acct", id: a, name: "Road trips", parent: nil, at: 1)
        let calls = FakeFolderCalls()
        calls.failing([OctoError.wrongCredentials("Wrong username or password")])
        let refused = await syncPlaylistFolders(store: store, space: "acct", calls: calls) { _ in }
        #expect(refused == .signInRefused)
        #expect(try await store.folderOps("acct").count == 1)
        calls.failing([URLError(.notConnectedToInternet)])
        let unreachable = await syncPlaylistFolders(store: store, space: "acct", calls: calls) { _ in }
        #expect(unreachable == .unreachable)
        #expect(try await store.folderOps("acct").count == 1)
        #expect(!calls.sent.contains("get"))
    }

    @Test func anyOtherRefusalDropsTheChangeAndSaysSoOnce() async throws {
        let store = try CatalogStore(path: ":memory:")
        try await store.createFolder("acct", id: a, name: "Road trips", parent: nil, at: 1)
        try await store.createFolder("acct", id: b, name: "Summer", parent: nil, at: 2)
        let calls = FakeFolderCalls()
        calls.failing([OctoError.notFound("There is no such folder."), OctoError.server(code: 10, message: "Folders can go 4 deep.")])
        let told = Told()
        let end = await syncPlaylistFolders(store: store, space: "acct", calls: calls) { told.words.append($0) }
        #expect(end == .pulled)
        #expect(told.words == [PlaylistFolderWords.folderChangeFailed])
        #expect(try await store.folderOps("acct").isEmpty)
    }

    @Test func aPullKeepsTheChangesStillWaitingAndDropsPlacementsInGoneFolders() async throws {
        let store = try CatalogStore(path: ":memory:")
        try await store.createFolder("acct", id: c, name: "Night", parent: nil, at: 1)
        try await store.placePlaylists("acct", keys: ["pl2"], in: c, at: 2)
        try await store.applyFolderPull("acct", PlaylistFolders(
            folders: [PlaylistFolder(id: a, name: "Road trips")],
            places: [PlaylistPlace(playlist: "pl1", folder: a), PlaylistPlace(playlist: "pl3", folder: "pfGONEGONEGONEGONEGONE")]))
        let tree = try await store.playlistFolderTree("acct")
        #expect(tree.folder(c)?.name == "Night", "a change still waiting is replayed on top")
        #expect(tree.folderOf("pl2") == c)
        #expect(tree.folderOf("pl1") == a)
        #expect(tree.folderOf("pl3") == nil)
        #expect(try await store.folderOps("acct").count == 2)
    }

    @Test func aPhoneOnlyPlacementGoesToTheServerWhenThePlaylistIsSavedAndBackWhenUnlinked() async throws {
        let store = try CatalogStore(path: ":memory:")
        try await store.insertPlaylist(PlaylistRow(id: "r1", name: "Mine", createdAt: 1, updatedAt: 1))
        try await store.createFolder("acct", id: a, name: "Road trips", parent: nil, at: 1)
        let queued = try await store.placePlaylists("acct", keys: [PlaylistFolderTree.localKey("r1")], in: a, at: 2)
        #expect(!queued, "a playlist only on the phone is never sent")
        #expect(try await store.folderOps("acct").map(\.kind) == [FolderOpKind.save])
        #expect(try await store.linkPlaylist("r1", serverId: "pl9", sourceId: "s", syncedAt: 1, name: "Mine", stamp: "", placingIn: "acct", at: 3))
        let ops = try await store.folderOps("acct")
        #expect(ops.last?.kind == FolderOpKind.place)
        #expect(ops.last?.playlistKeys == ["pl9"])
        #expect(ops.last?.folderId == a)
        let tree = try await store.playlistFolderTree("acct")
        #expect(tree.folderOf("pl9") == a)
        #expect(tree.folderOf("local:r1") == nil)
        try await store.unlinkPlaylist("r1")
        let after = try await store.playlistFolderTree("acct")
        #expect(after.folderOf("local:r1") == a)
        #expect(after.folderOf("pl9") == nil)
    }

    @Test func aDeletedFolderMovesItsContentsUpAndNoServerQueuesNothing() async throws {
        let store = try CatalogStore(path: ":memory:")
        try await store.createFolder("", id: a, name: "Road trips", parent: nil, at: 1)
        try await store.createFolder("", id: b, name: "Summer", parent: a, at: 2)
        try await store.placePlaylists("", keys: ["local:r1"], in: b, at: 3)
        try await store.deleteFolder("", id: b, at: 4)
        let tree = try await store.playlistFolderTree("")
        #expect(tree.folder(b) == nil)
        #expect(tree.folderOf("local:r1") == a)
        #expect(try await store.folderOps("").isEmpty)
        #expect(try await store.moveFolder("", id: a, into: a, at: 5) == .intoItself)
    }
}

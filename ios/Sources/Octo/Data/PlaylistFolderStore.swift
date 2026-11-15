import Foundation
import Observation
import OctoKit

/// How long after a folder change it is sent, so quick changes go as one.
private let folderSendDelayMs: Int64 = 1_500

/// The listener's playlist folders on this phone, per account (`space`: the kept server's id, or
/// "" with none in use). Folders are offered on the phone alone and with a server listing
/// octoPlaylistFolders. Each change is written with the change it sends in one transaction
/// (folder ids are made here, so nothing waits for the server), and sent 1.5 s after the last one
/// and after every playlists freshen (`syncPlaylistFolders`): oldest first, only the signed-in
/// account's. A refused sign-in waits for the next sign-in. A playlist only on the phone is placed
/// under "local:<row id>" and never sent.
@MainActor
@Observable
final class PlaylistFolderStore {
    /// The account's folders, with where its playlists sit.
    private(set) var tree = PlaylistFolderTree.empty

    @ObservationIgnored private let session: Session
    @ObservationIgnored private let library: Library
    @ObservationIgnored private let announce: @MainActor (String) -> Void
    @ObservationIgnored private var watch: Watch<String>?
    @ObservationIgnored private var waiting: Task<Void, Never>?
    @ObservationIgnored private var syncing = false
    @ObservationIgnored private var again = false
    /// The sign-in the server refused: nothing is sent again until another.
    @ObservationIgnored private var paused: ObjectIdentifier?

    init(session: Session, library: Library, announce: @escaping @MainActor (String) -> Void) {
        self.session = session
        self.library = library
        self.announce = announce
        watch = Watch({ [weak library, weak session] in "\(session?.inUse?.account ?? "")|\(library?.stamp(.playlists) ?? 0)" }) { [weak self] _ in
            Task { await self?.reload() }
        }
        Task { await reload() }
    }

    /// The account whose folders show.
    var space: String { session.inUse?.account ?? "" }

    /// Whether folders are offered now: with no server in use, or one listing octoPlaylistFolders.
    var offered: Bool { session.inUse.map { $0.lists(playlistFoldersExtension) } ?? true }

    private func reload() async {
        tree = (try? await library.store.playlistFolderTree(space)) ?? .empty
    }

    /// A new folder in `parent` (nil: the top); answers its id, or nil for a name that won't do or a
    /// parent that can't hold one more level.
    @discardableResult
    func create(_ name: String, in parent: String?) async -> String? {
        guard let clean = PlaylistFolderTree.cleanName(name) else { return nil }
        let id = PlaylistFolderTree.newId()
        guard (try? await library.store.createFolder(space, id: id, name: clean, parent: parent, at: epochNowMs())) == true else { return nil }
        later()
        return id
    }

    func rename(_ id: String, to name: String) async {
        guard let clean = PlaylistFolderTree.cleanName(name) else { return }
        if (try? await library.store.renameFolder(space, id: id, name: clean, at: epochNowMs())) == true { later() }
    }

    /// Moves a folder into `parent` (nil: the top); answers why it can't, or nil once moved.
    func move(_ id: String, into parent: String?) async -> FolderMoveRefusal? {
        let refusal = try? await library.store.moveFolder(space, id: id, into: parent, at: epochNowMs())
        if refusal == nil { later() }
        return refusal
    }

    /// Deletes a folder: its folders and playlists move to its parent.
    func delete(_ id: String) async {
        if (try? await library.store.deleteFolder(space, id: id, at: epochNowMs())) == true { later() }
    }

    /// Puts playlists (by placement key) in a folder, or at the top with none.
    func place(_ keys: [String], in folderId: String?) async {
        if (try? await library.store.placePlaylists(space, keys: keys, in: folderId, at: epochNowMs())) == true { later() }
    }

    /// A playlist deleted here: its placement goes.
    func forget(_ key: String) async {
        try? await library.store.forgetPlacements(key)
    }

    /// Sends the waiting changes a moment after the last one.
    func later() {
        waiting?.cancel()
        waiting = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(folderSendDelayMs))
            if Task.isCancelled { return }
            await self?.sync()
        }
    }

    /// Sends the waiting changes and takes the server's folders now.
    func freshen() {
        Task { await sync() }
    }

    /// Sends the signed-in account's changes in order, then takes the server's folders; a send
    /// asked for while one runs runs once more after it.
    private func sync() async {
        guard let server = session.inUse, server.lists(playlistFoldersExtension), paused != ObjectIdentifier(server.client) else { return }
        if syncing {
            again = true
            return
        }
        syncing = true
        defer { syncing = false }
        repeat {
            again = false
            let end = await syncPlaylistFolders(store: library.store, space: server.account, calls: ClientFolderCalls(client: server.client),
                                                notice: announce)
            if end == .signInRefused { paused = ObjectIdentifier(server.client) }
        } while again
    }
}

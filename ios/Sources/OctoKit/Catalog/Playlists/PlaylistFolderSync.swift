import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// The folder calls a send and a pull make: the server's, or a test's.
public protocol FolderCalls: Sendable {
    func folders() async throws -> PlaylistFolders
    func save(id: String, name: String, parent: String?) async throws -> PlaylistFolders
    func delete(id: String) async throws -> PlaylistFolders
    func place(playlistIds: [String], folderId: String?) async throws -> PlaylistFolders
}

/// The signed-in server's folder calls.
public struct ClientFolderCalls: FolderCalls {
    let client: SubsonicClient

    public init(client: SubsonicClient) { self.client = client }

    public func folders() async throws -> PlaylistFolders { try await client.playlistFolders() }

    public func save(id: String, name: String, parent: String?) async throws -> PlaylistFolders {
        try await client.savePlaylistFolder(id: id, name: name, parentId: parent)
    }

    public func delete(id: String) async throws -> PlaylistFolders { try await client.deletePlaylistFolder(id: id) }

    public func place(playlistIds: [String], folderId: String?) async throws -> PlaylistFolders {
        try await client.setPlaylistFolder(playlistIds: playlistIds, folderId: folderId)
    }
}

/// How a folder send ended.
public enum FolderSyncEnd: Sendable, Equatable {
    /// Every waiting change went (or was dropped), and the server's folders were taken.
    case pulled
    /// The server couldn't be reached: the change stays, for the next send.
    case unreachable
    /// The server refused the sign-in (40, 41): the change stays, and nothing goes until the next
    /// sign-in.
    case signInRefused
    /// The changes went, but the server's folders couldn't be read (or the queue written).
    case pullFailed
}

/// Sends an account's waiting folder changes, oldest first, then takes the server's folders in
/// place of the account's with the changes still waiting replayed on top
/// (`CatalogStore.applyFolderPull`). A send that can't reach the server stops and keeps its
/// change; a refused sign-in stops and keeps it; any other refusal (a folder deleted on another
/// device, a rule the server keeps) drops that change, and the listener is told once a send.
@MainActor
public func syncPlaylistFolders(store: CatalogStore, space: String, calls: FolderCalls, notice: @MainActor (String) -> Void) async -> FolderSyncEnd {
    var told = false
    while let op = (try? await store.firstFolderOp(space)) ?? nil {
        do {
            try await sendFolderOp(op, calls)
        } catch let error as OctoError {
            switch error {
            case .wrongCredentials, .authUnsupported:
                return .signInRefused
            case .notSubsonic, .http:
                return .unreachable
            case .server, .notFound:
                if !told {
                    told = true
                    notice(PlaylistFolderWords.folderChangeFailed)
                }
            }
        } catch {
            return .unreachable
        }
        do {
            try await store.dropFolderOp(op.seq)
        } catch {
            return .pullFailed
        }
    }
    guard let answer = try? await calls.folders() else { return .pullFailed }
    do {
        try await store.applyFolderPull(space, answer)
    } catch {
        return .pullFailed
    }
    return .pulled
}

private func sendFolderOp(_ op: FolderOp, _ calls: FolderCalls) async throws {
    switch op.kind {
    case FolderOpKind.save:
        if let id = op.folderId { _ = try await calls.save(id: id, name: op.name ?? "", parent: op.parent) }
    case FolderOpKind.delete:
        if let id = op.folderId { _ = try await calls.delete(id: id) }
    case FolderOpKind.place:
        _ = try await calls.place(playlistIds: op.playlistKeys, folderId: op.folderId)
    default:
        return
    }
}

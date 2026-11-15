import Foundation
import Observation
import OctoKit

/// How long after an edit it is sent, so quick changes go as one.
private let sendDelayMs: Int64 = 1_500

/// Keeps playlists in step with the signed-in server. The server's playlists come to the phone
/// after each copy of its library; a linked playlist edited on the phone goes to the server
/// shortly after, and again after the next copy if that failed. Playlists only on the phone stay
/// there unless the listener saves one to the server, turns on saving new ones, or puts a song
/// found online in one on a server that keeps such songs. What each step does is OctoKit's
/// `PlaylistSteps`; this runs them one at a time (`PlaylistTurns`, each turn starting with the
/// one-time base step for its server), then sends and takes the playlist folders. Nothing here
/// shows an error; the steps tell the listener about songs the server skipped and a playlist kept
/// aside.
@MainActor
@Observable
final class PlaylistSync {
    /// Whether playlists made on the phone are made on the server too. Off until turned on.
    private(set) var newOnServer: Bool

    @ObservationIgnored private let session: Session
    @ObservationIgnored private let library: Library
    @ObservationIgnored private let discovery: ServerDiscovery
    @ObservationIgnored private let folders: PlaylistFolderStore
    @ObservationIgnored private let announce: @MainActor (String) -> Void
    @ObservationIgnored private let prefs = Prefs(ServerSyncChoices.playlistsStore)
    @ObservationIgnored private let choices = ServerSyncChoices()
    @ObservationIgnored private var choiceWatch: NSObjectProtocol?
    /// One change to the server's playlists at a time.
    @ObservationIgnored private let turns = PlaylistTurns()
    /// What each step does.
    @ObservationIgnored private var steps: PlaylistSteps!
    /// Edits waiting to be sent, by playlist.
    @ObservationIgnored private var waits: [String: Task<Void, Never>] = [:]
    /// When the server's playlists were last brought together with the phone's.
    @ObservationIgnored private var reconciledAt: Int64 = 0
    /// Reads the signed-in account as the family sees it, loading it when not loaded yet (set by AccountHooks).
    @ObservationIgnored var familyPlan: @MainActor () async -> FamilyMe? = { nil }

    init(session: Session, library: Library, discovery: ServerDiscovery, sync: ServerSync, folders: PlaylistFolderStore,
         announce: @escaping @MainActor (String) -> Void) {
        self.session = session
        self.library = library
        self.discovery = discovery
        self.folders = folders
        self.announce = announce
        newOnServer = choices.newPlaylistsOnServer
        steps = PlaylistSteps(
            store: library.store,
            trackIdsOf: { [weak discovery] songs in await discovery?.idsFromServer(songs) ?? [] },
            notice: { words in announce(words) },
            forgetDeleted: { [weak self] account, serverId in self?.removeDeleted(account, serverId) },
            foldersChanged: { [weak folders] in folders?.later() })
        steps.familyPlan = { [weak self] in
            guard let self else { return nil }
            return await self.familyPlan()
        }
        // A backup put back sets the choice in the same place; the switch follows it.
        choiceWatch = NotificationCenter.default.addObserver(forName: UserDefaults.didChangeNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.newOnServer != self.choices.newPlaylistsOnServer else { return }
                self.newOnServer = self.choices.newPlaylistsOnServer
            }
        }
        sync.afterSync("playlists") { [weak self] in await self?.afterSync() }
    }

    /// Whether a server is signed in, so a playlist can be saved to it.
    var available: Bool { session.inUse != nil }

    /// Whether a song found online can go in the listener's playlists: a signed-in server listing
    /// octoPlaylistSongs.
    var findsGoIn: Bool { session.inUse.map { findsGoInPlaylists($0.extensions) } ?? false }

    func setNewOnServer(_ on: Bool) {
        newOnServer = on
        choices.setNewPlaylistsOnServer(on)
    }

    /// Runs `work` after any change already running, the one-time base step for `server` first.
    private func serially(_ server: ServerInUse, _ work: @escaping @MainActor () async -> Void) async {
        let playlistServer = server.playlistServer
        await turns.run(base: { [weak self] in try await self?.steps.ensureBase(playlistServer) }, work)
    }

    /// Runs after each copy of the server's library; the folders follow.
    func afterSync() async {
        guard let server = await signedIn() else { return }
        reconciledAt = epochNowMs()
        await serially(server) { await self.steps.reconcile(server.playlistServer, deleted: self.deleted(server.account)) }
        folders.freshen()
    }

    /// The playlists are on screen: one deleted or made on another device since the last copy of
    /// the library is taken out or brought in now, at most once a minute, and the folders follow.
    func freshen() {
        let now = epochNowMs()
        guard playlistsStale(now, reconciledAt) else { return }
        reconciledAt = now
        Task {
            guard let server = await signedIn() else { return }
            await serially(server) { await self.steps.reconcile(server.playlistServer, deleted: self.deleted(server.account)) }
            folders.freshen()
        }
    }

    /// A playlist was made on the phone. With saving new playlists turned on, it is made on the
    /// server too.
    func created(_ localId: String) {
        guard newOnServer else { return }
        saveToServer(localId)
    }

    /// Makes this playlist on the server too, and keeps the two in step.
    func saveToServer(_ localId: String) {
        Task {
            guard let server = await signedIn() else { return }
            try? await library.store.keepOnServer(localId, sourceId: server.sourceId)
            await send(localId)
        }
    }

    /// A song found online went in a playlist only on the phone: on a server that keeps such songs
    /// the playlist is saved there first, so they keep playing, and the listener is told.
    func savedForFinds(_ localId: String) {
        guard findsGoIn else { return }
        announce(PlaylistFolderWords.savedForFinds)
        saveToServer(localId)
    }

    /// A playlist changed on the phone. One kept with the server is sent shortly; one only on the
    /// phone is left alone.
    func changed(_ localId: String) {
        waits[localId]?.cancel()
        waits[localId] = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(sendDelayMs))
            if Task.isCancelled { return }
            self?.waits[localId] = nil
            // Once started, a send runs to the end.
            await Task { await self?.send(localId) }.value
        }
    }

    /// A playlist was deleted on the phone. One kept with the server is deleted there too, now or
    /// after the next copy.
    func deleted(_ playlist: PlaylistRow) {
        guard let serverId = playlist.serverId else { return }
        Task {
            guard let server = await signedIn(), playlist.sourceId == server.sourceId else { return }
            addDeleted(server.account, serverId)
            await serially(server) { _ = await failureOf { try await self.steps.deleteOnServer(server.playlistServer, serverId) } }
        }
    }

    /// Forgets a server taken off the list: its deletions still waiting, and its claim on its address.
    func forget(_ account: String, sourceId: String?) {
        prefs.forgetAccount(account)
        if let sourceId, prefs.string("linked_by", sourceId) == account { prefs.remove("linked_by", sourceId) }
    }

    private func send(_ localId: String) async {
        guard let server = await signedIn() else { return }
        await serially(server) { [weak self] in
            guard let self, let playlist = try? await self.library.store.playlist(localId), playlist.sourceId == server.sourceId else { return }
            _ = await failureOf { try await self.steps.send(server.playlistServer, playlist) }
        }
    }

    /// The signed-in server, if there is one. Playlists linked for another account on the same
    /// address are let go first; other servers' links stay for when they are in use again.
    private func signedIn() async -> ServerInUse? {
        guard let server = session.inUse else { return nil }
        let before = prefs.string("linked_by", server.sourceId)
        if before != server.account {
            prefs.set(server.account, "linked_by", server.sourceId)
            if before != nil { try? await library.store.unlinkServer(server.sourceId) }
        }
        return server
    }

    private func deleted(_ account: String) -> Set<String> { prefs.strings("deleted", account) }

    private func addDeleted(_ account: String, _ serverId: String) {
        prefs.set(strings: deleted(account).union([serverId]), "deleted", account)
    }

    private func removeDeleted(_ account: String, _ serverId: String) {
        prefs.set(strings: deleted(account).subtracting([serverId]), "deleted", account)
    }
}

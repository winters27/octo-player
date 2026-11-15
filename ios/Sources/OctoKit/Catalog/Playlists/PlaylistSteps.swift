import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// The signed-in server as the playlist steps see it: its client, its account (the kept server's
/// id, which folder changes are kept under), its source, and the extensions it lists. Captured
/// when a change is made, so it goes there even after a switch.
public struct PlaylistServer: Sendable {
    public let client: SubsonicClient
    public let account: String
    public let sourceId: String
    public let extensions: [String]

    public init(client: SubsonicClient, account: String, sourceId: String, extensions: [String]) {
        self.client = client
        self.account = account
        self.sourceId = sourceId
        self.extensions = extensions
    }

    public var username: String { client.credentials.username }

    /// Whether the server takes a call's params in a form body.
    public var formPost: Bool { lists(formPostExtension) }

    /// Whether family playlist rows say who may rename or delete them and the server takes
    /// removals by song.
    public var familyRules: Bool { lists(familyPlaylistsExtension) }

    /// Whether the listener's own playlists hold songs found online (octoPlaylistSongs): a push
    /// sends the server's own ids for them, every replace carries octoIfStamp and octoOwnIds,
    /// removals go by song, and a playlist changed on both sides takes the phone's own changes on
    /// top of the server's.
    public var playlistSongs: Bool { lists(playlistSongsExtension) }

    private func lists(_ name: String) -> Bool { extensions.contains { $0.hasPrefix(name + ":") } }
}

extension PlaylistRow {
    /// Changed on the phone since both sides last agreed.
    public var edited: Bool { syncedAt == nil || updatedAt > (syncedAt ?? 0) }

    /// The playlist as planning sees it, when it is linked. An imported playlist's copy, and a
    /// family playlist the listener may not change, only follow the server. `ownRules`: the server
    /// lists octoPlaylistSongs, so the listener's own playlist changed on both sides is pushed
    /// with the family rule.
    public func linked(ownRules: Bool = false) -> LinkedPlaylist? {
        serverId.map {
            LinkedPlaylist(id, $0, name, edited, syncedName, serverStamp, family: family,
                           pullOnly: octoImport || (family && !familyMayAdd && !familyMayRemove),
                           ownRules: ownRules && !family && !octoImport)
        }
    }
}

/// One change to the server's playlists at a time: each turn runs after the one before it,
/// starting with `base` (the one-time base step for its server, `PlaylistSteps.ensureBase`) as
/// its own guarded call. A failure there (a full disk) leaves the turn's work to run with the
/// rules for a playlist that has no base, and the next turn tries the step again.
@MainActor
public final class PlaylistTurns {
    private var running: Task<Void, Never>?

    public init() {}

    public func run(base: @escaping @MainActor () async throws -> Void, _ work: @escaping @MainActor () async -> Void) async {
        let before = running
        let task = Task { @MainActor in
            await before?.value
            do {
                try await base()
            } catch {
                // The next turn tries it again.
            }
            await work()
        }
        running = task
        await task.value
    }
}

/// The steps that keep the phone's playlists and the server's the same (PlaylistPlan.swift), one
/// at a time as PlaylistSync runs them. On a server listing octoPlaylistSongs a push sends the
/// server's own ids for songs found online (outsideSongFor), a replace carries the stamp the phone
/// checked (octoIfStamp) and octoOwnIds, a replace refused because the list changed since
/// (`PlaylistChanged`) is settled as a playlist changed on both sides, and every push ends with
/// the write-back: the server's list read after it becomes the playlist's songs and its base.
/// Changed on both sides, a playlist with a base sends its own changes on top of the server's (as
/// a family playlist does); one with none is kept aside as "<name> (from this phone)" and takes
/// the server's list. Songs the server skipped (octoSkipped) leave the playlist, and the listener
/// hears it.
@MainActor
public final class PlaylistSteps {
    private let store: CatalogStore
    /// Each server song as the phone's library song or find it is, one for each, in order.
    private let trackIdsOf: @MainActor ([Song]) async -> [String]
    /// Tells the listener something: songs the server skipped, a playlist kept aside.
    private let notice: @MainActor (String) -> Void
    /// A deletion the server no longer needs to hear about, by account and server id.
    private let forgetDeleted: @MainActor (String, String) -> Void
    /// A folder placement changed here and waits to go to the server.
    private let foldersChanged: @MainActor () -> Void
    private let now: @MainActor () -> Int64
    /// The signed-in account as the family sees it, loading it when not loaded yet.
    public var familyPlan: @MainActor () async -> FamilyMe? = { nil }
    /// Test hooks: a push's songs went to the server and its write-back is next; the base step read
    /// a playlist's songs and its base is written next.
    public var pushed: @MainActor (String) async -> Void = { _ in }
    public var seeding: @MainActor (String) async -> Void = { _ in }

    public init(store: CatalogStore,
                trackIdsOf: @escaping @MainActor ([Song]) async -> [String],
                notice: @escaping @MainActor (String) -> Void = { _ in },
                forgetDeleted: @escaping @MainActor (String, String) -> Void = { _, _ in },
                foldersChanged: @escaping @MainActor () -> Void = {},
                now: @escaping @MainActor () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.store = store
        self.trackIdsOf = trackIdsOf
        self.notice = notice
        self.forgetDeleted = forgetDeleted
        self.foldersChanged = foldersChanged
        self.now = now
    }

    // MARK: Bringing both sides together

    /// Brings the server's playlists and the phone's together (planPlaylists), then notes what the
    /// server marks on each copy: its notice list, its upkeep kind, an imported playlist's copy, and
    /// what the listener may do with a family playlist. `deleted` are server playlists deleted on
    /// the phone and not yet on the server.
    public func reconcile(_ server: PlaylistServer, deleted: Set<String>) async {
        let client = server.client
        guard let all = try? await client.fullPlaylists() else { return }
        let suspects = stationSuspects(all)
        // Without the list of stations, a playlist that might be one is left out.
        var stations = Set<String>()
        if !suspects.isEmpty {
            if let listed = try? await client.stations() { stations = Set(listed.map(\.id)) } else { stations = Set(suspects.map(\.id)) }
        }
        let keep = importable(all, stationIds: stations, username: server.username)
        let kept = (try? await store.serverPlaylists(server.sourceId)) ?? []
        let keepIds = Set(keep.map(\.id))
        let steps = planPlaylists(
            server: keep.map { ServerPlaylist($0.id, $0.name, stampOf($0.changed, $0.songCount)) },
            others: Set(all.map(\.id)).subtracting(keepIds),
            linked: kept.compactMap { $0.linked(ownRules: server.playlistSongs) },
            waiting: kept.filter { $0.serverId == nil }.map(\.id),
            deleted: deleted)
        for step in steps { try? await run(server, step) }
        var byId: [String: ServerPlaylistInfo] = [:]
        for playlist in keep { byId[playlist.id] = playlist }
        for row in (try? await store.serverPlaylists(server.sourceId)) ?? [] {
            guard let serverId = row.serverId, let playlist = byId[serverId] else { continue }
            if playlist.octoNotice != row.octoNotice { try? await store.markNotice(row.id, notice: playlist.octoNotice) }
            let upkeep = upkeepMark(playlist.octoUpkeep, kind: playlist.octoUpkeepKind)
            if upkeep != row.upkeep { try? await store.markUpkeep(row.id, upkeep: upkeep) }
            if playlist.octoImport != row.octoImport { try? await store.markImport(row.id, playlist.octoImport) }
        }
        // Which of them the server lists as the family's, and what this listener may do with each.
        let familyRows = keep.filter { isFamilyPlaylist(owner: $0.owner, username: server.username, octoFamily: $0.octoFamily) }
        let me = familyRows.isEmpty ? nil : await familyPlan()
        var rights: [String: PlaylistAccess] = [:]
        for playlist in familyRows {
            rights[playlist.id] = playlist.access(username: server.username, me: me, removesById: server.familyRules)
        }
        for row in (try? await store.serverPlaylists(server.sourceId)) ?? [] {
            guard let serverId = row.serverId, keepIds.contains(serverId) else { continue }
            let access = rights[serverId]
            if (access != nil) != row.family { try? await store.markFamily(row.id, family: access != nil) }
            if let access,
               access.add != row.familyMayAdd || access.remove != row.familyMayRemove
                || access.rename != row.familyMayRename || access.delete != row.familyMayDelete {
                try? await store.markFamilyRights(row.id, add: access.add, remove: access.remove, rename: access.rename, delete: access.delete)
            }
        }
    }

    public func run(_ server: PlaylistServer, _ step: PlaylistStep) async throws {
        switch step {
        case .importing(let serverId, let stamp):
            try await importPlaylist(server, serverId, stamp)
        case .pull(let localId, let serverId, let stamp, let keepName):
            try await pull(server, localId, serverId, stamp, keepName)
        case .push(let localId, _):
            if let playlist = try await store.playlist(localId) { try await push(server, playlist) }
        case .create(let localId):
            if let playlist = try await store.playlist(localId) { try await create(server, playlist) }
        case .remove(let localId):
            guard let playlist = try await store.playlist(localId) else { return }
            // Changed here after all: it stays, only on the phone.
            if playlist.edited {
                try await store.unlinkPlaylist(playlist.id)
            } else {
                try await store.deleteUnchangedPlaylist(playlist.id, updatedAt: playlist.updatedAt)
            }
        case .unlink(let localId):
            try await store.unlinkPlaylist(localId)
        case .deleteOnServer(let serverId):
            try await deleteOnServer(server, serverId)
        case .forgetDeletion(let serverId):
            forgetDeleted(server.account, serverId)
        }
    }

    /// A playlist changed on the phone: made on the server when it is not there yet, sent when it
    /// was edited since both sides last agreed.
    public func send(_ server: PlaylistServer, _ playlist: PlaylistRow) async throws {
        if playlist.serverId == nil {
            try await create(server, playlist)
        } else if playlist.edited {
            try await push(server, playlist)
        }
    }

    public func deleteOnServer(_ server: PlaylistServer, _ serverId: String) async throws {
        do {
            try await server.client.deletePlaylist(serverId)
        } catch OctoError.notFound {
            // Already gone.
        } catch let OctoError.server(code, _) where code == 50 {
            // Not allowed (a family playlist for anyone but a manager): never tried again; it comes back to the phone with the next sync.
        }
        forgetDeleted(server.account, serverId)
    }

    // MARK: The one-time base step

    /// The one-time base step for a server listing octoPlaylistSongs, the first thing every turn
    /// runs (PlaylistTurns): until the server's mark is written, each of the listener's own linked
    /// playlists of this server unedited since its last sync gets a base, the songs the phone would
    /// send for it under the outside rule, and each edited one lets go of any base a pull left (a
    /// push wrote none), so the rules for a playlist with no base decide it. A row edited or
    /// unlinked while the step runs is left as it is. The mark is written after every row, so a
    /// failure part way (a full disk) leaves the step for the next turn.
    public func ensureBase(_ server: PlaylistServer) async throws {
        guard server.playlistSongs, !(try await store.baseSeeded(server.sourceId)) else { return }
        for row in try await store.serverPlaylists(server.sourceId) where !row.family && !row.octoImport && row.serverId != nil {
            if row.syncedAt == row.updatedAt {
                let songs = try await songsToSend(server, row.id, outside: true)
                await seeding(row.id)
                _ = try await store.seedBase(row, songs: syncedSongsText(songs))
            } else {
                _ = try await store.clearBase(row)
            }
        }
        try await store.markBaseSeeded(server.sourceId, at: now())
    }

    // MARK: Steps

    /// Makes a phone copy of a server playlist. A copy left on the phone by an earlier sign-in,
    /// with the same name and songs, is linked again instead of made twice (its folder placement
    /// going to the server with it); an imported playlist's copy is never one's twin.
    private func importPlaylist(_ server: PlaylistServer, _ serverId: String, _ stamp: String) async throws {
        let full = try await server.client.fullPlaylist(serverId)
        guard let rows = await rowsOf(full) else { return }
        let isFamily = isFamilyPlaylist(owner: full.owner, username: server.username, octoFamily: full.octoFamily)
        let access = isFamily
            ? full.access(username: server.username, me: await familyPlan(), removesById: server.familyRules) : PlaylistAccess()
        let songs = syncedSongsText(full.entry.map(\.id))
        let upkeep = upkeepMark(full.octoUpkeep, kind: full.octoUpkeepKind)
        if !full.octoImport {
            for playlist in try await store.phonePlaylists() where playlist.name == full.name {
                guard try await store.playlistItems(playlist.id).map(\.trackId) == rows.map(\.trackId) else { continue }
                if try await store.linkPlaylist(playlist.id, serverId: serverId, sourceId: server.sourceId, syncedAt: playlist.updatedAt,
                                                name: playlist.name, stamp: stamp, placingIn: server.account, at: now()) {
                    foldersChanged()
                }
                try await store.markFamily(playlist.id, family: isFamily)
                if isFamily {
                    try await store.markFamilyRights(playlist.id, add: access.add, remove: access.remove, rename: access.rename,
                                                     delete: access.delete)
                }
                try await store.markFamilyBase(playlist.id, songs: songs)
                try await store.markUpkeep(playlist.id, upkeep: upkeep)
                return
            }
        }
        let current = now()
        let at = min(epochMs(full.changed) ?? current, current)
        let id = UUID().uuidString.lowercased()
        let row = PlaylistRow(id: id, name: full.name, createdAt: min(epochMs(full.created) ?? at, at), updatedAt: at,
                              serverId: serverId, sourceId: server.sourceId, syncedAt: at, syncedName: full.name, serverStamp: stamp,
                              family: isFamily, syncedSongs: songs, familyMayAdd: access.add, familyMayRemove: access.remove,
                              familyMayRename: access.rename, familyMayDelete: access.delete, upkeep: upkeep, octoImport: full.octoImport)
        try await store.insertServerPlaylist(row, items: await items(id, rows))
    }

    /// Takes the server's songs into the phone's copy, keeping songs only on the phone where they
    /// were. Skipped if the playlist is edited meanwhile; the next sync settles it.
    private func pull(_ server: PlaylistServer, _ localId: String, _ serverId: String, _ stamp: String, _ keepName: Bool) async throws {
        guard let before = try await store.playlist(localId) else { return }
        let full = try await server.client.fullPlaylist(serverId)
        guard let fromServer = await rowsOf(full) else { return }
        let localItems = try await store.playlistItems(before.id)
        let local = localItems.map { PlaylistSyncRow($0.trackId, $0.serverSongId) }
        let copies = try await serverCopies(server.sourceId, local.map(\.trackId))
        let rows = withPhoneOnly(fromServer, local) { serverSongFor($0, copies[$0.trackId] ?? []) == nil }
        var updated = before
        updated.syncedSongs = syncedSongsText(full.entry.map(\.id))
        if keepName {
            // Still counts as changed here until the name reaches the server.
            updated.serverStamp = stamp
        } else {
            let at = max(now(), before.updatedAt + 1)
            updated.name = full.name
            updated.updatedAt = at
            updated.syncedAt = at
            updated.syncedName = full.name
            updated.serverStamp = stamp
        }
        let ids = await items(before.id, rows, ids: before.family ? keptRowIds(localItems.map { (id: $0.id, trackId: $0.trackId) }, rows) : [])
        guard try await store.takeServerSongs(expected: before, items: ids, updated: updated) else { return }
        if keepName {
            try await server.client.updatePlaylist(serverId, name: before.name)
            guard let after = try await listingStamp(server, serverId) else { return }
            try await store.markSynced(before.id, syncedAt: before.updatedAt, name: before.name, stamp: after)
        }
    }

    /// Sends the phone's songs and name as one replace. When the server changed too since both
    /// last agreed, a server listing octoPlaylistSongs takes the phone's changes on top of its own
    /// (a base kept) or keeps the phone's version aside (none); any other server settles it like a
    /// sync would.
    private func push(_ server: PlaylistServer, _ playlist: PlaylistRow) async throws {
        guard let serverId = playlist.serverId, let linked = playlist.linked(ownRules: server.playlistSongs) else { return }
        // A family playlist is never replaced whole: its own changes go on top.
        if playlist.family {
            try await pushOnTop(server, playlist, serverId)
            return
        }
        let client = server.client
        let listed = try await client.fullPlaylists().first { $0.id == serverId }
            .map { ServerPlaylist($0.id, $0.name, stampOf($0.changed, $0.songCount)) }
        // An imported playlist's copy only follows the server.
        if linked.pullOnly {
            if let step = planLinked(listed, linked) { try await run(server, step) }
            return
        }
        guard let listed, listed.stamp == playlist.serverStamp else {
            if let listed, server.playlistSongs, playlist.edited {
                if playlist.syncedSongs != nil {
                    try await pushOnTop(server, playlist, serverId)
                } else {
                    try await fromThisPhone(server, playlist, serverId, listed)
                }
                return
            }
            if let step = planLinked(listed, linked) { try await run(server, step) }
            return
        }
        let there = try await client.fullPlaylist(serverId)
        let songs = try await songsToSend(server, playlist.id, outside: server.playlistSongs)
        var sent = false
        var skipped: [String] = []
        if there.entry.map(\.id) != songs {
            do {
                skipped = try await client.replacePlaylistSongs(serverId, songIds: songs, formPost: server.formPost,
                                                                ifStamp: server.playlistSongs ? changedOf(playlist.serverStamp) : nil,
                                                                ownIds: server.playlistSongs)
            } catch is PlaylistChanged {
                // Changed on the server since the stamp checked: settled as changed on both sides.
                guard let current = try await store.playlist(playlist.id) else { return }
                if current.syncedSongs != nil {
                    try await pushOnTop(server, current, serverId)
                } else {
                    try await fromThisPhone(server, current, serverId, nil)
                }
                return
            }
            sent = true
        }
        if there.name != playlist.name {
            try await client.updatePlaylist(serverId, name: playlist.name)
            sent = true
        }
        guard server.playlistSongs else {
            let after: String
            if sent {
                guard let stamp = try await listingStamp(server, serverId) else { return }
                after = stamp
            } else {
                after = listed.stamp
            }
            try await store.markSynced(playlist.id, syncedAt: playlist.updatedAt, name: playlist.name, stamp: after)
            return
        }
        await pushed(playlist.id)
        try await pushWriteBack(server, playlist, serverId, pushed: songs, skipped: skipped)
    }

    /// Sends a playlist's own changes since both sides last agreed, on top of everyone else's
    /// (`familySend` decides what): songs taken out here, songs added here at the end, then its
    /// name. A family playlist, and the listener's own one on a server listing octoPlaylistSongs
    /// (its songs by the server's ids, removals by song). The phone then takes the server's list and
    /// name; a song whose removal waits stays out of the phone's copy, and the copy stays marked
    /// changed so it is sent again. Songs the server skipped leave the playlist.
    private func pushOnTop(_ server: PlaylistServer, _ playlist: PlaylistRow, _ serverId: String) async throws {
        let client = server.client
        let own = !playlist.family
        let there = try await client.fullPlaylist(serverId)
        let current = there.entry.map(\.id)
        let phone = own ? try await songsToSend(server, playlist.id, outside: server.playlistSongs)
            : try await familySongsToSend(server, playlist.id)
        let base = syncedSongsOf(playlist.syncedSongs) ?? phone
        let bySong = server.familyRules || (own && server.playlistSongs)
        // A family playlist the listener may only listen to follows the server and sends nothing.
        let pullOnly = !own && !playlist.familyMayAdd && !playlist.familyMayRemove
        var skipped: [String] = []
        let send: FamilySend
        if pullOnly {
            send = FamilySend(changes: FamilyChanges(removeAt: [], add: []), baseAfterSend: current, held: [])
        } else if bySong {
            send = familySend(base: base, phone: phone, current: current, listedCount: current.count, cleanOnly: false)
        } else {
            let removing = !familyChanges(base: base, phone: phone, current: current).removeAt.isEmpty
            let cleanOnly: Bool? = removing ? await familyPlan()?.abilities.cleanOnly : false
            var listedCount: Int?
            if removing && cleanOnly == false {
                listedCount = try await client.fullPlaylists().first { $0.id == serverId }?.songCount
            }
            send = familySend(base: base, phone: phone, current: current, listedCount: listedCount, cleanOnly: cleanOnly)
        }
        if !send.changes.none {
            do {
                if bySong {
                    skipped = try await client.updatePlaylist(serverId, songIdsToAdd: send.changes.add, songIndexesToRemove: [],
                                                              songsToRemove: songsToRemove(current, positions: send.changes.removeAt),
                                                              formPost: server.formPost)
                } else {
                    skipped = try await client.updatePlaylist(serverId, songIdsToAdd: send.changes.add,
                                                              songIndexesToRemove: send.changes.removeAt, formPost: server.formPost)
                }
            } catch {
                // A send whose answer was lost may still have reached Octo; it is checked so the same songs are never added twice.
                if error is OctoError { throw error }
                guard let again = try? await client.fullPlaylist(serverId),
                      familySendLanded(current: current, after: again.entry.map(\.id), changes: send.changes) else { throw error }
            }
            // Kept before anything else can fail, so nothing is sent twice.
            try await store.markFamilyBase(playlist.id, songs: syncedSongsText(send.baseAfterSend))
        }
        if !pullOnly && playlist.name != playlist.syncedName && playlist.name != there.name {
            do {
                try await client.updatePlaylist(serverId, name: playlist.name)
            } catch let OctoError.server(code, _) where code == 50 {
                // Only its maker or a family manager may rename it; the server's name comes back below.
            }
        }
        guard let stamp = try await listingStamp(server, serverId) else { return }
        let full = try await client.fullPlaylist(serverId)
        guard let fromServer = await rowsOf(full) else { return }
        let localItems = try await store.playlistItems(playlist.id)
        let local = localItems.map { PlaylistSyncRow($0.trackId, $0.serverSongId) }
        let copies = try await serverCopies(server.sourceId, local.map(\.trackId))
        let rows = withoutHeld(withPhoneOnly(fromServer, local) { serverSongFor($0, copies[$0.trackId] ?? []) == nil }, send.held)
        var updated = playlist
        let at = max(now(), playlist.updatedAt + 1)
        updated.name = full.name
        updated.updatedAt = at
        // Still changed here while a removal waits, so it is sent again.
        updated.syncedAt = send.held.isEmpty ? at : playlist.syncedAt
        updated.syncedName = full.name
        updated.serverStamp = stamp
        updated.syncedSongs = syncedSongsText(full.entry.map(\.id))
        let taken = try await store.takeServerSongs(
            expected: playlist, items: await items(playlist.id, rows, ids: keptRowIds(localItems.map { (id: $0.id, trackId: $0.trackId) }, rows)),
            updated: updated)
        // Changed here meanwhile: the base is what went less what was skipped, and the change made
        // meanwhile goes on top next time.
        if !taken && !skipped.isEmpty && !send.changes.none {
            try await store.markFamilyBase(playlist.id, songs: syncedSongsText(withoutSkipped(send.baseAfterSend, skipped)))
        }
        try await dropSkipped(playlist.id, skipped)
    }

    /// The write-back after a push on a server listing octoPlaylistSongs: the server's list read
    /// after it becomes the playlist's songs (songs only on the phone kept where they were) and its
    /// base, in one step, refused when the playlist was changed here during the push. Then only the
    /// base moves, to the songs pushed less those the server skipped, and the server's stamp stays
    /// as it was, so the change made meanwhile goes on top of the server's list next time, keeping
    /// anything another device added. Songs the server skipped leave the playlist either way.
    private func pushWriteBack(_ server: PlaylistServer, _ playlist: PlaylistRow, _ serverId: String, pushed: [String],
                               skipped: [String]) async throws {
        guard let stamp = try await listingStamp(server, serverId) else { return }
        let full = try await server.client.fullPlaylist(serverId)
        guard let fromServer = await rowsOf(full) else { return }
        let localItems = try await store.playlistItems(playlist.id)
        let local = localItems.map { PlaylistSyncRow($0.trackId, $0.serverSongId) }
        let copies = try await serverCopies(server.sourceId, local.map(\.trackId))
        let rows = withPhoneOnly(fromServer, local) { serverSongFor($0, copies[$0.trackId] ?? []) == nil }
        let at = max(now(), playlist.updatedAt + 1)
        var updated = playlist
        updated.name = full.name
        updated.updatedAt = at
        updated.syncedAt = at
        updated.syncedName = full.name
        updated.serverStamp = stamp
        updated.syncedSongs = syncedSongsText(full.entry.map(\.id))
        let taken = try await store.takeServerSongs(
            expected: playlist, items: await items(playlist.id, rows, ids: keptRowIds(localItems.map { (id: $0.id, trackId: $0.trackId) }, rows)),
            updated: updated)
        if !taken { try await store.markFamilyBase(playlist.id, songs: syncedSongsText(withoutSkipped(pushed, skipped))) }
        try await dropSkipped(playlist.id, skipped)
    }

    /// Songs the server skipped (octoSkipped) leave the playlist, and the listener hears it once;
    /// nothing when it skipped none.
    private func dropSkipped(_ playlistId: String, _ skipped: [String]) async throws {
        guard !skipped.isEmpty else { return }
        let out = Set(skipped)
        let gone = try await store.playlistItems(playlistId).filter { item in
            if let id = item.serverSongId, out.contains(id) { return true }
            return isFind(item.trackId) && out.contains(String(item.trackId.dropFirst(findPrefix.count)))
        }
        if !gone.isEmpty { try await store.removeItemsFromPlaylist(playlistId, itemIds: gone.map(\.id), now: now()) }
        notice(PlaylistFolderWords.skippedSongs)
    }

    /// A playlist changed on both sides with no base kept (only across the upgrade): the phone's
    /// version is kept as a playlist only on the phone, "<name> (from this phone)", the linked one
    /// takes the server's list, and the listener is told. A copy holding songs found online goes to
    /// the server at once, so they keep playing.
    private func fromThisPhone(_ server: PlaylistServer, _ playlist: PlaylistRow, _ serverId: String, _ known: ServerPlaylist?) async throws {
        var listed = known
        if listed == nil {
            listed = try await server.client.fullPlaylists().first { $0.id == serverId }
                .map { ServerPlaylist($0.id, $0.name, stampOf($0.changed, $0.songCount)) }
        }
        guard let listed else { return }
        let rows = try await store.playlistItems(playlist.id)
        let copyId = UUID().uuidString.lowercased()
        let at = now()
        let copy = PlaylistRow(id: copyId, name: PlaylistFolderWords.fromThisPhone(playlist.name), createdAt: at, updatedAt: at)
        try await store.insertServerPlaylist(copy, items: rows.enumerated().map { position, row in
            PlaylistItem(playlistId: copyId, trackId: row.trackId, relinkKey: row.relinkKey, position: position, serverSongId: row.serverSongId)
        })
        notice(PlaylistFolderWords.bothChanged(playlist.name))
        try await run(server, .pull(localId: playlist.id, serverId: serverId, stamp: listed.stamp, keepName: false))
        if rows.contains(where: { isFind($0.trackId) }) {
            try await store.keepOnServer(copyId, sourceId: server.sourceId)
            if let made = try await store.playlist(copyId) { try await create(server, made) }
        }
    }

    /// Makes a phone playlist on the server and links the two; a folder placement it had on the
    /// phone goes to the server in the transaction that links it. On a server listing
    /// octoPlaylistSongs its songs found online go by their own ids and it ends with the
    /// write-back, so it keeps a base like every linked playlist.
    private func create(_ server: PlaylistServer, _ playlist: PlaylistRow) async throws {
        guard playlist.serverId == nil, playlist.sourceId == server.sourceId else { return }
        let songs = try await songsToSend(server, playlist.id, outside: server.playlistSongs)
        let (made, skipped) = try await server.client.makePlaylistNoting(name: playlist.name, songIds: songs, formPost: server.formPost)
        let serverId: String
        if let id = made?.id { serverId = id } else if let named = try await newestNamed(server, playlist.name) { serverId = named } else { return }
        // Linked at once, so a failure from here on can never make it twice.
        let first = made.map { stampOf($0.changed, $0.songCount) } ?? ""
        if try await store.linkPlaylist(playlist.id, serverId: serverId, sourceId: server.sourceId, syncedAt: playlist.updatedAt,
                                        name: playlist.name, stamp: first, placingIn: server.account, at: now()) {
            foldersChanged()
        }
        if server.playlistSongs, let linked = try await store.playlist(playlist.id) {
            try await pushWriteBack(server, linked, serverId, pushed: songs, skipped: skipped)
            return
        }
        if let stamp = try await listingStamp(server, serverId) {
            try await store.markSynced(playlist.id, syncedAt: playlist.updatedAt, name: playlist.name, stamp: stamp)
        }
    }

    // MARK: Songs

    /// The server songs a phone playlist goes to the server as, in order, without the songs only on
    /// the phone; with `outside`, by outsideSongFor.
    private func songsToSend(_ server: PlaylistServer, _ localId: String, outside: Bool) async throws -> [String] {
        let rows = try await store.playlistItems(localId).map { PlaylistSyncRow($0.trackId, $0.serverSongId) }
        let copies = try await serverCopies(server.sourceId, rows.map(\.trackId))
        guard outside else { return rows.compactMap { serverSongFor($0, copies[$0.trackId] ?? []) } }
        let library = try await libraryIds(server.sourceId, rows.compactMap(\.serverSongId))
        return rows.compactMap { outsideSongFor($0, copies[$0.trackId] ?? [], libraryIds: library) }
    }

    /// A family playlist's songs as the server knows them: each song from the server's copy by the
    /// id the server gave it, a song added here by its copy on the server; songs only on the phone
    /// left out.
    private func familySongsToSend(_ server: PlaylistServer, _ localId: String) async throws -> [String] {
        let rows = try await store.playlistItems(localId).map { PlaylistSyncRow($0.trackId, $0.serverSongId) }
        let copies = try await serverCopies(server.sourceId, rows.map(\.trackId))
        return rows.compactMap { $0.serverSongId ?? serverSongFor($0, copies[$0.trackId] ?? []) }
    }

    /// Each library song's copies on this server, by song.
    private func serverCopies(_ sourceId: String, _ trackIds: [String]) async throws -> [String: [String]] {
        let all = try await store.copies(of: Array(Set(trackIds.filter { !isFind($0) })))
        var copies: [String: [String]] = [:]
        for copy in all where copy.sourceId == sourceId { copies[copy.mergedId, default: []].append(copy.nativeId) }
        return copies
    }

    /// Which of these server ids the library links to a song on this server.
    private func libraryIds(_ sourceId: String, _ serverIds: [String]) async throws -> Set<String> {
        let ids = Array(Set(serverIds))
        guard !ids.isEmpty else { return [] }
        return Set(try await store.libraryLinks(sourceId, ids).map(\.serverId))
    }

    /// A server playlist's songs as phone rows, or nil when they could not all be placed (the
    /// server was signed out meanwhile).
    private func rowsOf(_ playlist: ServerPlaylistInfo) async -> [PlaylistSyncRow]? {
        let ids = await trackIdsOf(playlist.entry)
        guard ids.count == playlist.entry.count else { return nil }
        return serverRows(playlist.entry.map(\.id), ids)
    }

    /// The rows of a playlist's songs, each keeping the id given for its place (0 for a new one).
    private func items(_ playlistId: String, _ rows: [PlaylistSyncRow], ids: [Int64?] = []) async -> [PlaylistItem] {
        var keys: [String: String] = [:]
        for track in (try? await store.tracks(ids: rows.map(\.trackId).filter { !isFind($0) })) ?? [] { keys[track.id] = track.relinkKey }
        return rows.enumerated().map { position, row in
            PlaylistItem(id: ids.indices.contains(position) ? (ids[position] ?? 0) : 0,
                         playlistId: playlistId, trackId: row.trackId, relinkKey: keys[row.trackId] ?? "", position: position,
                         serverSongId: row.serverSongId)
        }
    }

    /// The playlist's stamp as the server lists it now, or nil when the server no longer lists it.
    private func listingStamp(_ server: PlaylistServer, _ serverId: String) async throws -> String? {
        try await server.client.fullPlaylists().first { $0.id == serverId }.map { stampOf($0.changed, $0.songCount) }
    }

    /// For a server too old to answer with the playlist it made: the newest one of the user's by
    /// that name that no phone playlist is linked to (never a family playlist).
    private func newestNamed(_ server: PlaylistServer, _ name: String) async throws -> String? {
        let linked = Set(try await store.serverPlaylists(server.sourceId).compactMap(\.serverId))
        return newestNamedCandidates(try await server.client.fullPlaylists(), name: name, username: server.username, linked: linked)
            .max { ($0.created ?? "") < ($1.created ?? "") }?.id
    }
}

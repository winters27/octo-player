import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@testable import OctoKit

/// A pretend Octo for the playlist steps: playlists by id with their songs and a changed count, the
/// calls the steps make, octoIfStamp checked on a replace, songs it skips, and one replace it can
/// refuse as stale.
final class PlaylistOcto: @unchecked Sendable {
    struct Mix {
        var name: String
        var songs: [String]
        var changed: Int
        var octoImport = false
    }

    private let lock = NSLock()
    private var mixes: [String: Mix] = [:]
    private var skip: Set<String> = []
    private var refuseNext = false
    private var made = 0

    static func stamp(_ changed: Int) -> String { "2026-11-14T10:00:00.\(100 + changed)Z" }

    func set(_ id: String, _ mix: Mix) { lock.withLock { mixes[id] = mix } }

    func mix(_ id: String) -> Mix? { lock.withLock { mixes[id] } }

    func skipping(_ ids: Set<String>) { lock.withLock { skip = ids } }

    func refuseNextReplace() { lock.withLock { refuseNext = true } }

    func reply(_ request: FakeServer.Request) -> FakeServer.Reply? {
        lock.withLock {
            switch request.endpoint {
            case "getPlaylists":
                let rows = mixes.keys.sorted().map { id in Self.row(id, mixes[id]!, songs: false) }
                return Self.ok(#""playlists":{"playlist":[\#(rows.joined(separator: ","))]}"#)
            case "getPlaylist":
                guard let id = request.query["id"], let mix = mixes[id] else { return Self.missing }
                return Self.ok(#""playlist":\#(Self.row(id, mix, songs: true))"#)
            case "createPlaylist":
                let sent = request.values("songId")
                let skipped = sent.filter(skip.contains)
                if let id = request.query["playlistId"] {
                    guard var mix = mixes[id] else { return Self.missing }
                    let stale = request.query[ifStampParam].map { $0 != Self.stamp(mix.changed) } ?? false
                    if refuseNext || stale {
                        refuseNext = false
                        return Self.reply(#"{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":0,"message":"The playlist changed. Read it again.","octoConflict":true}}}"#)
                    }
                    mix.songs = sent.filter { !skip.contains($0) }
                    mix.changed += 1
                    mixes[id] = mix
                    return Self.ok(Self.skippedField(skipped))
                }
                made += 1
                let id = "made\(made)"
                let mix = Mix(name: request.query["name"] ?? "", songs: sent.filter { !skip.contains($0) }, changed: 1)
                mixes[id] = mix
                let extra = skipped.isEmpty ? "" : "," + Self.skippedField(skipped)
                return Self.ok(#""playlist":\#(Self.row(id, mix, songs: true))"# + extra)
            case "updatePlaylist":
                guard let id = request.query["playlistId"], var mix = mixes[id] else { return Self.missing }
                for removal in request.values("octoSongToRemove") {
                    let parts = removal.split(separator: "@")
                    let song = String(parts[0])
                    let copy = parts.count > 1 ? Int(parts[1]) ?? 1 : 1
                    let places = mix.songs.indices.filter { mix.songs[$0] == song }
                    if places.count >= copy { mix.songs.remove(at: places[copy - 1]) }
                }
                let added = request.values("songIdToAdd")
                mix.songs += added.filter { !skip.contains($0) }
                if let name = request.query["name"] { mix.name = name }
                mix.changed += 1
                mixes[id] = mix
                return Self.ok(Self.skippedField(added.filter(skip.contains)))
            default:
                return nil
            }
        }
    }

    private static func row(_ id: String, _ mix: Mix, songs: Bool) -> String {
        let entry = songs ? #","entry":["# + mix.songs.map(song).joined(separator: ",") + "]" : ""
        return #"{"id":"\#(id)","name":"\#(mix.name)","owner":"winters","songCount":\#(mix.songs.count),"changed":"\#(stamp(mix.changed))","readonly":\#(mix.octoImport),"octoImport":\#(mix.octoImport)\#(entry)}"#
    }

    private static func song(_ id: String) -> String {
        #"{"id":"\#(id)","title":"\#(id)","artist":"Artist","album":"Album","duration":200,"isExternal":\#(id.hasPrefix("ext-"))}"#
    }

    private static func skippedField(_ ids: [String]) -> String {
        ids.isEmpty ? "" : #""octoSkipped":[\#(ids.map { "\"\($0)\"" }.joined(separator: ","))]"#
    }

    private static func ok(_ fields: String) -> FakeServer.Reply { reply(FakeServer.okBody(fields)) }

    private static var missing: FakeServer.Reply {
        reply(#"{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":70,"message":"Not found"}}}"#)
    }

    private static func reply(_ body: String) -> FakeServer.Reply { FakeServer.Reply(status: 200, body: Data(body.utf8)) }
}

@MainActor
@Suite struct PlaylistStepsTests {
    let octo = PlaylistOcto()
    let server = FakeServer()

    init() {
        let octo = octo
        server.route { octo.reply($0) }
    }

    private func signedIn(_ extensions: [String] = ["\(playlistSongsExtension):1"], sourceId: String = "s") -> PlaylistServer {
        PlaylistServer(client: server.client(), account: "acct", sourceId: sourceId, extensions: extensions)
    }

    private func makeSteps(_ store: CatalogStore, _ told: Told) -> PlaylistSteps {
        PlaylistSteps(store: store, trackIdsOf: { songs in songs.map { $0.isExternal ? findId($0.id) : "s:\($0.id)" } },
                      notice: { told.words.append($0) }, now: { 1_000 })
    }

    /// The phone's copy "p" of the server's "pl1": its rows (track id and the server id it came
    /// from), its base, whether it was edited since, and the stamp both sides last agreed on.
    private func linked(_ store: CatalogStore, rows: [(String, String?)], base: String?, edited: Bool, changed: Int = 1,
                        count: Int) async throws {
        let row = PlaylistRow(id: "p", name: "Road", createdAt: 1, updatedAt: edited ? 3 : 2, serverId: "pl1", sourceId: "s", syncedAt: 2,
                              syncedName: "Road", serverStamp: stampOf(PlaylistOcto.stamp(changed), count), syncedSongs: base)
        try await store.insertServerPlaylist(row, items: rows.enumerated().map { position, item in
            PlaylistItem(playlistId: "p", trackId: item.0, relinkKey: "", position: position, serverSongId: item.1)
        })
    }

    private func calls(_ endpoint: String) -> [FakeServer.Request] { server.requests.filter { $0.endpoint == endpoint } }

    // MARK: A-T3, pushes

    @Test func aPushSendsOutsideIdsWithTheStampAndOwnIdsThenWritesBack() async throws {
        let store = try CatalogStore(path: ":memory:")
        octo.set("pl1", .init(name: "Road", songs: ["a", "ext-1"], changed: 1))
        try await linked(store, rows: [("s:a", "a"), ("find:ext-1", "ext-1"), ("find:ext-2", nil)], base: "a\next-1", edited: true, count: 2)
        try await makeSteps(store, Told()).send(signedIn(), try #require(try await store.playlist("p")))
        let replace = try #require(calls("createPlaylist").first)
        #expect(replace.values("songId") == ["a", "ext-1", "ext-2"])
        #expect(replace.query[ifStampParam] == PlaylistOcto.stamp(1))
        #expect(replace.query[ownIdsParam] == "1")
        let after = try #require(try await store.playlist("p"))
        #expect(after.syncedSongs == "a\next-1\next-2")
        #expect(!after.edited)
        #expect(after.serverStamp == stampOf(PlaylistOcto.stamp(2), 3))
        #expect(try await store.playlistItems("p").map(\.trackId) == ["s:a", "find:ext-1", "find:ext-2"])
    }

    @Test func aServerWithoutTheExtensionGetsThePushAsBefore() async throws {
        let store = try CatalogStore(path: ":memory:")
        octo.set("pl1", .init(name: "Road", songs: ["a"], changed: 1))
        try await linked(store, rows: [("s:a", "a"), ("find:ext-2", nil)], base: nil, edited: true, count: 1)
        try await makeSteps(store, Told()).send(signedIn([]), try #require(try await store.playlist("p")))
        let replace = try #require(calls("createPlaylist").first)
        #expect(replace.query[ifStampParam] == nil)
        #expect(replace.query[ownIdsParam] == nil)
        #expect(try await store.playlist("p")?.syncedSongs == nil, "no write-back without the extension")
    }

    @Test func aNoBaseRowWhoseServerDidNotChangePushesAnExactReplace() async throws {
        let store = try CatalogStore(path: ":memory:")
        octo.set("pl1", .init(name: "Road", songs: ["a", "b"], changed: 1))
        try await linked(store, rows: [("s:b", "b")], base: nil, edited: true, count: 2)
        try await makeSteps(store, Told()).send(signedIn(), try #require(try await store.playlist("p")))
        #expect(calls("createPlaylist").first?.query[ifStampParam] == PlaylistOcto.stamp(1))
        #expect(octo.mix("pl1")?.songs == ["b"])
        #expect(try await store.playlist("p")?.syncedSongs == "b")
    }

    @Test func aStaleReplaceIsSettledOnTopOfTheServersList() async throws {
        let store = try CatalogStore(path: ":memory:")
        octo.set("pl1", .init(name: "Road", songs: ["a", "ext-1", "c"], changed: 1))
        octo.refuseNextReplace()
        try await linked(store, rows: [("s:a", "a"), ("find:ext-2", nil)], base: "a\next-1", edited: true, count: 3)
        try await makeSteps(store, Told()).send(signedIn(), try #require(try await store.playlist("p")))
        let update = try #require(calls("updatePlaylist").first)
        #expect(update.values("octoSongToRemove") == ["ext-1@1"])
        #expect(update.values("songIdToAdd") == ["ext-2"])
        #expect(octo.mix("pl1")?.songs == ["a", "c", "ext-2"])
        #expect(try await store.playlistItems("p").map(\.trackId) == ["s:a", "s:c", "find:ext-2"])
        #expect(try await store.playlist("p")?.syncedSongs == "a\nc\next-2")
    }

    @Test func bothChangedWithABaseKeepsTheLocalAddAndRemovalOnAPlainPlaylistWithFamilyOff() async throws {
        let store = try CatalogStore(path: ":memory:")
        // Another device added c since both sides agreed on a, b.
        octo.set("pl1", .init(name: "Road", songs: ["a", "b", "c"], changed: 2))
        try await linked(store, rows: [("s:b", "b"), ("find:ext-2", nil)], base: "a\nb", edited: true, changed: 1, count: 2)
        await makeSteps(store, Told()).reconcile(signedIn(), deleted: [])
        #expect(calls("createPlaylist").isEmpty, "never a whole replace over the other device's add")
        #expect(octo.mix("pl1")?.songs == ["b", "c", "ext-2"])
        #expect(try await store.playlistItems("p").map(\.trackId) == ["s:b", "s:c", "find:ext-2"])
    }

    @Test func aNoBaseRowChangedOnBothSidesIsKeptAsideFromThisPhoneAndItsFindGoesToTheServer() async throws {
        let store = try CatalogStore(path: ":memory:")
        octo.set("pl1", .init(name: "Road", songs: ["a", "c"], changed: 2))
        try await linked(store, rows: [("s:a", "a"), ("find:ext-2", nil)], base: nil, edited: true, changed: 1, count: 1)
        let told = Told()
        await makeSteps(store, told).reconcile(signedIn(), deleted: [])
        #expect(told.words.contains(PlaylistFolderWords.bothChanged("Road")))
        #expect(try await store.playlistItems("p").map(\.trackId) == ["s:a", "s:c"], "the linked playlist takes the server's list")
        let copy = try #require(try await store.playlists().first { $0.name == PlaylistFolderWords.fromThisPhone("Road") })
        #expect(copy.serverId == "made1")
        #expect(octo.mix("made1")?.songs == ["a", "ext-2"])
    }

    @Test func aNoBaseExactReplaceAnsweredStaleIsKeptAsideFromThisPhone() async throws {
        let store = try CatalogStore(path: ":memory:")
        octo.set("pl1", .init(name: "Road", songs: ["a"], changed: 1))
        octo.refuseNextReplace()
        try await linked(store, rows: [("s:a", "a"), ("s:b", "b")], base: nil, edited: true, count: 1)
        let told = Told()
        try await makeSteps(store, told).send(signedIn(), try #require(try await store.playlist("p")))
        #expect(told.words == [PlaylistFolderWords.bothChanged("Road")])
        #expect(try await store.playlists().contains { $0.name == PlaylistFolderWords.fromThisPhone("Road") && $0.serverId == nil })
    }

    @Test func anotherDevicesAddBetweenThePushAndTheReadIsKeptByTheWriteBack() async throws {
        let store = try CatalogStore(path: ":memory:")
        octo.set("pl1", .init(name: "Road", songs: ["a"], changed: 1))
        try await linked(store, rows: [("s:a", "a"), ("find:ext-2", nil)], base: "a", edited: true, count: 1)
        let steps = makeSteps(store, Told())
        let octo = octo
        steps.pushed = { _ in
            guard var mix = octo.mix("pl1") else { return }
            mix.songs.append("d")
            mix.changed += 1
            octo.set("pl1", mix)
        }
        try await steps.send(signedIn(), try #require(try await store.playlist("p")))
        #expect(try await store.playlistItems("p").map(\.trackId) == ["s:a", "find:ext-2", "s:d"])
        let after = try #require(try await store.playlist("p"))
        #expect(after.syncedSongs == "a\next-2\nd")
        #expect(after.serverStamp == stampOf(PlaylistOcto.stamp(3), 3))
    }

    @Test func aRowEditedDuringThePushKeepsThePushedListAsItsBaseAndTheOldStamp() async throws {
        let store = try CatalogStore(path: ":memory:")
        octo.set("pl1", .init(name: "Road", songs: ["a"], changed: 1))
        octo.skipping(["ext-9"])
        try await linked(store, rows: [("s:a", "a"), ("find:ext-2", nil), ("find:ext-9", nil)], base: "a", edited: true, count: 1)
        let steps = makeSteps(store, Told())
        steps.pushed = { id in try? await store.renamePlaylist(id, name: "Road trip", now: 5_000) }
        try await steps.send(signedIn(), try #require(try await store.playlist("p")))
        let after = try #require(try await store.playlist("p"))
        #expect(after.syncedSongs == "a\next-2", "the pushed list less what the server skipped")
        #expect(after.serverStamp == stampOf(PlaylistOcto.stamp(1), 1), "the old stamp, so the edit goes on top next time")
        #expect(after.edited)
    }

    // MARK: A-T4, skipped songs

    @Test func songsTheServerSkippedLeaveThePlaylistWithTheNotice() async throws {
        let store = try CatalogStore(path: ":memory:")
        octo.set("pl1", .init(name: "Road", songs: ["a"], changed: 1))
        octo.skipping(["ext-9"])
        try await linked(store, rows: [("s:a", "a"), ("find:ext-9", nil)], base: "a", edited: true, count: 1)
        let told = Told()
        try await makeSteps(store, told).send(signedIn(), try #require(try await store.playlist("p")))
        #expect(told.words == [PlaylistFolderWords.skippedSongs])
        #expect(try await store.playlistItems("p").map(\.trackId) == ["s:a"])
    }

    // MARK: A-T2, a find in a playlist only on the phone

    @Test func aPhoneOnlyPlaylistHoldingAFindIsMadeOnTheServerWithItsIdAndItsPlacement() async throws {
        let store = try CatalogStore(path: ":memory:")
        let folder = "pfAAAAAAAAAAAAAAAAAAAA"
        try await store.insertServerPlaylist(PlaylistRow(id: "n", name: "New", createdAt: 1, updatedAt: 1),
                                             items: [PlaylistItem(playlistId: "n", trackId: "find:ext-9", relinkKey: "", position: 0)])
        try await store.createFolder("acct", id: folder, name: "Road trips", parent: nil, at: 1)
        try await store.placePlaylists("acct", keys: [PlaylistFolderTree.localKey("n")], in: folder, at: 2)
        try await store.keepOnServer("n", sourceId: "s")
        try await makeSteps(store, Told()).send(signedIn(), try #require(try await store.playlist("n")))
        let made = try #require(calls("createPlaylist").first)
        #expect(made.query["name"] == "New")
        #expect(made.values("songId") == ["ext-9"])
        let row = try #require(try await store.playlist("n"))
        #expect(row.serverId == "made1")
        #expect(row.syncedSongs == "ext-9")
        let ops = try await store.folderOps("acct")
        #expect(ops.last?.kind == FolderOpKind.place)
        #expect(ops.last?.playlistKeys == ["made1"])
        #expect(try await store.playlistFolderTree("acct").folderOf("made1") == folder)
    }

    // MARK: A-T8, imported playlists' copies

    @Test func anImportedPlaylistsCopyComesReadOnlyAndIsNeverAPhonePlaylistsTwin() async throws {
        let store = try CatalogStore(path: ":memory:")
        octo.set("oi1234567890123456789A", .init(name: "Imported", songs: ["a"], changed: 1, octoImport: true))
        try await store.insertServerPlaylist(PlaylistRow(id: "twin", name: "Imported", createdAt: 1, updatedAt: 1),
                                             items: [PlaylistItem(playlistId: "twin", trackId: "s:a", relinkKey: "", position: 0)])
        await makeSteps(store, Told()).reconcile(signedIn(), deleted: [])
        let copy = try #require(try await store.serverPlaylists("s").first { $0.serverId == "oi1234567890123456789A" })
        #expect(copy.id != "twin")
        #expect(copy.octoImport)
        #expect(copy.linked(ownRules: true)?.pullOnly == true)
        #expect(copy.linked(ownRules: true)?.ownRules == false)
        #expect(try await store.playlist("twin")?.serverId == nil)
        let summary = try #require(summarize([copy], []).first)
        #expect(summary.octoImport)
        #expect(summary.placeKey == "oi1234567890123456789A")
    }

    // MARK: A-T3, the one-time base step

    private func row(_ id: String, updated: Int64, family: Bool = false, source: String = "s", base: String? = nil) -> PlaylistRow {
        PlaylistRow(id: id, name: id, createdAt: 1, updatedAt: updated, serverId: "sv-\(id)", sourceId: source, syncedAt: 2, syncedName: id,
                    serverStamp: "x|1", family: family, syncedSongs: base)
    }

    @Test func theBaseStepSeedsUneditedRowsLetsEditedOnesGoAndRunsOnceEachServer() async throws {
        let store = try CatalogStore(path: ":memory:")
        try await store.insertServerPlaylist(row("clean", updated: 2), items: [
            PlaylistItem(playlistId: "clean", trackId: "find:ext-1", relinkKey: "", position: 0, serverSongId: "ext-1"),
            PlaylistItem(playlistId: "clean", trackId: "s:a", relinkKey: "", position: 1, serverSongId: "a"),
        ])
        try await store.insertServerPlaylist(row("edited", updated: 3, base: "stale"), items: [])
        try await store.insertServerPlaylist(row("family", updated: 2, family: true, base: "f"), items: [])
        try await store.insertServerPlaylist(row("other", updated: 2, source: "t", base: "o"), items: [])
        let seen = Told()
        let steps = makeSteps(store, Told())
        steps.seeding = { seen.words.append($0) }
        try await steps.ensureBase(signedIn())
        #expect(try await store.playlist("clean")?.syncedSongs == "ext-1\na", "outside ids for outside rows")
        #expect(try await store.playlist("edited")?.syncedSongs == nil, "an edited row keeps no base")
        #expect(try await store.playlist("family")?.syncedSongs == "f", "a family row keeps its own base")
        #expect(try await store.playlist("other")?.syncedSongs == "o", "only the signed-in server's rows")
        #expect(try await store.baseSeeded("s"))
        #expect(seen.words == ["clean"])
        try await steps.ensureBase(signedIn())
        #expect(seen.words == ["clean"], "once a server")
        try await steps.ensureBase(signedIn(sourceId: "t"))
        #expect(try await store.playlist("other")?.syncedSongs == "", "a second server gets its own step")
    }

    @Test func aRowEditedOrUnlinkedDuringTheStepKeepsNoBaseAndTheMarkIsWrittenLast() async throws {
        let store = try CatalogStore(path: ":memory:")
        try await store.insertServerPlaylist(row("one", updated: 2), items: [])
        try await store.insertServerPlaylist(row("two", updated: 2), items: [])
        let steps = makeSteps(store, Told())
        steps.seeding = { id in
            if id == "one" { try? await store.renamePlaylist(id, name: "Renamed", now: 10) } else { try? await store.unlinkPlaylist(id) }
        }
        try await steps.ensureBase(signedIn())
        #expect(try await store.playlist("one")?.syncedSongs == nil)
        #expect(try await store.playlist("two")?.syncedSongs == nil)
        #expect(try await store.baseSeeded("s"))
    }

    @Test func aServerWithoutTheExtensionGetsNoBaseStep() async throws {
        let store = try CatalogStore(path: ":memory:")
        try await store.insertServerPlaylist(row("clean", updated: 2), items: [])
        try await makeSteps(store, Told()).ensureBase(signedIn([]))
        #expect(try await store.playlist("clean")?.syncedSongs == nil)
        let seeded = try await store.baseSeeded("s")
        #expect(!seeded)
    }

    @Test func everyTurnRunsTheBaseStepFirstAndAFailureThereLeavesTheWorkToRun() async {
        struct DiskFull: Error {}
        let turns = PlaylistTurns()
        let order = Told()
        await turns.run(base: {
            order.words.append("base")
            throw DiskFull()
        }) { order.words.append("work") }
        await turns.run(base: { order.words.append("base") }) { order.words.append("work") }
        #expect(order.words == ["base", "work", "base", "work"])
    }
}

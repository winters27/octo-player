import Foundation
import Testing
@testable import OctoKit

// The folder calls, a replace's marks, the ids a server skipped, a stale replace, and the row marks,
// as a server listing octoPlaylistSongs and octoPlaylistFolders sends and reads them.
@Suite struct PlaylistFolderCallsTests {
    let server = FakeServer()

    private let folders = #""playlistFolders":{"changed":"2026-11-14T10:00:00.000Z","folder":[{"id":"pfAAAAAAAAAAAAAAAAAAAA","name":"Road trips","created":"2026-11-14T09:00:00.000Z"},{"id":"pfBBBBBBBBBBBBBBBBBBBB","name":"Summer","parent":"pfAAAAAAAAAAAAAAAAAAAA"}],"place":[{"playlist":"pl1","folder":"pfBBBBBBBBBBBBBBBBBBBB"}]}"#

    @Test func theFolderCallsSendTheirParamsAndReadTheFolders() async throws {
        server.ok(folders)
        let read = try await server.client().playlistFolders()
        #expect(read.changed == "2026-11-14T10:00:00.000Z")
        #expect(read.folders.map(\.name) == ["Road trips", "Summer"])
        #expect(read.folders[0].parent == nil)
        #expect(read.places == [PlaylistPlace(playlist: "pl1", folder: "pfBBBBBBBBBBBBBBBBBBBB")])
        server.ok(folders)
        _ = try await server.client().savePlaylistFolder(id: "pfCCCCCCCCCCCCCCCCCCCC", name: "Night", parentId: nil)
        server.ok(folders)
        _ = try await server.client().savePlaylistFolder(id: "pfCCCCCCCCCCCCCCCCCCCC", name: "Night", parentId: "pfAAAAAAAAAAAAAAAAAAAA")
        server.ok(folders)
        _ = try await server.client().setPlaylistFolder(playlistIds: ["pl1", "pl2"], folderId: nil)
        server.ok(folders)
        _ = try await server.client().deletePlaylistFolder(id: "pfAAAAAAAAAAAAAAAAAAAA")
        let sent = server.requests
        #expect(sent.map(\.endpoint) == ["getPlaylistFolders", "savePlaylistFolder", "savePlaylistFolder", "setPlaylistFolder", "deletePlaylistFolder"])
        #expect(sent[1].query["name"] == "Night")
        #expect(sent[1].query["parentId"] == nil)
        #expect(sent[2].query["parentId"] == "pfAAAAAAAAAAAAAAAAAAAA")
        #expect(sent[3].values("playlistId") == ["pl1", "pl2"])
        #expect(sent[3].query["folderId"] == nil)
        #expect(sent[4].query["id"] == "pfAAAAAAAAAAAAAAAAAAAA")
    }

    @Test func aDeletedFolderIsNotFoundAndAnAnswerWithoutFoldersIsAnError() async {
        server.error(70, "There is no such folder.")
        await #expect(throws: OctoError.notFound("There is no such folder.")) {
            _ = try await server.client().savePlaylistFolder(id: "pfAAAAAAAAAAAAAAAAAAAA", name: "Road trips", parentId: nil)
        }
        server.ok()
        await #expect(throws: OctoError.notSubsonic()) { _ = try await server.client().playlistFolders() }
    }

    @Test func onlyAReplacesFirstCallCarriesTheStampAndOwnIdsMarks() {
        let calls = replaceSongsCalls("pl1", (1...400).map { "s\($0)" }, formPost: false, perCall: 150, ifStamp: "2026-11-14T10:00:00.123Z", ownIds: true)
        #expect(calls[0].params.contains(.init(ifStampParam, "2026-11-14T10:00:00.123Z")))
        #expect(calls[0].params.contains(.init(ownIdsParam, "1")))
        #expect(calls.dropFirst().allSatisfy { call in !call.params.contains { $0.name == ifStampParam || $0.name == ownIdsParam } })
        #expect(!replaceSongsCalls("pl1", ["s1"], formPost: true)[0].params.contains { $0.name == ifStampParam || $0.name == ownIdsParam })
    }

    @Test func answersNameTheSkippedSongsAndAStaleReplaceIsItsOwnError() async throws {
        server.ok(#""octoSkipped":["ext-9"]"#)
        #expect(try await server.client().replacePlaylistSongs("pl1", songIds: ["s1", "ext-9"], ifStamp: "t1", ownIds: true) == ["ext-9"])
        #expect(server.requests[0].query[ifStampParam] == "t1")
        #expect(server.requests[0].query[ownIdsParam] == "1")
        server.ok(#""octoSkipped":["x2"]"#)
        #expect(try await server.client().updatePlaylist("pl1", songIdsToAdd: ["x2"]) == ["x2"])
        server.ok(#""playlist":{"id":"pl9","name":"Drive","songCount":1},"octoSkipped":["x1"]"#)
        let made = try await server.client().makePlaylistNoting(name: "Drive", songIds: ["s1", "x1"])
        #expect(made.playlist?.id == "pl9")
        #expect(made.skipped == ["x1"])
        server.enqueue(#"{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":0,"message":"The playlist changed. Read it again.","octoConflict":true}}}"#)
        await #expect(throws: PlaylistChanged(message: "The playlist changed. Read it again.")) {
            _ = try await server.client().replacePlaylistSongs("pl1", songIds: ["s1"], ifStamp: "old")
        }
        server.error(50, "Only the playlist's owner can change it.")
        await #expect(throws: OctoError.server(code: 50, message: "Only the playlist's owner can change it.")) {
            _ = try await server.client().replacePlaylistSongs("pl1", songIds: ["s1"])
        }
    }

    @Test func theUpkeepAndImportMarksAreReadAndAnImportedCopyIsNeverFromOcto() async throws {
        server.ok(#""playlists":{"playlist":[{"id":"a","name":"Review","octoUpkeep":true,"octoUpkeepKind":"notice"},{"id":"b","name":"Fix","octoUpkeep":true},{"id":"oi1234567890123456789A","name":"Imported","readonly":true,"octoImport":true}]}"#)
        let rows = try await server.client().fullPlaylists()
        #expect(rows.map(\.octoUpkeep) == [true, true, false])
        #expect(rows.map { upkeepMark($0.octoUpkeep, kind: $0.octoUpkeepKind) } == [UpkeepKind.notice, UpkeepKind.action, nil])
        #expect(rows.map(\.octoImport) == [false, false, true])
        #expect(importable(rows, stationIds: [], username: "winters").map(\.id) == ["a", "b", "oi1234567890123456789A"])
        let lists = try JSONDecoder().decode(
            [Playlist].self,
            from: Data(#"[{"id":"oi1234567890123456789A","name":"Imported","owner":"winters","readonly":true,"octoImport":true,"octoGroup":"more"}]"#.utf8))
        #expect(lists[0].octoImport)
        #expect(octoListGroup(lists[0], listener: "winters") == nil)
        #expect(!opensAsServerList(lists[0], runsOcto: true, listener: "winters"))
    }
}

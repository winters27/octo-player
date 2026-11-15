import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@testable import OctoKit

/// The stream versions calls against answers in the exact form an Octo server sends them.
@Suite struct StreamVersionsClientTests {
    static let picked = #"{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","streamVersions":{"id":"X","playsFrom":"stream","playing":{"videoId":"v1","title":"Song","channel":"Artist - Topic","duration":215,"kind":"release","picked":"person","pickedBy":"ede","mine":false,"pickedAt":"2026-11-14T10:00:00Z"},"heardVideoId":"v0","busy":false,"version":[{"videoId":"v2","title":"Song (Live)","channel":"Artist","duration":240,"kind":"video"}],"missed":["youtubeMusic"],"ids":["X","Y"]}}}"#
    static let file = #"{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","streamVersions":{"id":"X","playsFrom":"file","playing":{"videoId":"v9","title":null,"channel":null,"duration":null,"kind":null,"picked":"octo","pickedBy":null,"mine":false,"pickedAt":null},"busy":true,"version":[],"missed":[],"ids":["X"]}}}"#
    static let download = #"{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","streamVersions":{"id":"X","playsFrom":"download","busy":false,"version":[],"missed":[],"ids":["X"]}}}"#
    static let changes = #"{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","streamVersionChanges":{"epoch":"e1","seq":7,"complete":true,"change":[{"seq":6,"ids":["X","Y"]},{"seq":7,"ids":["Z"]}]}}}"#
    static let changesIncomplete = #"{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","streamVersionChanges":{"epoch":"e2","seq":0,"complete":false,"change":[]}}}"#

    let pickedAnswer = StreamVersions(
        id: "X", playsFrom: PLAYS_FROM_STREAM,
        playing: StreamVersionPlaying(videoId: "v1", title: "Song", channel: "Artist - Topic", duration: 215,
                                      kind: VERSION_KIND_RELEASE, picked: PICKED_PERSON, pickedBy: "ede", mine: false,
                                      pickedAt: "2026-11-14T10:00:00Z"),
        heardVideoId: "v0", busy: false,
        version: [StreamVersion(videoId: "v2", title: "Song (Live)", channel: "Artist", duration: 240, kind: VERSION_KIND_VIDEO)],
        missed: [MISSED_YOUTUBE_MUSIC], ids: ["X", "Y"])

    @Test func aListingAsksByTheSongsIdAndReadsEveryField() async throws {
        let server = FakeServer()
        server.enqueue(Self.picked)
        #expect(try await server.client().streamVersions("X") == pickedAnswer)
        let asked = try #require(server.requests.last)
        #expect(asked.endpoint == "getStreamVersions")
        #expect(asked.query["id"] == "X")
    }

    @Test func aSongPlayedFromAFileOrDownloadedOnPlayReadsWhatItLeavesOut() async throws {
        let server = FakeServer()
        server.enqueue(Self.file)
        let file = try await server.client().streamVersions("X")
        #expect(file.playsFrom == PLAYS_FROM_FILE)
        #expect(file.playing == StreamVersionPlaying(videoId: "v9", picked: PICKED_OCTO))
        #expect(file.heardVideoId == nil)
        #expect(file.busy)
        #expect(file.version.isEmpty && file.missed.isEmpty)
        #expect(file.ids == ["X"])
        server.enqueue(Self.download)
        let download = try await server.client().streamVersions("X")
        #expect(download.playsFrom == PLAYS_FROM_DOWNLOAD)
        #expect(download.playing == nil)
        #expect(download.ids == ["X"])
    }

    @Test func aPickSendsItsVideoAndGoingBackSendsAuto() async throws {
        let server = FakeServer()
        server.enqueue(Self.picked)
        #expect(try await server.client().setStreamVersion("X", video: "v2") == pickedAnswer)
        let pick = try #require(server.requests.last)
        #expect(pick.endpoint == "setStreamVersion")
        #expect(pick.query["id"] == "X")
        #expect(pick.query["video"] == "v2")
        server.enqueue(Self.picked)
        try await server.client().setStreamVersion("X", video: STREAM_VERSION_AUTO)
        #expect(server.requests.last?.query["video"] == "auto")
    }

    @Test func aRefusalIsTheServersErrorInItsOwnWords() async {
        let server = FakeServer()
        let client = server.client()
        let notOffered = "That version is not offered for this song any more. Open the list again."
        server.error(70, notOffered)
        await #expect(throws: OctoError.notFound(notOffered)) { try await client.setStreamVersion("X", video: "v9") }
        let beingAdded = "This song is being added to your library right now. You can choose its version once that finishes."
        server.error(0, beingAdded)
        await #expect(throws: OctoError.server(code: 0, message: beingAdded)) { try await client.setStreamVersion("X", video: "v2") }
        let refused = "The version chosen for a song plays for everyone on this server, so this account cannot choose one. Ask a family manager."
        server.error(50, refused)
        await #expect(throws: OctoError.server(code: 50, message: refused)) { try await client.setStreamVersion("X", video: "v2") }
    }

    @Test func anAnswerWithoutItsVersionsIsNotTakenForOne() async {
        let server = FakeServer()
        server.ok()
        await #expect(throws: OctoError.notSubsonic()) { try await server.client().streamVersions("X") }
    }

    @Test func theChangeFeedSendsTheCursorAndReadsEveryChange() async throws {
        let server = FakeServer()
        server.enqueue(Self.changes)
        let changes = try await server.client().streamVersionChanges(epoch: "e1", since: 5)
        #expect(changes == StreamVersionChanges(epoch: "e1", seq: 7, complete: true,
                                                change: [StreamVersionChange(seq: 6, ids: ["X", "Y"]), StreamVersionChange(seq: 7, ids: ["Z"])]))
        let asked = try #require(server.requests.last)
        #expect(asked.endpoint == "getStreamVersionChanges")
        #expect(asked.query["epoch"] == "e1")
        #expect(asked.query["since"] == "5")
        server.enqueue(Self.changesIncomplete)
        let first = try await server.client().streamVersionChanges(epoch: nil, since: nil)
        #expect(first == StreamVersionChanges(epoch: "e2", seq: 0, complete: false))
        let firstAsked = try #require(server.requests.last)
        #expect(firstAsked.query["epoch"] == nil)
        #expect(firstAsked.query["since"] == nil)
    }

    @Test func aPlayTokenIsTheEntryAndItsRestartCount() {
        #expect(playToken(entryId: "e7", restarts: 0) == "e7.0")
        #expect(playToken(entryId: "q-12", restarts: 3) == "q-12.3")
        #expect(PLAY_TOKEN_PARAM == "octoPlay")
        #expect(VIDEO_HEADER == "X-Octo-Video")
    }
}

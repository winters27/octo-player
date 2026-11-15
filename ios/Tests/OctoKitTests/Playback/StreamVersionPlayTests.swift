import Foundation
import Testing
@testable import OctoKit

/// What the iPhone keeps and sends for a version picked for a song found online: the play token,
/// the restart a pick makes, another video as another file, the write fence, the drops that wait
/// for a song to stop, the songs found online among the saved copies, the kept cursor, the menu's
/// gate, the profiles that go, and the sheet as its states draw it.
@Suite struct StreamVersionPlayTests {
    // Red when: the token leaves out the run, the entry or the restarts.
    @Test func aPlayTokenNamesTheRunTheEntryAndItsRestarts() {
        #expect(streamPlayToken(entryId: "q:3", restarts: 2, launch: "run") == "run-q:3.2")
        #expect(streamPlayToken(entryId: "q:3", restarts: 0).hasPrefix(StreamPlays.launch + "-"))
    }

    // Red when: `restarted()` keeps the old size or the count, or the address loses the count.
    @Test func aRestartIsTheNextPlayWithoutTheOldFilesSize() throws {
        let ref = StreamRef(sourceId: "server:a", serverId: "ext-1", mimeType: "audio/mp4", bitrate: nil, size: 4_000_000)
        #expect(!streamUri(ref).contains("restart"))
        let again = ref.restarted()
        #expect(again.size == nil)
        #expect(again.restarts == 1)
        let back = try #require(parseStreamUri(streamUri(again.restarted())))
        #expect(back.restarts == 2)
        #expect(back.size == nil)
        #expect(back.serverId == "ext-1")
        #expect(back.sourceId == "server:a")
        // The saved copy's name is the song's, whatever its restarts.
        let plain = StreamRef(sourceId: "server:a", serverId: "ext-1", mimeType: "audio/mp4", bitrate: nil)
        #expect(streamCacheKey("server:a", back, StreamRequest(nil)) == streamCacheKey("server:a", plain, StreamRequest(nil)))
    }

    // Red when: `differs` leaves out the video, or the index does not keep or compare it.
    @Test func anotherVideoIsAnotherFile() {
        let saved = StreamShape(type: "audio/mp4", total: 4_000_000, video: "v1")
        #expect(saved.differs(from: StreamShape(type: "audio/mp4", total: 4_000_000, video: "v2")))
        #expect(!saved.differs(from: StreamShape(type: "audio/mp4", total: 4_000_000, video: "v1")))
        #expect(!saved.differs(from: StreamShape(type: "audio/mp4", total: 4_000_000)))
        let named = streamShapeOf(status: 206, contentType: "audio/mp4", contentRange: "bytes 0-9/4000000", contentLength: 10, video: " v2 ")
        #expect(named.video == "v2")
        var index = StreamCacheIndex(enabled: true)
        index.describe("find", contentLength: 4_000_000, mimeType: "audio/mp4", video: "v1", nowMs: 1)
        index.wrote("find", offset: 0, count: 1_000_000, freshFile: true, nowMs: 1)
        #expect(index.entries["find"]?.video == "v1")
        let same = index.answered("find", StreamShape(type: "audio/mp4", total: 4_000_000, video: "v1"))
        #expect(!same)
        let other = index.answered("find", StreamShape(type: "audio/mp4", total: 4_000_000, video: "v2"))
        #expect(other)
        let dropped = index.takeDropped()
        #expect(dropped == ["find"])
    }

    @Test func aListSavedBeforeVideosReadsBack() throws {
        let old = #"{"k":{"contentLength":10,"mimeType":"audio/mp4","pieces":[[0,10]],"lastTouchedMs":1}}"#
        let entries = try JSONDecoder().decode([String: StreamCacheEntry].self, from: Data(old.utf8))
        #expect(entries["k"]?.video == nil)
        #expect(entries["k"]?.contentLength == 10)
    }

    // Red when: a bump leaves older writes admitted, reaches another song, or misses another
    // request of the same song.
    @Test func aDropRefusesWhatWasBegunBefore() {
        var fence = StreamWriteFence()
        let key = "server:a|ext-1|audio/mp4|raw|4000000"
        let begun = fence.generation(of: key)
        #expect(fence.admits(key, generation: begun))
        fence.bump(sourceId: "server:a", serverId: "ext-1")
        #expect(!fence.admits(key, generation: begun))
        #expect(!fence.admits("server:a|ext-1|audio/mpeg|192", generation: begun))
        #expect(fence.admits(key, generation: fence.generation(of: key)))
        #expect(fence.admits("server:a|ext-2|audio/mp4|raw", generation: 0))
    }

    // Red when: a song playing loses its copies at once, or keeps them after it stops, or the
    // song opening waits.
    @Test func aSongPlayingKeepsItsCopiesUntilItStops() {
        var drops = VersionDrops()
        let now = drops.take(["ext-1", "ext-2", "ext-3"], playing: ["ext-1", "ext-3"], opening: "ext-3")
        #expect(now == ["ext-2", "ext-3"])
        #expect(drops.waiting == ["ext-1"])
        let stillPlaying = drops.settle(playing: ["ext-1"], opening: nil)
        #expect(stillPlaying.isEmpty)
        let stopped = drops.settle(playing: [], opening: nil)
        #expect(stopped == ["ext-1"])
        #expect(drops.waiting.isEmpty)
        _ = drops.take(["ext-4"], playing: ["ext-4"], opening: nil)
        let opening = drops.settle(playing: ["ext-4"], opening: "ext-4")
        #expect(opening == ["ext-4"])
    }

    // Red when: a song saved without a video counts, or another server's does.
    @Test func theSongsSavedFromAVideoAmongAServersCopies() {
        var index = StreamCacheIndex(enabled: true)
        let found = "5d2Kq0aZ8mT1rVx3Yb7Lc9"
        for (key, video) in [("server:a|\(found)|audio/mp4|raw", "v1"), ("server:a|\(found)|audio/mp4|128|99", "v1"),
                             ("server:a|nd-7|audio/flac|raw", nil), ("server:b|Q1w2E3r4T5y6U7i8O9p0Aa|audio/mp4|raw", "v2")] {
            index.describe(key, contentLength: 10, mimeType: "audio/mp4", video: video, nowMs: 1)
            index.wrote(key, offset: 0, count: 10, freshFile: true, nowMs: 1)
        }
        #expect(videoSavedSongs(index, sourceId: "server:a") == [found])
    }

    // Red when: an answer asked for before a `forget` or `clear` is kept over the song's next one.
    @Test func anAnswerAskedForBeforeAForgetIsNotKept() async throws {
        final class Gate: @unchecked Sendable {
            var profiles: TransitionProfiles?
            var forgetFirst = true
        }
        let gate = Gate()
        let profiles = TransitionProfiles(fetch: { id in
            // The song is given another file while its old profile is on the way.
            if gate.forgetFirst {
                gate.forgetFirst = false
                gate.profiles?.forget([id])
            }
            var answer = try TransitionProfileAnswer.parse(transitionProfileJSON)
            answer.id = id
            answer.ready = true
            return answer
        })
        gate.profiles = profiles
        #expect(await profiles.profile("X") == nil)
        #expect(profiles.cached("X") == nil)
        _ = try #require(await profiles.profile("X"))
        #expect(profiles.cached("X") != nil)
    }

    @Test func aCursorReadsBackAsItWasKept() throws {
        let back = try #require(versionCursor(from: versionCursorText(StreamVersionCursor(epoch: "e-1 2", seq: 42))))
        #expect(back.epoch == "e-1 2")
        #expect(back.seq == 42)
        #expect(versionCursor(from: "e1") == nil)
        #expect(versionCursor(from: "7 ") == nil)
    }

    // Red when: the item shows without the extension, signed out, for a library song or a station.
    @Test func onlyASongFoundOnlineOnAServerThatLetsThisPersonPick() {
        let listed = ["octoStreamVersions:1", "octoTransitions:1"]
        #expect(offersStreamVersions(extensions: listed, signedIn: true, isFound: true, isStation: false))
        #expect(!offersStreamVersions(extensions: ["octoStreamVersionChanges:1"], signedIn: true, isFound: true, isStation: false))
        #expect(!offersStreamVersions(extensions: listed, signedIn: false, isFound: true, isStation: false))
        #expect(!offersStreamVersions(extensions: listed, signedIn: true, isFound: false, isStation: false))
        #expect(!offersStreamVersions(extensions: listed, signedIn: true, isFound: true, isStation: true))
    }

    // Red when: `forget` leaves the song's kept profile, or takes the others with it.
    @Test func profilesOfSongsGivenAnotherFileAreAskedForAgain() async throws {
        final class Asked: @unchecked Sendable { var ids: [String] = [] }
        let asked = Asked()
        let profiles = TransitionProfiles(fetch: { id in
            asked.ids.append(id)
            var answer = try TransitionProfileAnswer.parse(transitionProfileJSON)
            answer.id = id
            answer.ready = true
            return answer
        })
        _ = try #require(await profiles.profile("ext-1"))
        _ = try #require(await profiles.profile("ext-2"))
        profiles.forget(["ext-1"])
        #expect(profiles.cached("ext-1") == nil)
        #expect(profiles.cached("ext-2") != nil)
        _ = await profiles.profile("ext-1")
        #expect(asked.ids == ["ext-1", "ext-2", "ext-1"])
    }

    // The sheet's walk as the iPhone drives it: the entry id is the queue item's, the song id the
    // one in its address, and the way back is its own event.
    @Test func theSheetTakesPicksOnlyForTheEntryPlaying() throws {
        let item = QueueItem(trackId: "find:X", uri: streamUri(StreamRef(sourceId: "server:a", serverId: "X", mimeType: nil, bitrate: nil)),
                             title: "Song", isFind: true, entryId: "q:3")
        let songId = try #require(parseStreamUri(item.uri)?.serverId)
        let answer = StreamVersions(
            id: "X", playsFrom: PLAYS_FROM_STREAM,
            playing: StreamVersionPlaying(videoId: "v1", title: "Song", channel: "Artist - Topic", duration: 215, kind: "release",
                                          picked: PICKED_PERSON, mine: true),
            heardVideoId: "v0",
            version: [StreamVersion(videoId: "v2", title: "Song (Live)", channel: "Artist", duration: 240, kind: "video")],
            missed: [MISSED_YOUTUBE_MUSIC], ids: ["X", "Y"])
        let opened = StreamVersionsSheetState.closed.on(.open(VersionsSong(entryId: "q:3", songId: songId, title: "Song", artist: "Artist")))
        #expect(StreamVersionWords.stateLine(opened) == StreamVersionWords.looking)
        let listed = opened.on(.listed(answer))
        guard case .shown(let shown) = listed else {
            Issue.record("not shown")
            return
        }
        #expect(shown.canUndo)
        #expect(shown.rows.map(\.videoId) == ["v2"])
        #expect(StreamVersionWords.playingNote(try #require(shown.playing), heardVideoId: answer.heardVideoId).hasSuffix(StreamVersionWords.nextStart))
        #expect(listed.on(.pick(videoId: "v2", playingEntryId: "q:4", playingSongId: songId)) == listed)
        let checking = listed.on(.pick(videoId: "v2", playingEntryId: item.entryId, playingSongId: songId))
        #expect(checking != listed)
        let back = listed.on(.undo(playingEntryId: item.entryId, playingSongId: songId))
        guard case .shown(let undoing) = back else {
            Issue.record("not shown")
            return
        }
        #expect(undoing.checking == STREAM_VERSION_AUTO)
        #expect(listed.on(.entryChanged("q:4")) == .closed)
    }
}

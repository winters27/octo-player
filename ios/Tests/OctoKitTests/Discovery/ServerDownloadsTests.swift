import Foundation
import Testing
@testable import OctoKit

private let now = Int64(parseServerInstant("2026-10-04T18:00:00Z")!.timeIntervalSince1970 * 1_000)
private let us = Locale(identifier: "en_US")
private let root = Locale(identifier: "en_US_POSIX")

private func download(_ key: String, _ state: String, startedAt: String = "2026-10-04T17:59:00Z", updatedAt: String? = nil,
                      kind: String? = "download", progress: Double? = nil, note: String? = nil, error: String? = nil,
                      ahead: Int? = nil, libraryId: String? = nil) -> Acquisition {
    let id = String(key.split(separator: ":", maxSplits: 1).last ?? "")
    return Acquisition(id: id, artist: "Air", title: "Song \(key)", state: state, progress: progress, source: "Soulseek",
                       startedAt: startedAt, updatedAt: updatedAt ?? startedAt, error: error, libraryId: libraryId, ahead: ahead,
                       note: note, key: key, kind: kind)
}

private func upgrade(_ id: String, _ state: String, acquisition: String? = nil, detail: String? = nil,
                     updatedAt: String = "2026-10-04T17:58:00Z") -> Upgrade {
    Upgrade(id: id, title: "Up \(id)", artist: "Air", state: state, detail: detail, updatedAt: updatedAt, acquisition: acquisition)
}

/// A held download the server offers to keep anyway, or says nothing of it.
private func held(_ key: String, keepAnyway: Bool = true) -> Acquisition {
    var row = download(key, "searching", note: "1 track waits: play it once, or keep it anyway")
    row.keepAnyway = keepAnyway
    return row
}

/// How Octo's held downloads read, and the call that keeps one anyway, against a server for tests.
@Suite struct KeepAnywayCallTests {
    /// A held song the server offers to keep anyway, a held album that says nothing of it, a song
    /// still on its way, and an album holding two tracks.
    private let heldRows = #""acquisitions":{"acquisition":[{"id":"dz-1","title":"Help!","state":"searching","key":"deezer:1","kind":"download","keepAnyway":true},{"id":"dz-2","title":"Help!","state":"searching","key":"deezer:2","kind":"download"},{"id":"dz-3","title":"Yesterday","state":"downloading","key":"deezer:3","kind":"download","keepAnyway":false},{"id":"dz-4","title":"Rubber Soul","state":"searching","key":"deezer:4","kind":"download","keepAnyway":true,"heldTracks":2}]}"#

    @Test func keepAnywayIsReadFromAServerListingVersion4() async throws {
        let server = FakeServer()
        server.ok(heldRows)
        let rows = try await server.client().acquisitions(keepsAnyway: true)
        // Left out is no, and a song's row holds no tracks.
        #expect(rows.map(\.keepAnyway) == [true, false, false, true])
        #expect(rows.map(\.heldTracks) == [nil, nil, nil, 2])
    }

    @Test func keepAnywayIsNeverReadBelowVersion4EvenWhenSent() async throws {
        let server = FakeServer()
        server.ok(heldRows)
        server.ok(heldRows)
        let client = server.client()
        let plain = try await client.acquisitions()
        #expect(plain.count == 4)
        #expect(plain.allSatisfy { !$0.keepAnyway && $0.heldTracks == nil })
        let older = try await client.acquisitions(keepsAnyway: false)
        #expect(older.allSatisfy { !$0.keepAnyway && $0.heldTracks == nil })
    }

    @Test func oneDownloadReadsKeepAnywayTheSameWay() async throws {
        let server = FakeServer()
        let one = #""acquisition":{"id":"dz-4","title":"Rubber Soul","state":"searching","key":"deezer:4","keepAnyway":true,"heldTracks":3}"#
        server.ok(one)
        server.ok(one)
        let client = server.client()
        let fromVersion4 = try await client.acquisition(key: "deezer:4", keepsAnyway: true)
        let fromOlder = try await client.acquisition(key: "deezer:4")
        #expect(fromVersion4.keepAnyway)
        #expect(fromVersion4.heldTracks == 3)
        #expect(!fromOlder.keepAnyway)
        #expect(fromOlder.heldTracks == nil)
    }

    @Test func keepAnywaySendsTheRowsKey() async throws {
        let server = FakeServer()
        server.ok()
        try await server.client().keepAcquisitionAnyway(key: "Track:deezer:1")
        let request = try #require(server.requests.last)
        #expect(request.path == "/rest/keepAcquisitionAnyway")
        #expect(request.query["key"] == "Track:deezer:1")
        #expect(request.query["f"] == "json")
        #expect(request.query["u"] == "winters")
        // The app keeps the signed-in user's own copy, and never names a user.
        #expect(request.query["user"] == nil)
    }

    @Test func keepAnywaysRefusalsCarryTheServersWords() async {
        let server = FakeServer()
        let client = server.client()
        server.error(70, "Nothing is waiting to be kept")
        server.error(10, "Name the person whose copy to keep.")
        var said: [String] = []
        for _ in 0..<2 {
            do {
                try await client.keepAcquisitionAnyway(key: "deezer:1")
                Issue.record("a refusal must not look like ok")
            } catch {
                said.append(keepAnywayError(error))
            }
        }
        #expect(said == ["Nothing is waiting to be kept", "Name the person whose copy to keep"])
        server.close()
        do {
            try await client.keepAcquisitionAnyway(key: "deezer:1")
            Issue.record("a server out of reach must not look like ok")
        } catch {
            #expect(keepAnywayError(error) == keepAnywayUnreachable)
        }
    }
}

@Suite struct DownloadRowsTests {
    @Test func onlyARowTheServerMarksOffersKeepAnyway() {
        let offered = rowOf(held("s:a"))
        #expect(offered.keepAnyway)
        #expect(keepAnywayLabel(offered) == keepAnywayText)
        #expect(offered.status == "1 track waits: play it once, or keep it anyway")
        // Left out, a plain download, no key to send back, and an upgrade waiting its turn.
        #expect(keepAnywayLabel(rowOf(held("s:a", keepAnyway: false))) == nil)
        #expect(keepAnywayLabel(rowOf(download("s:a", "downloading"))) == nil)
        var keyless = held("s:a")
        keyless.key = nil
        #expect(keepAnywayLabel(rowOf(keyless)) == nil)
        #expect(keepAnywayLabel(rowOf(upgrade("nd-1", "queued"))) == nil)
    }

    @Test func anAlbumHoldingTwoOrMoreSaysKeepTheseAnyway() {
        #expect(keepAnywayWords(heldTracks: nil) == "Keep anyway")
        #expect(keepAnywayWords(heldTracks: 1) == "Keep anyway")
        #expect(keepAnywayWords(heldTracks: 2) == "Keep these anyway")
        #expect(keepAnywayWords(heldTracks: 12) == "Keep these anyway")
        var album = held("s:a")
        album.heldTracks = 1
        #expect(keepAnywayLabel(rowOf(album)) == keepAnywayText)
        album.heldTracks = 2
        #expect(keepAnywayLabel(rowOf(album)) == keepTheseAnywayText)
        // Tracks held, but nothing to keep anyway.
        album.keepAnyway = false
        #expect(keepAnywayLabel(rowOf(album)) == nil)
    }

    @Test func eachStageReadsAsOneLine() {
        #expect(statusOf(download("s:a", "queued", ahead: 3)) == "Waiting, 3 ahead")
        #expect(statusOf(download("s:a", "queued")) == "Waiting its turn")
        #expect(statusOf(download("s:a", "searching", note: "Soulseek couldn't get it, trying YouTube.")) == "Soulseek couldn't get it, trying YouTube")
        #expect(statusOf(download("s:a", "searching")) == "Looking on Soulseek")
        #expect(statusOf(download("s:a", "downloading", progress: 0.42)) == "Downloading from Soulseek, 42%")
        #expect(statusOf(download("s:a", "verifying")) == "Checking the file")
        #expect(statusOf(download("s:a", "importing")) == "Adding to your library")
        #expect(statusOf(download("s:a", "done")) == "In your library")
        #expect(statusOf(download("s:a", "failed", error: "No source had this song.")) == "No source had this song")
    }

    @Test func runningRowsComeFirstThenTheNewestFinished() {
        let rows = drawerRows([
            download("s:old", "done", startedAt: "2026-10-04T17:00:00Z", updatedAt: "2026-10-04T17:05:00Z"),
            download("s:run", "downloading", progress: 0.5),
            download("s:new", "failed", startedAt: "2026-10-04T17:30:00Z", updatedAt: "2026-10-04T17:40:00Z"),
        ], [], now: now)
        #expect(rows.map(\.key) == ["s:run", "s:new", "s:old"])
        #expect(rows[0].fraction == 0.5)
        #expect(runningCount(rows) == 1)
        #expect(overallFraction(rows) == 0.5)
    }

    @Test func anUpgradeJoinsItsDownloadAndItsVerdictIsTheLastWord() {
        let row = drawerRows([download("s:x", "done", kind: "upgrade")],
                             [upgrade("nd-1", "upgraded", acquisition: "s:x", detail: "Now FLAC 16-bit 44.1 kHz, 31.0 MB, was MP3 220 kbps.")],
                             now: now).first!
        #expect(row.kind == .upgrade)
        #expect(row.phase == .done)
        #expect(row.status == "Now FLAC 16-bit 44.1 kHz, 31.0 MB, was MP3 220 kbps")
        #expect(row.findId == "nd-1")
        #expect(row.logKey == "s:x")
    }

    @Test func aDownloadedUpgradeIsStillCheckedBeforeItCountsAsDone() {
        let row = drawerRows([download("s:x", "done", kind: "upgrade")], [upgrade("nd-1", "working", acquisition: "s:x")], now: now).first!
        #expect(row.phase == .checking)
        #expect(!row.finished)
    }

    @Test func anUpgradeWithoutADownloadIsARowOfItsOwnAndAnOldFinishedOneLeaves() {
        let rows = drawerRows([], [
            upgrade("nd-1", "waiting"),
            upgrade("nd-2", "notFound", updatedAt: "2026-10-04T17:00:00Z"),
            upgrade("nd-3", "upgraded", updatedAt: "2026-10-03T10:00:00.1234567Z"),
        ], now: now)
        #expect(rows.map(\.key) == ["upgrade:nd-1", "upgrade:nd-2"])
        #expect(rows[0].status == "Waiting for Soulseek")
        #expect(rows[1].status == "No higher quality copy found")
        #expect(rows[0].logKey == nil)
    }

    @Test func aWaitingUpgradeSaysTheServersOwnWordsForItsWait() {
        let mornye = "Waiting for Mornye's check, which it could not pass for example-ext; it allows its next try at 14:05 UTC."
        let rows = drawerRows([], [upgrade("nd-1", "waiting", detail: mornye), upgrade("nd-2", "waiting", detail: "  ")], now: now)
        let held = rows.first { $0.key == "upgrade:nd-1" }
        #expect(held?.phase == .waiting)
        #expect(held?.status == "Waiting for Mornye's check, which it could not pass for example-ext; it allows its next try at 14:05 UTC")
        // Blank words are no words: the old line stays.
        #expect(rows.first { $0.key == "upgrade:nd-2" }?.status == "Waiting for Soulseek")
    }

    @Test func aClearedRowStaysHidden() {
        #expect(drawerRows([download("s:a", "done")], [upgrade("nd-2", "notFound")], now: now, hidden: ["upgrade:nd-2", "s:a"]).isEmpty)
    }

    @Test func aCopyReadsBestFactFirst() {
        let copy = FoundCandidate(source: "Soulseek", peer: "peer1", file: "03 - Da Funk.flac", title: "Da Funk", format: "flac",
                                  quality: "FLAC 16-bit 44.1 kHz", size: 31_000_000, length: 330, freeSlot: true, speed: 1_500_000, rank: 1)
        #expect(candidateFacts(copy, locale: us) == ["FLAC 16-bit 44.1 kHz", "29.6 MB", "5:30", "Soulseek", "peer1", "Free to send now", "1.4 MB/s"])
        #expect(candidateTitle(copy) == "Da Funk")
        #expect(candidateVerdict(copy) == "Octo's first choice")
        var third = copy
        third.rank = 3
        #expect(candidateVerdict(third) == "Octo's choice 3")
        var passed = copy
        passed.rank = nil
        passed.note = "From a live album."
        #expect(candidateVerdict(passed) == "From a live album")
        #expect(isLosslessCopy(copy))
        var bits = copy
        bits.format = "FLAC 24bit"
        #expect(isLosslessCopy(bits))
        bits.format = "MP3-320"
        #expect(!isLosslessCopy(bits))
        #expect(candidateFacts(FoundCandidate(source: "", format: "mp3", queueLength: 3), locale: us) == ["MP3", "3 in queue"])
    }

    @Test func theSummaryCountsWhatRunsAndWhatFinished() {
        let rows = drawerRows([download("s:a", "done"), download("s:b", "searching"), download("s:c", "failed")], [], now: now)
        #expect(drawerSummary(rows) == "1 on the way, 2 finished")
        #expect(drawerSummary([]) == "")
        #expect(ownedCopyText(quality: "MP3 220 kbps", size: 7_000_000, locale: us) == "Your copy: MP3 220 kbps, 6.7 MB")
        #expect(ownedCopyText(quality: nil, size: nil) == nil)
    }

    @Test func aRowWaitingForThePersonIsNotOnTheWayButTheListIsStillAskedForOften() {
        var album = download("s:w", "searching", note: "2 tracks wait: play them once, or keep them anyway")
        album.keepAnyway = true
        album.heldTracks = 2
        var off = download("s:x", "searching", note: "AcoustID is off, so Octo cannot check what played")
        off.keepAnyway = true
        let rows = drawerRows([download("s:a", "done"), download("s:b", "downloading", progress: 0.4), album], [], now: now)
        #expect(drawerSummary(rows) == "1 on the way, 1 waits for you, 1 finished")
        #expect(drawerState(rows) == "1 on the way, 1 waits for you")
        #expect(drawerMark(rows) == .ring)
        let waiting = drawerRows([album, off], [], now: now)
        #expect(drawerSummary(waiting) == "2 wait for you")
        #expect(drawerState(Array(waiting.prefix(1))) == "1 waits for you")
        #expect(drawerMark(waiting) == .waits)
        let done = drawerRows([download("s:a", "done")], [], now: now)
        #expect(drawerMark(done) == .nothing)
        #expect(drawerState(done) == nil)
        #expect(onTheWayCount(waiting) == 0)
        #expect(runningCount(waiting) == 2, "Still asked for at the open rows' pace")
        #expect(pillLead(waiting) == nil)
        #expect(pillCount(waiting, [], listedSince: nil, now: now) == 0)
        #expect(pillLead(rows)?.key == "s:b")
        #expect(pillSpoken(rows) == "Downloading 40%, Song s:b")
    }

    @Test func anAlbumsRowIsAnAlbumInEveryStateAndItsLogLooksForNoSong() {
        let kept = rowOf(download("s:a", "done", kind: "album"))
        #expect(kept.kind == .album)
        #expect(kindLabel(kept.kind) == nil)
        #expect(logFindId(kept) == nil)
        #expect(logFindId(rowOf(download("s:a", "searching", kind: "album"))) == nil)
        #expect(logFindId(rowOf(download("s:a", "done"))) == "a")
    }

    @Test func logLinesShowTheClockTime() {
        #expect(logTime("2026-10-04T18:04:31Z", zone: TimeZone(identifier: "UTC")!) == "18:04:31")
        #expect(logTime(nil) == "")
        #expect(logTime("not a time") == "")
    }

    @Test func aCopysKeyIsItsIdOrItsPlace() {
        #expect(copyKey(FoundCandidate(index: 0, id: "c1")) == "id:c1")
        #expect(copyKey(FoundCandidate(index: 0)) == "at:0")
    }

    @Test func thePillSpeaksForTheSongFurthestAlong() {
        let rows = [
            rowOf(download("s:a", "queued", startedAt: "2026-10-04T17:50:00Z")),
            rowOf(download("s:b", "downloading", startedAt: "2026-10-04T17:52:00Z", progress: 0.42)),
            rowOf(download("s:c", "downloading", startedAt: "2026-10-04T17:51:00Z", progress: 0.9)),
            rowOf(download("s:d", "done")),
        ]
        // Two at the same step: the one that started first, not the one further in.
        #expect(pillLead(rows)?.key == "s:c")
        #expect(stepWords(pillLead(rows)!) == "Downloading 90%")
        #expect(pillSpoken(rows) == "Downloading 90%, Song s:c. 2 more on the way")
        #expect(pillLead([rowOf(download("s:d", "done"))]) == nil)
        #expect(stepWords(rowOf(download("s:a", "verifying"))) == "Checking")
        #expect(stepWords(rowOf(download("s:a", "importing"))) == "Adding")
        #expect(stepWords(rowOf(download("s:a", "searching"))) == "Searching")
        #expect(stepWords(rowOf(download("s:a", "downloading"))) == "Downloading")
    }

    @Test func eachPhaseSitsOnItsStepAndAFailureStopsWhereItsLogGot() {
        #expect(stepOf(rowOf(download("s:a", "queued"))) == 0)
        #expect(stepOf(rowOf(download("s:a", "downloading"))) == 1)
        #expect(stepOf(rowOf(download("s:a", "verifying"))) == 2)
        #expect(stepOf(rowOf(download("s:a", "importing"))) == 3)
        #expect(stepOf(rowOf(download("s:a", "done"))) == DownloadStep.allCases.count)
        let failed = rowOf(download("s:a", "failed"))
        let log = [
            AcquisitionEvent(kind: "search", text: "Looking"),
            AcquisitionEvent(kind: "transfer", text: "Downloading"),
            AcquisitionEvent(kind: "check", text: "Checking the file"),
            AcquisitionEvent(kind: "failed", text: "Wrong song"),
            AcquisitionEvent(kind: "note", text: "Gave up"),
        ]
        #expect(stepOf(failed, events: log) == DownloadStep.check.rawValue)
        #expect(stepOf(failed) == DownloadStep.find.rawValue)
        #expect(markOf(LogKind.of("something new")) == .note)
    }

    @Test func aRowThatAsksThePersonToPlayOrKeepWaitsForThemAndOneWaitingOnAServiceDoesNot() {
        var album = rowOf(download("s:a", "searching", note: "2 tracks wait: play them once, or keep them anyway"))
        album.keepAnyway = true
        album.heldTracks = 2
        #expect(waitsForYou(album))
        album.keepAnyway = false
        #expect(waitsForYou(album), "An album seen by someone it does not offer Keep anyway")
        #expect(waitsForYou(rowOf(download("s:a", "searching", note: "1 track waits: play it once, or keep it anyway"))))
        var off = rowOf(download("s:a", "searching", note: "AcoustID is off, so Octo cannot check what played"))
        off.keepAnyway = true
        #expect(waitsForYou(off))
        off.keepAnyway = false
        #expect(!waitsForYou(off), "The same line without Keep anyway waits for a key")
        #expect(waitsForYou(rowOf(download("s:a", "searching", note: "Play it once so Octo can keep the version you hear"))))
        #expect(waitsForYou(rowOf(download("s:a", "searching", note: "Octo played another song for this one. Play it once more so Octo can keep the version you hear"))))
        for line in ["Waiting for AcoustID", "Waiting for MusicBrainz", "Waiting for Lidarr's indexers", "Keeping the version you heard once YouTube answers"] {
            #expect(!waitsForYou(rowOf(download("s:a", "searching", note: line))), "\(line)")
        }
        #expect(!waitsForYou(rowOf(download("s:a", "searching"))))
        var running = rowOf(download("s:a", "downloading"))
        running.keepAnyway = true
        #expect(!waitsForYou(running))
        #expect(!waitsForYou(rowOf(download("s:a", "done"))))
    }

    @Test func anAlbumsLogOffersNoFindSongsASongsDoes() {
        let song = rowOf(download("s:a", "searching", note: "AcoustID is off, so Octo cannot check what played"))
        #expect(logFindId(song) == "a")
        var album = rowOf(download("s:b", "searching", note: "2 tracks wait: play them once, or keep them anyway"))
        album.heldTracks = 2
        #expect(logFindId(album) == nil)
    }

    @Test func aHeldDownloadWaitsOnTheStepItsLogReachedAndASearchBegunAgainIsBackOnFind() {
        let held = rowOf(download("s:a", "searching", note: "Waiting for AcoustID"))
        let log = [
            AcquisitionEvent(kind: "search", text: "Looking on Soulseek"),
            AcquisitionEvent(kind: "transfer", text: "Downloading from Soulseek"),
            AcquisitionEvent(kind: "check", text: "Checking the file"),
            AcquisitionEvent(kind: "note", text: "Waiting for AcoustID"),
        ]
        #expect(stepOf(held, events: log) == DownloadStep.check.rawValue)
        let album = log + [
            AcquisitionEvent(kind: "library", text: "In the library folder"),
            AcquisitionEvent(kind: "note", text: "2 tracks wait: play them once, or keep them anyway"),
        ]
        #expect(stepOf(held, events: album) == DownloadStep.add.rawValue)
        #expect(stepOf(held, events: log + [AcquisitionEvent(kind: "search", text: "Looking on YouTube")]) == DownloadStep.find.rawValue)
        #expect(stepOf(held) == DownloadStep.find.rawValue)
        #expect(stepOf(rowOf(download("s:a", "queued")), events: log) == DownloadStep.find.rawValue)
    }

    @Test func aCopysBadgeTakesItsKindAndTheFactsKeepTheRest() {
        let flac = FoundCandidate(source: "Soulseek", format: "flac", quality: "FLAC 16-bit 44.1 kHz", size: 36_100_000, length: 329, freeSlot: true)
        #expect(copyBadge(flac) == "FLAC")
        #expect(badgeFacts(flac, locale: root) == ["16-bit 44.1 kHz", "34.4 MB", "5:29", "Soulseek", "Free to send now"])
        #expect(badgeFacts(FoundCandidate(source: "Soulseek", format: "flac", quality: "FLAC"), locale: root) == ["Soulseek"])
        #expect(copyBadge(FoundCandidate(source: "Lidarr")) == nil)
        #expect([1, 2, 3, 4, 11, 22].map(rankWords) == ["1st choice", "2nd choice", "3rd choice", "4th choice", "11th choice", "22nd choice"])
    }

    // MARK: The pill's count

    private func asked(_ id: String, _ agoMs: Int64) -> AskedSong { AskedSong(serverId: id, askedAt: now - agoMs) }
    private func rows(_ list: Acquisition...) -> [DownloadRow] { drawerRows(list, [], now: now) }

    @Test func aSongAskedForLongAgoThatNoListShowsIsNotCounted() {
        #expect(pillCount([], [asked("a", 60 * 60_000)], listedSince: now - 5_000, now: now) == 0)
    }

    @Test func aSongThatLandedLeavesThePillEvenBeforeAnyListComesBack() {
        let landed = askedSongs([FindSong(id: "find:a", sourceId: "server:octo", title: "Nightcall", artist: "Kavinsky",
                                          requestedAt: now - 1_000, adoptedId: "nd-7")], sourceId: "server:octo")
        #expect(pillCount([], landed, listedSince: nil, now: now) == 0)
    }

    @Test func theReportersPillAnOldAskWithAnEmptyDrawerIsGone() {
        let now: Int64 = 100 * 60_000
        let asked = askedSongs([FindSong(id: "find:a", sourceId: "server:octo", title: "Nightcall", artist: "Kavinsky",
                                         requestedAt: now - 60 * 60_000)], sourceId: "server:octo")
        // After a list came back, and straight after a restart, before any list.
        #expect(pillCount([], asked, listedSince: now - 3_000, now: now) == 0)
        #expect(pillCount([], asked, listedSince: nil, now: now) == 0)
    }

    @Test func aSongJustAskedForCountsUntilTheFirstListAfterIt() {
        let ask = asked("a", 1_000)
        #expect(pillCount([], [ask], listedSince: now - 10_000, now: now) == 1)
        #expect(pillCount([], [ask], listedSince: nil, now: now) == 1)
        #expect(pillCount([], [ask], listedSince: now - 500, now: now) == 0)
    }

    @Test func aListedSongCountsOnceWhicheverListIsNewer() {
        let running = rows(download("s:a", "downloading", progress: 0.4))
        let ask = asked("a", 1_000)
        #expect(pillCount(running, [ask], listedSince: now - 500, now: now) == 1)
        #expect(pillCount(running, [ask], listedSince: now - 10_000, now: now) == 1)
    }

    @Test func aSongListedUnderItsLibraryIdCountsOnce() {
        let importing = rows(download("s:a", "importing", libraryId: "nd-9"))
        #expect(importing.first?.findId == "nd-9")
        #expect(pillCount(importing, [asked("a", 1_000)], listedSince: now - 10_000, now: now) == 1)
    }

    @Test func aDoneOrFailedSongIsNotCounted() {
        let ask = asked("a", 60_000)
        #expect(pillCount(rows(download("s:a", "done")), [ask], listedSince: now - 500, now: now) == 0)
        #expect(pillCount(rows(download("s:a", "failed", error: "no copy")), [ask], listedSince: now - 500, now: now) == 0)
        #expect(pillCount([], [ask], listedSince: now - 500, now: now) == 0)
    }

    @Test func withNoListComingBackAnAskCountsTwoMinutesAtMost() {
        #expect(pillCount([], [asked("a", askedUnlistedMs - 1)], listedSince: nil, now: now) == 1)
        #expect(pillCount([], [asked("a", askedUnlistedMs)], listedSince: nil, now: now) == 0)
        #expect(pillCount([], [asked("a", 3 * 60 * 60_000)], listedSince: nil, now: now) == 0)
        #expect(pillCount([], [asked("a", -60_000)], listedSince: nil, now: now) == 0)
    }

    @Test func runningRowsCountWithoutAnyAsk() {
        let list = rows(download("s:a", "downloading"), download("s:b", "queued"), download("s:c", "done"))
        #expect(pillCount(list, [], listedSince: now - 500, now: now) == 2)
    }

    @Test func thePillLooksAgainWhenTheFirstCountedAskRunsOut() {
        let asks = [asked("a", 30_000), asked("b", 90_000)]
        #expect(pillRecheckMs([], asks, listedSince: nil, now: now) == 30_000)
        #expect(pillRecheckMs([], asks, listedSince: now - 500, now: now) == nil)
        #expect(pillRecheckMs(rows(download("s:a", "downloading")), [asked("a", 30_000)], listedSince: nil, now: now) == nil)
    }

    @Test func aSongAskedWithItsAlbumCountsUntilTheAlbumIsListedThirtyMinutesAtMost() {
        let ask = AskedSong(serverId: "a", askedAt: now - 10 * 60_000, album: true)
        #expect(stillAsked(ask, listedSince: now - 1_000, now: now))
        #expect(pillCount([], [ask], listedSince: now - 1_000, now: now) == 1)
        #expect(!stillAsked(AskedSong(serverId: "a", askedAt: now - askedAlbumUnlistedMs, album: true), listedSince: now - 1_000, now: now))
        let left = stillAskedForMs(ask, listedSince: now - 1_000, now: now)
        #expect(left == Int64(20 * 60_000), "(String(describing: left))")
        #expect(stillAskedForMs(AskedSong(serverId: "a", askedAt: now - 1_000), listedSince: now - 500, now: now) == nil)
    }

    @Test func onlySongsAskedForOnThisServerThatHaveNotLandedAreAsked() {
        func find(_ id: String, requestedAt: Int64 = 1_000, adoptedId: String = "", sourceId: String = "server:octo") -> FindSong {
            FindSong(id: id, sourceId: sourceId, title: "Nightcall", artist: "Kavinsky", album: "OutRun", requestedAt: requestedAt, adoptedId: adoptedId)
        }
        let list = [find("a", requestedAt: 5_000), find("b", adoptedId: "nd-7"), find("c", sourceId: "server:other"), find("d", requestedAt: 0)]
        #expect(askedSongs(list, sourceId: "server:octo") == [AskedSong(serverId: "a", askedAt: 5_000)])
        #expect(askedSongs(list, sourceId: nil).isEmpty)
    }

    @Test func theWordsCountTheSongsAndTheRingFollowsTheServersStep() {
        #expect(comingText(1) == "1 downloading")
        #expect(comingText(3) == "3 downloading")
        func row(_ state: String, _ progress: Double? = nil) -> DownloadRow {
            rowOf(Acquisition(id: "a", artist: "Daft Punk", title: "Da Funk", state: state, progress: progress, key: "s:a", kind: "download"))
        }
        #expect(ringPhase(row("queued")) == .queued)
        #expect(ringPhase(row("searching")) == .queued)
        #expect(ringPhase(row("downloading", 0.4)) == .downloading(0.4))
        #expect(ringPhase(row("verifying")) == .adding)
        #expect(ringPhase(row("importing")) == .adding)
    }

    // MARK: Replacing a library song's copy

    private let compare = PickCompare(yours: PickSide(quality: "FLAC", length: 420, take: "live recording"),
                                      copy: PickSide(quality: "FLAC", length: 298, take: "the plain song"))

    @Test func aComparisonLeavesOutWhatIsMissing() {
        #expect(compareLines(PickCompare(yours: PickSide(quality: "MP3 320"), copy: PickSide(length: 298, take: "the plain song")))
            == ["Yours: MP3 320", "This copy: 4:58, the plain song"])
        #expect(compareLines(PickCompare(yours: PickSide(), copy: nil)).isEmpty)
        #expect(compareLines(nil).isEmpty)
        #expect(compareLines(compare) == ["Yours: FLAC, 7:00, live recording", "This copy: FLAC, 4:58, the plain song"])
    }

    @Test func aCopysTagAndTheTakeLine() {
        let same = FoundCandidate(source: "Soulseek", vsYours: "same", vsYoursText: "Same as yours")
        #expect(vsYoursTag(same) == "Same as yours")
        #expect(vsYoursSpoken(same) == "Compared with your copy: Same as yours")
        #expect(vsYoursTag(FoundCandidate(source: "Soulseek")) == nil)
        #expect(takeLine(" Yours looks like a live recording. Pick the plain song to replace it. ")
            == "Yours looks like a live recording. Pick the plain song to replace it.")
        #expect(takeLine(" ") == nil)
    }
}

private func replacing(plain: Bool = true) -> Replacing {
    Replacing(songId: "nd-1", title: "Holocene", search: "f1", copyKey: "id:c1", plainSong: plain)
}

private func job(_ state: String, at: String = "2026-10-11T12:05:00Z", detail: String? = nil, id: String = "nd-1") -> Upgrade {
    Upgrade(id: id, title: "Holocene", state: state, detail: detail, updatedAt: at)
}

@MainActor @Suite struct ReplacementsTests {
    @Test func aReplacementIsFollowedUntilItIsReplaced() {
        let all = Replacements()
        all.start(replacing(), upgrades: [])
        #expect(all.replacing("nd-1"))
        #expect(all.of(search: "f1", copyKey: "id:c1")?.copyKey == "id:c1")
        #expect(all.update([]).isEmpty)
        #expect(all.update([job("working")]).isEmpty)
        #expect(all.now["nd-1"]?.seen == true)
        let ends = all.update([job("upgraded", detail: "Now FLAC.")])
        #expect(ends.count == 1)
        guard case .replaced(let done) = ends.first else { Issue.record("not replaced"); return }
        #expect(ends.first?.words == "Replaced with the plain song")
        #expect(!all.replacing("nd-1"))
        // Undo puts the old copy back by the song's id.
        #expect(done.songId == "nd-1")
    }

    @Test func aReplacementThatFailsSaysYourCopyWasKeptAndWhereToLook() {
        let all = Replacements()
        all.start(replacing(plain: false), upgrades: [])
        let ends = all.update([job("failed", detail: "The copy you picked was not the right recording, so it was thrown away. Your copy was kept.")])
        guard case .kept = ends.first else { Issue.record("not kept"); return }
        #expect(ends.first?.words == "The copy you picked was not the right recording, so it was thrown away. Your copy was kept. Details are in Downloads.")
        #expect(replacedLine(plainSong: false) == "Replaced with the copy you picked")
        #expect(keptLine(nil) == "The copy you picked did not replace yours. Your copy was kept. Details are in Downloads.")
    }

    @Test func anOlderJobsEndIsNotThisReplacements() {
        let all = Replacements()
        let older = job("upgraded", at: "2026-10-10T12:00:00Z")
        all.start(replacing(), upgrades: [older])
        #expect(all.update([older]).isEmpty)
        #expect(all.replacing("nd-1"))
        #expect(all.update([older, job("failed", id: "nd-2")]).isEmpty)
        let ends = all.update([job("failed", at: "2026-10-11T12:05:00Z", detail: "No copy came. Your copy was kept.")])
        guard case .kept = ends.first else { Issue.record("not kept"); return }
    }

    @Test func theReplacingRowSaysTheStepThePillWould() {
        let r = replacing()
        let row = rowOf(Upgrade(id: "nd-1", title: "Holocene", state: "working", progress: 0.4))
        #expect(replacingRow(r, [row]) == row)
        #expect(replacingWords(row) == "Replacing... Searching")
        #expect(replacingWords(nil) == "Replacing...")
        let downloading = rowOf(download("s:a", "downloading", progress: 0.42), upgrade: Upgrade(id: "nd-1", state: "working"))
        #expect(replacingWords(replacingRow(r, [downloading])) == "Replacing... Downloading 42%")
    }
}

/// Holds back whoever waits on it until it is opened.
private actor Gate {
    private var opened = false
    private var waiting: [CheckedContinuation<Void, Never>] = []

    func wait() async {
        if opened { return }
        await withCheckedContinuation { waiting.append($0) }
    }

    func open() {
        opened = true
        for one in waiting { one.resume() }
        waiting = []
    }
}

/// The server's lists, set by each test.
private final class FakeDownloads: DownloadsSource, @unchecked Sendable {
    private let lock = NSLock()
    private var _list: [Acquisition] = []
    private var _calls = 0
    private var _cleared: [String?] = []
    private var _fail = false
    private var _slowNext: Gate?
    private var _kept: [String] = []
    private var _keepAnswer: @Sendable (String) async throws -> Void = { _ in }

    var list: [Acquisition] {
        get { lock.withLock { _list } }
        set { lock.withLock { _list = newValue } }
    }
    var fail: Bool {
        get { lock.withLock { _fail } }
        set { lock.withLock { _fail = newValue } }
    }
    /// When set, the next list asked for is the list as it is now, answered only once this opens.
    var slowNext: Gate? {
        get { lock.withLock { _slowNext } }
        set { lock.withLock { _slowNext = newValue } }
    }
    var calls: Int { lock.withLock { _calls } }
    var cleared: [String?] { lock.withLock { _cleared } }
    /// Each Keep anyway sent, and what the server does with one.
    var kept: [String] { lock.withLock { _kept } }
    var keepAnswer: @Sendable (String) async throws -> Void {
        get { lock.withLock { _keepAnswer } }
        set { lock.withLock { _keepAnswer = newValue } }
    }

    /// The server keeps it: the list then shows the row in the library.
    func keepsOnTheServer() {
        keepAnswer = { [weak self] key in
            guard let self else { return }
            self.list = self.list.map { row in
                guard row.key == key else { return row }
                var done = row
                done.state = "done"
                done.keepAnyway = false
                return done
            }
        }
    }

    func acquisitions() async throws -> [Acquisition] {
        let (now, gate) = try lock.withLock { () throws -> ([Acquisition], Gate?) in
            _calls += 1
            if _fail { throw OctoError.server(code: 0, message: "down") }
            let gate = _slowNext
            _slowNext = nil
            return (_list, gate)
        }
        if let gate { await gate.wait() }
        return now
    }

    func upgrades() async throws -> [Upgrade] { [] }

    func clear(_ key: String?) async throws {
        lock.withLock {
            _cleared.append(key)
            _list = _list.filter { !($0.stage == .done && (key == nil || $0.key == key)) }
        }
    }

    func keepAnyway(_ key: String) async throws {
        let answer = lock.withLock {
            _kept.append(key)
            return _keepAnswer
        }
        try await answer(key)
    }
}

/// A Find songs source whose search finishes on the second look, and which notes each pick.
private final class FakeFind: FindSource, @unchecked Sendable {
    static let copy = FoundCandidate(source: "Soulseek", index: 0, id: "c1")
    private let lock = NSLock()
    private var _asks = 0
    private var _searches = 0
    private var _picks: [String] = []
    private var _anyways: [Bool?] = []
    private var _answer: @Sendable () -> PickResult = { PickResult(pickQueued, "Getting it.", "soulseek:x") }
    private var _libraryId: String?

    var asks: Int { lock.withLock { _asks } }
    var picks: [String] { lock.withLock { _picks } }
    var anyways: [Bool?] { lock.withLock { _anyways } }
    var answer: @Sendable () -> PickResult {
        get { lock.withLock { _answer } }
        set { lock.withLock { _answer = newValue } }
    }
    var libraryId: String? {
        get { lock.withLock { _libraryId } }
        set { lock.withLock { _libraryId = newValue } }
    }

    func start(_ id: String) async throws -> FoundSongs {
        lock.withLock {
            _searches += 1
            _asks = 0
            return FoundSongs(id: "f\(_searches)", state: "searching", candidate: [Self.copy])
        }
    }

    func get(_ search: String) async throws -> FoundSongs {
        lock.withLock {
            _asks += 1
            return FoundSongs(id: search, state: _asks < 2 ? "searching" : "done", song: FoundSong(title: "Holocene", libraryId: _libraryId),
                              candidate: [Self.copy, FoundCandidate(source: "Lidarr", index: 1, id: "c2")])
        }
    }

    func pick(_ search: String, _ copy: FoundCandidate, anyway: Bool?) async throws -> PickResult {
        let answer = lock.withLock {
            _picks.append(search)
            _anyways.append(anyway)
            return _answer
        }
        return answer()
    }
}

private let instant: Sleeper = { _ in await Task.yield() }

@MainActor @Suite struct DownloadWatchesTests {
    @Test func clearingHidesAtOnceAndAsksTheServer() async {
        let source = FakeDownloads()
        source.list = [download("s:a", "done"), download("s:b", "downloading")]
        let watch = DownloadsWatch(source: source, clock: { now })
        await watch.poll()
        await watch.clear()
        #expect(watch.rows.map(\.key) == ["s:b"])
        #expect(source.cleared == [nil])
        await watch.poll()
        await watch.clear("s:b")
        // A running row is never cleared.
        #expect(watch.rows.map(\.key) == ["s:b"])
    }

    @Test func theWatchSaysWhenItsNewestListWasAskedFor() async {
        let clock = WatchClock()
        clock.now = now
        let source = FakeDownloads()
        source.list = [download("s:a", "downloading")]
        let watch = DownloadsWatch(source: source, clock: { clock.now })
        #expect(watch.listedSince == nil)
        await watch.poll()
        #expect(watch.listedSince == now)
        clock.now = now + 5_000
        source.fail = true
        await watch.poll()
        // A list that did not come back changes nothing.
        #expect(watch.listedSince == now)
        #expect(watch.rows.count == 1)
        watch.reset()
        #expect(watch.listedSince == nil)
        #expect(!watch.loaded)
    }

    @Test func keepAnywaySendsTheKeyThenAsksForTheListAgain() async {
        let source = FakeDownloads()
        source.list = [held("s:a"), download("s:b", "downloading")]
        source.keepsOnTheServer()
        let watch = DownloadsWatch(source: source, clock: { now })
        await watch.poll()
        #expect(source.calls == 1)

        await watch.keepAnyway("s:a")
        #expect(source.kept == ["s:a"])
        // Asked again once it answered: the refreshed row offers it no more.
        #expect(source.calls == 2)
        let row = watch.rows.first { $0.key == "s:a" }
        #expect(row?.phase == .done)
        #expect(row.flatMap(keepAnywayLabel) == nil)
        #expect(watch.keeping.isEmpty)
    }

    @Test func keepAnywayIsSendingUntilTheListCameBackAndASecondTapSendsNothing() async {
        let source = FakeDownloads()
        source.list = [held("s:a")]
        let gate = Gate()
        source.keepAnswer = { _ in await gate.wait() }
        let watch = DownloadsWatch(source: source, clock: { now })
        await watch.poll()

        let first = Task { await watch.keepAnyway("s:a") }
        while source.kept.isEmpty { await Task.yield() }
        #expect(watch.keeping["s:a"] == Keeping(sending: true))
        // A second tap while one is out.
        await watch.keepAnyway("s:a")
        #expect(source.kept == ["s:a"])

        await gate.open()
        await first.value
        #expect(source.calls == 2)
        #expect(watch.keeping["s:a"] == nil)
    }

    @Test func aRefusalShowsTheServersWordsOnTheRow() async {
        let source = FakeDownloads()
        source.list = [held("s:a"), held("s:b")]
        source.keepAnswer = { _ in throw OctoError.notFound("Nothing is waiting to be kept") }
        let watch = DownloadsWatch(source: source, clock: { now })
        await watch.poll()

        await watch.keepAnyway("s:a")
        #expect(watch.keeping["s:a"] == Keeping(error: "Nothing is waiting to be kept"))
        // Only on its own row, and the list is asked again all the same.
        #expect(watch.keeping["s:b"] == nil)
        #expect(source.calls == 2)

        // Still offered after the next look: the words stay.
        await watch.poll()
        #expect(watch.keeping["s:a"]?.error == "Nothing is waiting to be kept")

        // Tapped again and kept: the old words go with the button.
        source.keepsOnTheServer()
        await watch.keepAnyway("s:a")
        #expect(watch.keeping["s:a"] == nil)
    }

    @Test func aRefusalLeavesOnceTheRowNoLongerOffersKeepAnyway() async {
        let source = FakeDownloads()
        source.list = [held("s:a")]
        source.keepAnswer = { _ in throw OctoError.server(code: 10, message: "Name the person whose copy to keep.") }
        let watch = DownloadsWatch(source: source, clock: { now })
        await watch.poll()
        await watch.keepAnyway("s:a")
        #expect(watch.keeping["s:a"]?.error == "Name the person whose copy to keep")

        source.list = [download("s:a", "done")]
        await watch.poll()
        #expect(watch.keeping.isEmpty)
    }

    @Test func aServerOutOfReachSaysSoOnTheRow() async {
        let source = FakeDownloads()
        source.list = [held("s:a")]
        source.keepAnswer = { _ in throw URLError(.cannotConnectToHost) }
        let watch = DownloadsWatch(source: source, clock: { now })
        await watch.poll()
        source.fail = true
        await watch.keepAnyway("s:a")
        #expect(watch.keeping["s:a"]?.error == keepAnywayUnreachable)
        // The row stays as it was.
        #expect(watch.rows.first.flatMap(keepAnywayLabel) != nil)
    }

    @Test func aNewSignInForgetsEveryKeepAnyway() async {
        let source = FakeDownloads()
        source.list = [held("s:a")]
        source.keepAnswer = { _ in throw OctoError.notFound("Nothing is waiting to be kept") }
        let watch = DownloadsWatch(source: source, clock: { now })
        await watch.poll()
        await watch.keepAnyway("s:a")
        #expect(!watch.keeping.isEmpty)
        watch.reset()
        #expect(watch.keeping.isEmpty)
    }

    @Test func keepAnywayReadsAnOpenLogAgainBeforeTheButtonComesBack() async {
        let source = FakeDownloads()
        source.list = [held("s:a")]
        let watch = DownloadsWatch(source: source, clock: { now })
        await watch.poll()
        var also = 0
        await watch.keepAnyway("s:a") {
            also += 1
            // After the list came back, and still sending meanwhile.
            #expect(source.calls == 2)
            #expect(watch.keeping["s:a"] == Keeping(sending: true))
        }
        #expect(also == 1)
        #expect(watch.keeping["s:a"] == nil)
    }

    @Test func aRefusalForADownloadTheListDoesNotShowStaysForItsLog() async {
        let source = FakeDownloads()
        source.list = [held("s:b")]
        source.keepAnswer = { _ in throw OctoError.notFound("Nothing is waiting to be kept") }
        let watch = DownloadsWatch(source: source, clock: { now })
        await watch.poll()
        await watch.keepAnyway("s:x")
        #expect(watch.keeping["s:x"]?.error == "Nothing is waiting to be kept")
        await watch.poll()
        #expect(watch.keeping["s:x"]?.error == "Nothing is waiting to be kept")
    }

    @Test func aLogIsReadAgainAtOnceOnlyWhileItIsTheOneFollowed() async {
        let loads = Counter()
        let state = WatchClock()
        let watch = DownloadLogWatch(load: { key in
            _ = loads.next()
            return download(key, state.now == 0 ? "searching" : "done")
        }, pollMs: 60_000, clock: { now }, sleep: { _ in try? await Task.sleep(nanoseconds: 60_000_000_000) })
        watch.follow("s:a")
        while watch.row == nil { await Task.yield() }
        #expect(loads.value == 1)
        #expect(watch.row?.state == "searching")

        state.now = 1
        await watch.readAgain("s:a")
        #expect(loads.value == 2)
        #expect(watch.row?.state == "done")

        // Another download's log, and a closed one, are not read.
        await watch.readAgain("s:b")
        #expect(loads.value == 2)
        watch.close()
        await watch.readAgain("s:a")
        #expect(loads.value == 2)
    }

    @Test func anOlderAnswerNeverLandsAfterANewerOne() async {
        let source = FakeDownloads()
        source.list = [held("s:a")]
        let gate = Gate()
        source.slowNext = gate
        let watch = DownloadsWatch(source: source, clock: { now })
        // Asked first, answered last, with the list as it was.
        let slow = Task { await watch.poll() }
        while source.calls < 1 { await Task.yield() }
        source.list = [download("s:a", "done")]
        await watch.poll()
        #expect(watch.rows.first?.phase == .done)
        await gate.open()
        await slow.value
        #expect(watch.rows.first?.phase == .done)
    }

    @Test func theSheetIsAskedOftenWhileOpenOrBusyAndSeldomOtherwise() {
        let watch = DownloadsWatch(source: FakeDownloads(), clock: { now })
        #expect(watch.delayMs() == downloadsIdlePollMs)
        watch.open = true
        #expect(watch.delayMs() == downloadsOpenPollMs)
        watch.foreground = false
        #expect(watch.delayMs() == downloadsIdlePollMs)
    }

    @Test func aFinishedLogIsAskedForAMinuteMoreThenNoLonger() async {
        let loads = Counter()
        let watch = DownloadLogWatch(load: { key in
            let n = loads.next()
            var row = download(key, n <= 4 ? "downloading" : "done")
            row.updatedAt = "2026-10-04T17:59:59Z"
            return row
        }, clock: { now }, sleep: instant)
        watch.follow("s:a")
        await watch.settle()
        // Still asked a while for the lyrics line, then no more.
        #expect(loads.value == 4 + Int(logFinishedGraceMs / logPollMs) + 1)
        #expect(!watch.following)
        #expect(watch.row?.state == "done")
    }

    @Test func aLogThatFinishedLongAgoIsReadOnce() async {
        let loads = Counter()
        let watch = DownloadLogWatch(load: { key in
            _ = loads.next()
            var row = download(key, "failed")
            row.updatedAt = "2026-10-04T15:00:00Z"
            return row
        }, clock: { now }, sleep: instant)
        watch.follow("s:a")
        await watch.settle()
        #expect(loads.value == 1)
        #expect(!watch.following)
    }

    @Test func aLogNoLongerOnTheServerSaysSoAndStops() async {
        let watch = DownloadLogWatch(load: { _ in throw OctoError.notFound("gone") }, clock: { now }, sleep: instant)
        watch.follow("s:a")
        await watch.settle()
        #expect(watch.problem == "This download is no longer on the server")
        #expect(!watch.following)
    }

    private func doneWatch(_ source: FakeFind, asksFirst: Bool) async -> FindSongsWatch {
        let watch = FindSongsWatch(source: source, sleep: instant)
        watch.asksFirst = asksFirst
        watch.search("nd-1")
        await watch.settle()
        return watch
    }

    private let question = "This copy is MP3 320 and yours is FLAC, so it sounds worse. Replace yours anyway?"

    @Test func aFindSearchIsFollowedUntilItEndsThenPicked() async {
        let source = FakeFind()
        let watch = await doneWatch(source, asksFirst: false)
        #expect(watch.found?.state == "done")
        #expect(source.asks == 2)
        let picked = await watch.pick("f1", FakeFind.copy)
        #expect(picked.queued)
        #expect(watch.picked?.key == "soulseek:x")
        #expect(source.picks == ["f1"])
    }

    @Test func anOlderServerIsNeverSentAnyway() async {
        let source = FakeFind()
        source.answer = { PickResult(pickSkipped, "Your copy is already FLAC, so nothing would be better.") }
        let watch = await doneWatch(source, asksFirst: false)
        await watch.pick("f1", FakeFind.copy)
        #expect(source.anyways == [nil])
        #expect(watch.question == nil)
        #expect(watch.picked?.detail == "Your copy is already FLAC, so nothing would be better.")
    }

    @Test func aServerThatAsksFirstGetsAnywayFalseAndItsQuestionWaits() async {
        let source = FakeFind()
        let words = question
        source.answer = { PickResult(pickConfirm, words) }
        let watch = await doneWatch(source, asksFirst: true)
        let first = await watch.pick("f1", FakeFind.copy)
        #expect(first.confirm)
        #expect(source.anyways == [false])
        #expect(watch.question == PickQuestion(search: "f1", copy: FakeFind.copy, words: question, title: "Holocene"))
        // A question is not a refusal.
        #expect(watch.picked == nil)
        watch.close()
        #expect(watch.question == nil)
    }

    @Test func replaceAnywaySendsTheSameCopyWithAnywayTrue() async {
        let source = FakeFind()
        let words = question
        source.answer = { PickResult(pickConfirm, words) }
        let watch = await doneWatch(source, asksFirst: true)
        await watch.pick("f1", FakeFind.copy)
        let asked = watch.question!
        source.answer = { PickResult(pickQueued, "Getting it.", "soulseek:x") }
        let result = await watch.replaceAnyway(asked)
        #expect(result?.queued == true)
        #expect(source.picks == ["f1", "f1"])
        #expect(source.anyways == [false, true])
        #expect(watch.question == nil)
        #expect(watch.picked?.key == "soulseek:x")
        // Answered once: a second tap sends nothing.
        #expect(await watch.replaceAnyway(asked) == nil)
        #expect(source.picks.count == 2)
    }

    @Test func keepMineSendsNothing() async {
        let source = FakeFind()
        source.answer = { PickResult(pickConfirm, "") }
        let watch = await doneWatch(source, asksFirst: true)
        await watch.pick("f1", FakeFind.copy)
        #expect(watch.question?.words == replaceAnywayFallback)
        let asked = watch.question!
        watch.keepMine()
        #expect(watch.question == nil)
        #expect(watch.picked == nil)
        #expect(watch.found?.id == "f1")
        #expect(await watch.replaceAnyway(asked) == nil)
        #expect(source.picks.count == 1)
    }

    @Test func aQuestionGoesWithItsSearch() async {
        let source = FakeFind()
        let words = question
        source.answer = { PickResult(pickConfirm, words) }
        let watch = await doneWatch(source, asksFirst: true)
        await watch.pick("f1", FakeFind.copy)
        let asked = watch.question!
        watch.search("nd-1")
        #expect(watch.question == nil)
        #expect(await watch.replaceAnyway(asked) == nil)
        #expect(source.picks.count == 1)
        watch.close()
    }

    @Test func nothingIsPickedWhileTheSearchRuns() async {
        let source = FakeFind()
        let watch = FindSongsWatch(source: source, sleep: { _ in try? await Task.sleep(nanoseconds: 5_000_000_000) })
        watch.search("nd-1")
        while watch.found == nil { await Task.yield() }
        #expect(watch.found?.searching == true)
        #expect(!canPick(watch.found, FakeFind.copy))
        let picked = await watch.pick("f1", FakeFind.copy)
        #expect(!picked.queued)
        #expect(picked.detail == findPickWait)
        #expect(source.picks.isEmpty)
        watch.close()
        #expect(canPick(FoundSongs(id: "f1", state: "done"), FakeFind.copy))
        // A copy the server did not name.
        #expect(!canPick(FoundSongs(id: "f1", state: "done"), FoundCandidate(source: "Soulseek")))
    }

    @Test func aPickFromAReplacedListIsRefused() async {
        let source = FakeFind()
        let watch = await doneWatch(source, asksFirst: false)
        #expect(watch.found?.id == "f1")
        watch.search("nd-1")
        await watch.settle()
        #expect(watch.found?.id == "f2")
        #expect(await watch.pick("f1", FakeFind.copy).detail == findListChanged)
        #expect(source.picks.isEmpty)
    }

    @Test func aSearchIsShownUntilClosedOrReplaced() async {
        let source = FakeFind()
        let watch = await doneWatch(source, asksFirst: false)
        #expect(watch.showing("f1"))
        watch.search("nd-1")
        await watch.settle()
        #expect(!watch.showing("f1"))
        #expect(watch.showing("f2"))
        watch.close()
        #expect(!watch.showing("f2"))
    }

    @Test func aBetterCopyIsAskedTheUsualWayThenSentOnceWithAnywayTrue() async {
        let source = FakeFind()
        source.libraryId = "nd-1"
        let compare = PickCompare(yours: PickSide(quality: "FLAC", length: 420, take: "live recording"),
                                  copy: PickSide(quality: "FLAC", length: 298, take: "the plain song"))
        source.answer = { PickResult(pickReady, nil, nil, compare) }
        let watch = await doneWatch(source, asksFirst: true)
        await watch.pick("f1", FakeFind.copy)
        let asked = watch.question!
        #expect(asked.kind == .ready)
        #expect(asked.words == replaceQuestion(FakeFind.copy))
        #expect(asked.songId == "nd-1")
        #expect(asked.title == "Holocene")
        #expect(source.anyways == [false])
        source.answer = { PickResult(pickQueued, "Getting it.", nil) }
        #expect(await watch.replaceAnyway(asked)?.queued == true)
        #expect(await watch.replaceAnyway(asked) == nil)
        #expect(source.anyways == [false, true])
        #expect(Replacing.of(asked)?.plainSong == true)
    }

    @Test func anOutsideSongIsPickedAtOnceEvenWhenTheServerAsksFirst() async {
        let source = FakeFind()
        let watch = await doneWatch(source, asksFirst: true)
        #expect(await watch.pick("f1", FakeFind.copy).queued)
        #expect(watch.question == nil)
        #expect(source.anyways == [false])
    }
}

private final class WatchClock: @unchecked Sendable {
    private let lock = NSLock()
    private var _now: Int64 = 0
    var now: Int64 {
        get { lock.withLock { _now } }
        set { lock.withLock { _now = newValue } }
    }
}

private final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    var value: Int { lock.withLock { count } }
    func next() -> Int { lock.withLock { count += 1; return count } }
}

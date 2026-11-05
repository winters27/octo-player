import Foundation
import Testing
@testable import OctoKit

/// What a progress callback heard, in order.
private final class PartsHeard: @unchecked Sendable {
    private let lock = NSLock()
    private var parts: [Double] = []

    func add(_ part: Double) {
        lock.lock()
        parts.append(part)
        lock.unlock()
    }

    var all: [Double] {
        lock.lock()
        defer { lock.unlock() }
        return parts
    }
}

/// How far a copy is has one writer, which drops what is late or another's, and Home picks its
/// card from it as the other apps do.
@Suite struct CopyProgressTests {
    private func run(_ step: CopyStep = .read, done: Int = 10, of: Int? = 100, arriving: Bool = true, account: String = "acc",
                     dismissed: Bool = false) -> CopyRunState {
        CopyRunState(token: 7, account: account, server: "Home", arriving: arriving, step: step, done: done, of: of,
                     fraction: 0.1, artwork: nil, songs: 120, albums: 9, dismissed: dismissed)
    }

    @Test func lateReadsAndOtherCopiesAreDropped() {
        var ledger = CopyLedger()
        let token = ledger.begin(account: "acc", server: "Home", arriving: true)
        ledger.read(token, ReadProgress(songs: 500, listed: nil))
        ledger.read(token, ReadProgress(songs: 1_000, listed: 2_000))
        // The album side reports with the count it saw before the last page.
        ledger.read(token, ReadProgress(songs: 500, listed: 2_000))
        #expect(ledger.run?.done == 1_000)
        ledger.building(token)
        ledger.read(token, ReadProgress(songs: 5_000, listed: 2_000))
        #expect(ledger.run?.step == .build)
        ledger.read(token + 1, ReadProgress(songs: 1, listed: nil))
        #expect(ledger.run?.token == token)
    }

    @Test func aWholeReadCountsTheSongsKept() {
        var ledger = CopyLedger()
        let token = ledger.begin(account: "acc", server: "Home", arriving: true)
        ledger.read(token, ReadProgress(songs: 1_040, listed: 1_000))
        ledger.read(token, ReadProgress(songs: 1_000, listed: 1_000, whole: true))
        #expect(ledger.run?.done == 1_000)
    }

    @Test func theFractionMovesOnEveryPageAndNeverBack() {
        var last = -1.0
        for done in stride(from: 500, through: 20_000, by: 500) {
            let fraction = copyFraction(.read, done: done, of: 10_000)!
            #expect(fraction > last)
            #expect(fraction < readShare)
            last = fraction
        }
        #expect(copyFraction(.connect, done: 0, of: nil) == nil)
        #expect(copyFraction(.build, done: 0, of: nil) == readShare)
    }

    /// The system ends a background task that shows no progress, so its progress moves on every page
    /// (before the total is known too), through building and saving, and through the steps after the
    /// copy, never reaching its end before the copy ends.
    @Test func theSystemsTaskMovesThroughEveryPhase() {
        var ledger = CopyLedger()
        let token = ledger.begin(account: "acc", server: "Home", arriving: true)
        var shown = 0.0
        func look() -> Double {
            shown = systemProgress(ledger.run!, shown: shown)
            return shown
        }
        var last = look()
        for done in stride(from: 500, through: 5_000, by: 500) {
            ledger.read(token, ReadProgress(songs: done, listed: nil))
            let now = look()
            #expect(now > last)
            last = now
        }
        ledger.read(token, ReadProgress(songs: 5_500, listed: 200_000))
        #expect(look() > last)
        ledger.building(token)
        last = look()
        for part in [0.15, 0.3, 0.5, 0.8, 1.0] {
            ledger.built(token, part)
            let now = look()
            #expect(now > last)
            last = now
        }
        ledger.swapped(token)
        #expect(look() >= last)
        last = look()
        for part in [1.0 / 6, 2.0 / 6, 3.0 / 6, 4.0 / 6, 5.0 / 6] {
            ledger.settling(token, part)
            let now = look()
            #expect(now > last)
            last = now
        }
        #expect(last < 1)
    }

    /// The longest parts of a big copy, the in-memory build and the merge, say how far they are as
    /// they go, always forward.
    @Test func theBuildAndTheMergeSayHowFarTheyAre() {
        let albums = (0..<50).map { ServerAlbum(id: "al\($0)", name: "Album \($0)", artist: "Artist", artistId: "ar1") }
        let songs = (0..<10_000).map { ServerSong(id: "s\($0)", title: "Song \($0)", albumId: "al\($0 % 50)", track: $0 / 50 + 1, duration: 200) }
        let heard = PartsHeard()
        let catalog = buildServerCatalog("server:h", ServerLibrary(songs, albums, [])) { heard.add($0) }
        let built = heard.all
        #expect(built.count >= 4)
        #expect(built == built.sorted())
        #expect(built.allSatisfy { $0 < 1 })
        let merging = PartsHeard()
        _ = mergeCatalogs([SourceCatalog(sourceId: "server:h", onPhone: false, tracks: catalog.tracks, albums: catalog.albums, artists: catalog.artists)]) {
            merging.add($0)
        }
        #expect(merging.all.count >= 4)
        #expect(merging.all == merging.all.sorted())
    }

    @Test func aFirstCopySaysItsMusicIsHere() {
        var ledger = CopyLedger()
        let token = ledger.begin(account: "acc", server: "Home", arriving: true)
        ledger.staged(token, songs: 1_204, albums: 96)
        ledger.swapped(token)
        ledger.done(token)
        #expect(ledger.outcome == .arrived(token: token, account: "acc", server: "Home", songs: 1_204, albums: 96, stepsProblem: nil))
    }

    @Test func aFailureAfterTheSwapNeverSaysTheCopyFailed() {
        var ledger = CopyLedger()
        let token = ledger.begin(account: "acc", server: "Home", arriving: true)
        ledger.swapped(token)
        ledger.failed(token, "Couldn't reach the server.")
        #expect(ledger.outcome == .arrived(token: token, account: "acc", server: "Home", songs: 0, albums: 0, stepsProblem: "Couldn't reach the server."))
    }

    @Test func aFailureBeforeTheSwapSaysWhereItStopped() {
        var ledger = CopyLedger()
        let token = ledger.begin(account: "acc", server: "Home", arriving: true)
        ledger.read(token, ReadProgress(songs: 48_210, listed: 200_000))
        ledger.failed(token, CopyWords.cutOff)
        #expect(ledger.outcome == .failed(token: token, account: "acc", server: "Home", reason: CopyWords.cutOff, step: .read, done: 48_210))
    }

    @Test func aCopyAgainSaysNothingAndADismissedCardStaysAway() {
        var again = CopyLedger()
        let token = again.begin(account: "acc", server: "Home", arriving: false)
        again.swapped(token)
        again.done(token)
        #expect(again.outcome == nil)
        var first = CopyLedger()
        let one = first.begin(account: "acc", server: "Home", arriving: true)
        first.swapped(one)
        first.dismiss(one)
        first.done(one)
        #expect(first.outcome == nil)
        first.end(one)
        #expect(first.run == nil)
    }

    @Test func wordsMatchTheOtherApps() {
        #expect(CopyWords.count(run(done: 48_210, of: 200_000)) == "48,210 of 200,000")
        #expect(CopyWords.count(run(done: 1_234_567, of: nil)) == "1,234,567 so far")
        #expect(CopyWords.description(run(done: 195_000, of: 190_000)) == "Reading your songs, 195,000 of 195,000")
        #expect(CopyWords.doneText(songs: 1, albums: 1, server: "Home") == "1 song and 1 album from Home.")
        #expect(CopyWords.status(run()) == "Syncing, 10%")
        #expect(CopyWords.status(run(.settle)) == "Syncing")
        #expect(CopyWords.stoppedAt(.read, done: 48_210) == "Stopped after reading 48,210 songs.")
        let all = [CopyWords.title(arriving: true), CopyWords.title(arriving: false), CopyWords.heroText("Home"), CopyWords.doneTitle,
                   CopyWords.failedTitle, CopyWords.onItsWay, CopyWords.likesOnItsWay, CopyWords.noticeAsk, CopyWords.cutOff]
            + CopyStep.allCases.flatMap { [CopyWords.stepLabel($0, server: "Home"), CopyWords.doing($0, server: "Home")] }
        for text in all {
            #expect(!text.contains("\u{2014}") && !text.contains("\u{2013}"))
            #expect(!text.lowercased().contains("preview"))
        }
    }

    @Test func homeShowsAFirstCopyThenItsDoneCardAndNeverACopyAgain() {
        #expect(homeCopyCard(awaiting: nil, phoneMusic: false, run: run(), outcome: nil, account: "acc", server: "Home") == nil)
        #expect(homeCopyCard(awaiting: true, phoneMusic: false, run: run(), outcome: nil, account: "acc", server: "Home")
            == .coming(run: run(), server: "Home", big: true))
        #expect(homeCopyCard(awaiting: true, phoneMusic: true, run: nil, outcome: nil, account: "acc", server: "Home")
            == .coming(run: nil, server: "Home", big: false))
        #expect(homeCopyCard(awaiting: false, phoneMusic: false, run: run(.settle), outcome: nil, account: "acc", server: "Home")
            == .here(token: 7, songs: 120, albums: 9, server: "Home", settling: true, stepsProblem: nil))
        #expect(homeCopyCard(awaiting: false, phoneMusic: false, run: run(.settle, dismissed: true), outcome: nil, account: "acc", server: "Home") == nil)
        #expect(homeCopyCard(awaiting: false, phoneMusic: false, run: run(arriving: false), outcome: nil, account: "acc", server: "Home") == nil)
        let failed = CopyOutcome.failed(token: 5, account: "acc", server: "Home", reason: "No.", step: .read, done: 3)
        #expect(homeCopyCard(awaiting: true, phoneMusic: false, run: nil, outcome: failed, account: "acc", server: "Home")
            == .stopped(reason: "No.", step: .read, done: 3, big: true))
        #expect(homeCopyCard(awaiting: false, phoneMusic: false, run: nil, outcome: failed, account: "acc", server: "Home") == nil)
        #expect(homeCopyCard(awaiting: true, phoneMusic: false, run: run(account: "other"), outcome: nil, account: "acc", server: "Home")
            == .coming(run: nil, server: "Home", big: true))
    }
}

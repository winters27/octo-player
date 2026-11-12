import Foundation

/// How a copy of a server's library goes on the iPhone, in order: reaching the server, reading
/// its songs, building the library from them (in memory, then saved in one go), and the steps
/// after a copy (likes, playlists, downloads).
public enum CopyStep: Int, Comparable, Sendable, CaseIterable {
    case connect, read, build, settle

    public static func < (a: CopyStep, b: CopyStep) -> Bool { a.rawValue < b.rawValue }
}

/// The steps the app shows, in order.
public let copySteps: [CopyStep] = [.connect, .read, .build]

/// Where a shown step stands while the copy is at another.
public enum StepState: Sendable { case done, now, later }

public func stepState(_ shown: CopyStep, at: CopyStep) -> StepState {
    shown == at ? .now : (shown < at ? .done : .later)
}

/// How far one copy is. `token` is the copy's own. `arriving`: no whole copy of this address was
/// ever taken in, so none of its music is here yet. `done` of `of` count songs while reading,
/// `of` nil while unknown. `fraction` is of the whole copy, nil while it cannot be told.
/// `artwork` is the cover of a song that just came. `songs` and `albums` are the copy's counts
/// once it is built. `dismissed`: the done card was put away while the steps after it ran.
public struct CopyRunState: Equatable, Sendable {
    public var token: Int
    public var account: String
    public var server: String
    public var arriving: Bool
    public var step: CopyStep
    public var done: Int
    public var of: Int?
    public var fraction: Double?
    public var artwork: String?
    public var songs: Int
    public var albums: Int
    public var dismissed: Bool

    /// The total as shown: never below what is done.
    public var shownOf: Int? { of.map { max($0, done) } }

    /// The copy is in the library; only the steps after it run.
    public var swapped: Bool { step == .settle }
}

/// How the last first copy of a server ended, for the card that says so.
public enum CopyOutcome: Equatable, Sendable {
    /// The music is in the library. `stepsProblem` says why a step after the copy failed.
    case arrived(token: Int, account: String, server: String, songs: Int, albums: Int, stepsProblem: String?)
    /// The copy stopped before its music was in: why, and where.
    case failed(token: Int, account: String, server: String, reason: String, step: CopyStep, done: Int)

    public var token: Int {
        switch self {
        case let .arrived(token, _, _, _, _, _), let .failed(token, _, _, _, _, _): token
        }
    }

    public var account: String {
        switch self {
        case let .arrived(_, account, _, _, _, _), let .failed(_, account, _, _, _, _): account
        }
    }
}

/// How much of a copy the reading takes; building and saving the library go on to `savedShare`,
/// and the steps after the copy take the rest.
public let readShare = 0.9
public let savedShare = 0.97

/// How far a copy is at `done` of `of` in `step`, or nil while it cannot be told: reading leaves
/// room for one more page, so it moves on every page and reaches its end only when the read ends.
public func copyFraction(_ step: CopyStep, done: Int, of: Int?) -> Double? {
    switch step {
    case .connect: nil
    case .read: of.map { readShare * Double(done) / Double(max($0, done + libraryPage)) }
    case .build: readShare
    case .settle: savedShare
    }
}

/// How far the system's own task shows a copy: its fraction, and while reading before the total is
/// known, a small amount for every song read, so it moves on every page (the system ends a task that
/// shows no progress). Never below `shown`, what it showed last.
public func systemProgress(_ run: CopyRunState, shown: Double) -> Double {
    let now = run.fraction ?? (run.step == .read ? readShare * Double(run.done) / Double(run.done + 1_000_000) : 0.0001)
    return max(shown, min(now, 0.999))
}

/// How far a read of the whole library is: songs read so far (a repeat counts again until the
/// read is `whole`, when the count is of the songs kept), the songs the albums hold once every
/// album is listed (nil before), and now and then the cover of a song that came.
public struct ReadProgress: Equatable, Sendable {
    public var songs: Int
    public var listed: Int?
    public var cover: String?
    public var whole: Bool

    public init(songs: Int, listed: Int?, cover: String? = nil, whole: Bool = false) {
        self.songs = songs
        self.listed = listed
        self.cover = cover
        self.whole = whole
    }
}

/// The one writer of how far the copy running is (`run`) and how the last first copy ended
/// (`outcome`). An update for another copy, for a step before the one shown, or after the copy
/// ended is dropped, so a copy that was stopped and replaced never writes over its replacement.
/// The count and the fraction only go forward within a step, until the read is whole.
public struct CopyLedger: Equatable, Sendable {
    public private(set) var run: CopyRunState?
    public private(set) var outcome: CopyOutcome?
    private var tokens = 0

    public init() {}

    /// A copy starts, taking the place of any other. Answers its token.
    public mutating func begin(account: String, server: String, arriving: Bool) -> Int {
        tokens += 1
        run = CopyRunState(token: tokens, account: account, server: server, arriving: arriving, step: .connect, done: 0,
                           of: nil, fraction: nil, artwork: nil, songs: 0, albums: 0, dismissed: false)
        // A first copy starting makes the last one's card old news.
        if arriving { outcome = nil }
        return tokens
    }

    public mutating func read(_ token: Int, _ progress: ReadProgress) {
        guard var shown = run, shown.token == token, shown.step <= .read else { return }
        let same = shown.step == .read
        shown.done = progress.whole || !same ? progress.songs : max(shown.done, progress.songs)
        shown.of = progress.listed ?? shown.of
        shown.step = .read
        if let fraction = copyFraction(.read, done: shown.done, of: shown.of) { shown.fraction = max(shown.fraction ?? 0, fraction) }
        if let cover = progress.cover { shown.artwork = cover }
        run = shown
    }

    public mutating func building(_ token: Int) { built(token, 0) }

    /// Building and saving the library are `part` (0 to 1) done.
    public mutating func built(_ token: Int, _ part: Double) {
        guard var shown = run, shown.token == token, shown.step <= .build else { return }
        shown.step = .build
        shown.fraction = max(shown.fraction ?? 0, readShare + (savedShare - readShare) * min(max(part, 0), 1))
        run = shown
    }

    /// The steps after the copy are `part` (0 to 1) done.
    public mutating func settling(_ token: Int, _ part: Double) {
        guard var shown = run, shown.token == token, shown.swapped else { return }
        shown.fraction = max(shown.fraction ?? 0, savedShare + (1 - savedShare) * min(max(part, 0), 1))
        run = shown
    }

    /// The copy is built: its songs and albums.
    public mutating func staged(_ token: Int, songs: Int, albums: Int) {
        guard var shown = run, shown.token == token else { return }
        shown.songs = songs
        shown.albums = albums
        run = shown
    }

    /// The copy is in the library; the steps after it begin.
    public mutating func swapped(_ token: Int) {
        guard var shown = run, shown.token == token else { return }
        shown.step = .settle
        shown.fraction = max(shown.fraction ?? 0, savedShare)
        run = shown
    }

    /// The copy's last step ended: a first copy says its music is here.
    public mutating func done(_ token: Int) {
        guard let shown = run, shown.token == token, shown.arriving, shown.swapped, !shown.dismissed else { return }
        outcome = .arrived(token: token, account: shown.account, server: shown.server, songs: shown.songs, albums: shown.albums, stepsProblem: nil)
    }

    /// The copy failed with `reason`. A first copy says so; once its music is in, only that a step
    /// after it failed.
    public mutating func failed(_ token: Int, _ reason: String) {
        guard let shown = run, shown.token == token, shown.arriving else { return }
        if shown.swapped {
            guard !shown.dismissed else { return }
            outcome = .arrived(token: token, account: shown.account, server: shown.server, songs: shown.songs, albums: shown.albums,
                               stepsProblem: reason)
        } else {
            outcome = .failed(token: token, account: shown.account, server: shown.server, reason: reason, step: shown.step, done: shown.done)
        }
    }

    /// The copy ended, however it ended.
    public mutating func end(_ token: Int) {
        if run?.token == token { run = nil }
    }

    /// The done card was put away.
    public mutating func dismiss(_ token: Int) {
        if run?.token == token { run?.dismissed = true }
        clearArrived(token)
    }

    public mutating func clearArrived(_ token: Int) {
        if case let .arrived(arrived, _, _, _, _, _) = outcome, arrived == token { outcome = nil }
    }

    /// Try again was tapped, or the server in use changed.
    public mutating func clearOutcome() { outcome = nil }
}

// MARK: Words

/// The words the iPhone says about a copy, the same as the other apps'.
public enum CopyWords {
    public static func title(arriving: Bool) -> String { arriving ? "Bringing in your music" : "Updating your library" }
    public static func heroText(_ server: String) -> String { "From \(server). Keep using Octo while it works." }
    public static let savingTitle = "Almost done"
    public static let savingText = "Saving your library. Your music shows up in a moment."
    public static let doneTitle = "Your music is here"
    public static let failedTitle = "Couldn't bring in your music"
    public static let onItsWay = "Your music is on its way"
    public static let likesOnItsWay = "Your likes arrive with your music"
    public static let noticeAsk = "Get a notification while it copies"
    public static let cutOff = "Stopped before it finished"

    public static func stepLabel(_ step: CopyStep, server: String) -> String {
        switch step {
        case .connect: "Connect to \(server)"
        case .read: "Read your songs"
        case .build: "Build your library"
        case .settle: "Sync likes and playlists"
        }
    }

    public static func doing(_ step: CopyStep, server: String) -> String {
        switch step {
        case .connect: "Connecting to \(server)"
        case .read: "Reading your songs"
        case .build: "Building your library"
        case .settle: "Syncing likes and playlists"
        }
    }

    /// "48,210 of 200,000" once the total is known, "48,210 so far" before; nil for the other steps.
    public static func count(_ run: CopyRunState) -> String? {
        guard run.step == .read else { return nil }
        if let of = run.shownOf { return "\(grouped(run.done)) of \(grouped(of))" }
        return "\(grouped(run.done)) so far"
    }

    /// The copy in one line, for VoiceOver, the system's own progress and the car.
    public static func description(_ run: CopyRunState) -> String {
        let doing = doing(run.step, server: run.server)
        return count(run).map { "\(doing), \($0)" } ?? doing
    }

    public static func doneText(songs: Int, albums: Int, server: String) -> String {
        "\(counted(songs, "song")) and \(counted(albums, "album")) from \(server)."
    }

    /// Where a failed copy stopped, or nil when the reason says it all.
    public static func stoppedAt(_ step: CopyStep, done: Int) -> String? {
        switch step {
        case .read: done > 0 ? "Stopped after reading \(counted(done, "song"))." : nil
        case .build: "Stopped while building your library."
        case .connect, .settle: nil
        }
    }

    /// The settings line while a copy runs: "Syncing, 15%" while it can tell, "Syncing" otherwise.
    public static func status(_ run: CopyRunState) -> String {
        guard run.step != .settle, let fraction = run.fraction else { return "Syncing" }
        return "Syncing, \(percent(fraction))%"
    }

    /// A fraction as a whole percent, rounded down.
    public static func percent(_ fraction: Double) -> Int { min(100, Int(min(max(fraction, 0), 1) * 100 + 0.0001)) }

    static func grouped(_ n: Int) -> String {
        let digits = String(n)
        var out = ""
        for (i, c) in digits.enumerated() {
            if i > 0 && (digits.count - i) % 3 == 0 { out.append(",") }
            out.append(c)
        }
        return out
    }

    static func counted(_ n: Int, _ one: String) -> String { "\(grouped(n)) \(n == 1 ? one : one + "s")" }
}

// MARK: Home

/// The card in Home's copy slot.
public enum HomeCopyCard: Equatable, Sendable {
    /// A first copy of the server's music on its way, `run` nil while none runs yet. `big` with no
    /// music of the phone's own, where it fills Home; a banner above the shelves otherwise.
    case coming(run: CopyRunState?, server: String, big: Bool)
    /// The music arrived. `settling` while the steps after the copy still run.
    case here(token: Int, songs: Int, albums: Int, server: String, settling: Bool, stepsProblem: String?)
    /// The first copy stopped before its music arrived.
    case stopped(reason: String, step: CopyStep, done: Int, big: Bool)
}

/// Which copy card Home shows for the account in use, as on Android: a first copy while its music
/// is still to come (`awaiting`, nil until read), then the done card unless put away; a stopped
/// first copy while the music is still to come; a finished one's done card; with music to come
/// and no copy yet, the card drawn still. A copy again never shows here.
public func homeCopyCard(awaiting: Bool?, phoneMusic: Bool, run: CopyRunState?, outcome: CopyOutcome?, account: String?,
                         server: String) -> HomeCopyCard? {
    guard let awaiting, let account else { return nil }
    let big = !phoneMusic
    if let first = run, first.account == account, first.arriving {
        if first.swapped || !awaiting {
            return first.dismissed ? nil : .here(token: first.token, songs: first.songs, albums: first.albums, server: first.server,
                                                 settling: true, stepsProblem: nil)
        }
        return .coming(run: first, server: first.server, big: big)
    }
    if let outcome, outcome.account == account {
        switch outcome {
        case let .failed(_, _, _, reason, step, done):
            if awaiting { return .stopped(reason: reason, step: step, done: done, big: big) }
        case let .arrived(token, _, server, songs, albums, problem):
            return .here(token: token, songs: songs, albums: albums, server: server, settling: false, stepsProblem: problem)
        }
    }
    return awaiting ? .coming(run: nil, server: server, big: big) : nil
}

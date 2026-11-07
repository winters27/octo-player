import Foundation

// The servers kept on the phone, several at once with one in use: what each is called, the
// quiet words in its row, the notice after a switch, and the words about a server: what it
// is, what it offers, how quickly it answers and when it last read its folders.

/// How a kept server answered a quick look: whether it is there, and how long it took.
public struct ServerCheck: Sendable, Equatable {
    public var reach: Reach
    public var ms: Int64?

    public init(_ reach: Reach, _ ms: Int64? = nil) {
        self.reach = reach
        self.ms = ms
    }

    /// Whether a row's status line is a warning.
    public var isWarning: Bool {
        switch reach {
        case .unreachable, .wrongPassword, .signInPage, .needsHttps: true
        case .answers, .unknown: false
        }
    }

    /// Whether the row shows its status line as a warning: the server in use only when it needs
    /// https or its sign-in page turns it away, since its row shows nothing else of how it answered.
    public func warns(inUse: Bool) -> Bool { inUse ? reach == .needsHttps || reach.isSignInPage : isWarning }
}

public enum Reach: Sendable, Equatable {
    case answers
    /// It is there, but did not take the saved password.
    case wrongPassword
    case unreachable
    /// Signed out, so not asked.
    case unknown
    /// A sign-in page in front of the server answered in its place. `loginSent`: the saved
    /// sign-in for it went along and it said no.
    case signInPage(Gateway, loginSent: Bool)
    /// Kept with a sign-in page's password for a plain http address outside the home network,
    /// where the app never sends it.
    case needsHttps

    /// Whether a sign-in page answered in the server's place.
    public var isSignInPage: Bool {
        if case .signInPage = self { true } else { false }
    }
}

/// How a kept server's quick look ended, from the error it ended with: a refused password, a
/// sign-in page in front of it, or out of reach. Never a sign-out.
public func reachOf(_ error: Error) -> Reach {
    let failure = SubsonicError(error)
    if let gateway = failure.gateway { return .signInPage(gateway, loginSent: failure.loginSent) }
    if case .wrongCredentials = failure { return .wrongPassword }
    return .unreachable
}

/// What app start does with the server that was in use.
public enum ServerRestore: Sendable, Equatable {
    /// It signs in again with its saved secret.
    case signIn
    /// It was signed out of: none is in use.
    case leave
    /// Its secret can no longer be read: it stays in the list signed out, and none is in use.
    case signOut
    /// It keeps a sign-in page's password for a plain http address outside the home network,
    /// which the app never sends: none is in use, and its sign-in form opens asking for https.
    case needsHttps
}

/// What app start does with the server that was in use, from whether it was signed out of,
/// whether its secret can be read, and its saved headers in its scope.
public func serverRestore(signedOut: Bool, hasSecret: Bool, headers: [String: String], scope: ServerScope) -> ServerRestore {
    if signedOut { return .leave }
    if !hasSecret { return .signOut }
    if needsHttps(headers, scope: scope) { return .needsHttps }
    return .signIn
}

/// What a server is called: the name the listener gave it, else its host.
public func serverName(label: String, address: String) -> String {
    let trimmed = label.trimmingCharacters(in: .whitespacesAndNewlines)
    if !trimmed.isEmpty { return trimmed }
    if let host = ServerAddress.httpURL(address)?.host { return host }
    return address.hasSuffix("/") ? String(address.dropLast()) : address
}

/// Who is signed in where, for a server's row: "winters at music.example.com".
public func accountLine(username: String, address: String) -> String {
    var whereText = address.range(of: "://").map { String(address[$0.upperBound...]) } ?? address
    if whereText.hasSuffix("/") { whereText.removeLast() }
    return username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? whereText : "\(username) at \(whereText)"
}

/// A server's quiet status in its row: what it is, and how it answered. The one in use shows
/// only its kind here, unless it needs https for its sign-in page or that page turns it away.
public func statusLine(serverType: String?, serverVersion: String?, signedOut: Bool, check: ServerCheck?,
                       switching: Bool = false, inUse: Bool = false) -> String {
    let kind = serverKind(serverType, serverVersion)
    if switching { return "Switching to it now" }
    if signedOut { return "Signed out. Sign in again to use it." }
    guard let check, check.reach != .unknown, !inUse || check.reach == .needsHttps || check.reach.isSignInPage else { return kind }
    switch check.reach {
    case .answers: return ([kind] + [check.ms.map(answerWords)].compactMap { $0 }).joined(separator: " · ")
    case .wrongPassword: return "\(kind) · Didn't take the saved password"
    case .signInPage(let gateway, let loginSent): return "\(kind) · \(GatewayWords.row(gateway, loginSent: loginSent))"
    case .needsHttps: return "\(kind) · \(GatewayWords.rowNeedsHttps)"
    default: return "\(kind) · Out of reach right now"
    }
}

/// What a sign-in form's keep builds on.
public enum KeepBase: Sendable, Equatable {
    /// A server not kept yet.
    case new
    /// This kept server: the one the form was opened for (an edit, or signing in to it again),
    /// whatever its address is now; for any other form, the kept one with the same address and
    /// username.
    case kept(StoredServer)
    /// The server the form was opened for is no longer kept.
    case gone
    /// Another kept server already has this address and username.
    case alreadyKept(StoredServer)
}

/// What a keep builds on. `formFor` is the id of the kept server the form was opened for, nil for
/// a new sign-in or an add.
public func keepBase(formFor id: String?, address: URL, username: String, servers: [StoredServer]) -> KeepBase {
    var base: StoredServer?
    if let id {
        guard let found = servers.first(where: { $0.id == id }) else { return .gone }
        base = found
    }
    let twin = servers.first { $0.address == address && $0.username == username && $0.id != base?.id }
    if base != nil, let twin { return .alreadyKept(twin) }
    if let before = base ?? twin { return .kept(before) }
    return .new
}

/// The id a kept server takes: the one it was kept under while its username stays, else a new
/// one for the other account.
public func keptServerId(before: StoredServer?, username: String, address: URL, taken: Set<String>) -> String {
    if let before, before.username == username { return before.id }
    return freshServerId(username: username, address: ServerAddress.written(address), taken: taken)
}

/// The kept servers after a keep: the one it built on replaced where it was, else the new one
/// added at the end.
public func serversAfterKeep(_ servers: [StoredServer], before: StoredServer?, now server: StoredServer) -> [StoredServer] {
    var out = servers
    if let before, let index = out.firstIndex(where: { $0.id == before.id }) { out[index] = server } else { out.append(server) }
    return out
}

/// An id for a newly kept server: the account's key, unless another kept server has it already.
public func freshServerId(username: String, address: String, taken: Set<String>) -> String {
    let first = serverAccountKey(username, address)
    var n = 1
    while true {
        let id = n == 1 ? first : "\(first)-\(n)"
        if !taken.contains(id) { return id }
        n += 1
    }
}

/// Its kind and version: "Octo 0.9.3", "Navidrome 0.58.0", or "Subsonic server" when it does not say.
public func serverKind(_ type: String?, _ version: String?) -> String {
    var parts: [String] = []
    if let type, !type.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        parts.append(type.prefix(1).uppercased() + type.dropFirst())
    }
    if let version, !version.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { parts.append(version) }
    return parts.isEmpty ? "Subsonic server" : parts.joined(separator: " ")
}

/// What it brings beyond the music, or nil when nothing.
public func serverOffers(lyrics: Bool, adds: Bool) -> String? {
    let offers = [lyrics ? "synced lyrics" : nil, adds ? "adding songs you find online to your library" : nil].compactMap { $0 }
    return offers.isEmpty ? nil : "Offers \(offers.joined(separator: " and "))."
}

/// What the server offers beyond the music, from the extensions it lists.
public func offersOf(_ extensions: [String]) -> String? {
    serverOffers(lyrics: extensions.contains { $0.hasPrefix("songLyrics:") || $0.hasPrefix("octoLyrics:") },
                 adds: extensions.contains { $0.hasPrefix("octoAcquisitions:") })
}

/// How much it holds: "1,204 songs, 96 albums, 41 artists and 12 playlists". A count not known
/// yet is left out.
public func libraryCounts(songs: Int?, albums: Int?, artists: Int?, playlists: Int?) -> String? {
    func counted(_ n: Int, _ one: String) -> String { "\(SettingsSummaries.grouped(Int(n))) \(n == 1 ? one : one + "s")" }
    let parts = [songs.map { counted($0, "song") }, albums.map { counted($0, "album") },
                 artists.map { counted($0, "artist") }, playlists.map { counted($0, "playlist") }].compactMap { $0 }
    switch parts.count {
    case 0: return nil
    case 1: return parts[0]
    default: return parts.dropLast().joined(separator: ", ") + " and " + parts[parts.count - 1]
    }
}

/// How quickly it answered: "Answered in 42 ms", in seconds past one.
public func answerWords(_ ms: Int64) -> String {
    if ms < 1_000 { return "Answered in \(ms) ms" }
    return "Answered in \(String(format: "%.1f", locale: serverWordsLocale, Double(ms) / 1_000)) s"
}

/// Whether the server is reading its folders now and how far it is, or when it last did. Nil
/// when it says neither.
public func scanWords(_ status: ServerScanStatus?, nowMs: Int64, zone: TimeZone = .current) -> String? {
    guard let status else { return nil }
    if status.scanning {
        return status.count > 0 ? "Reading its folders now, \(SettingsSummaries.grouped(Int(status.count))) files so far." : "Reading its folders now."
    }
    // Only an instant or a time with its offset can be read; a time with no zone cannot.
    guard let last = serverTime(status.lastScan).map(Int64.init) else { return nil }
    return "Last read its folders \(agoWords(thenMs: last, nowMs: nowMs, zone: zone))."
}

/// A time before now in words: "just now", "5 minutes ago", "yesterday", "on 12 September",
/// with the year when it was another year.
public func agoWords(thenMs: Int64, nowMs: Int64, zone: TimeZone = .current) -> String {
    let minutes = max(0, nowMs - thenMs) / 60_000
    let hours = minutes / 60
    let days = hours / 24
    if minutes < 1 { return "just now" }
    if minutes < 60 { return minutes == 1 ? "a minute ago" : "\(minutes) minutes ago" }
    if hours < 24 { return hours == 1 ? "an hour ago" : "\(hours) hours ago" }
    if days < 2 { return "yesterday" }
    if days < 7 { return "\(days) days ago" }
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = zone
    let then = Date(timeIntervalSince1970: Double(thenMs) / 1_000)
    let now = Date(timeIntervalSince1970: Double(nowMs) / 1_000)
    let sameYear = calendar.component(.year, from: then) == calendar.component(.year, from: now)
    let formatter = DateFormatter()
    formatter.locale = serverWordsLocale
    formatter.timeZone = zone
    formatter.dateFormat = sameYear ? "d MMMM" : "d MMMM yyyy"
    return "on " + formatter.string(from: then)
}

/// The kept servers in one shape, with the one in use. `legacy` is the one server an older
/// version of an app keeps, and it has the last word, since that version writes only it: a store
/// from before the list becomes a list of one, a server an older version signed in to since is
/// added (or brought up to date) and made the one in use, and one it signed out of leaves none in
/// use. `id` gives a server's id, `same` tells whether two are the same account, and `adopt`
/// makes the legacy server into the kept one, with the id and name of the one it matched, if any.
public func settleServers<T>(_ listed: [T], legacy: T?, id: (T) -> String, same: (T, T) -> Bool,
                             adopt: (_ legacy: T, _ match: T?) -> T) -> (list: [T], inUse: T?) {
    var seen = Set<String>()
    let list = listed.filter { seen.insert(id($0)).inserted }
    guard let legacy else { return (list, nil) }
    let match = list.first { id($0) == id(legacy) || same($0, legacy) }
    let kept = adopt(legacy, match)
    guard let match else { return (list + [kept], kept) }
    return (list.map { id($0) == id(match) ? kept : $0 }, kept)
}

/// Home only when a home address is set, the device is on a local network (Wi-Fi or a cable),
/// and the home address answered just now.
public func choosePlace(hasHome: Bool, onLocalNetwork: Bool, homeAnswered: Bool) -> ServerPlace {
    hasHome && onLocalNetwork && homeAnswered ? .home : .away
}

let serverWordsLocale = Locale(identifier: "en_US")


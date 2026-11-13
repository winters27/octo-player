import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// A sign-in page's own session cookies: Authentik's, Authelia's and Cloudflare Access's.
/// Octo's own (its admin and family pages') are not among them, so keeping these never changes
/// how Octo sees the app.
public func isGatewayCookie(_ name: String) -> Bool {
    name.hasPrefix("authentik_proxy") || name == "authelia_session" || name == "CF_Authorization"
}

/// The session cookies a sign-in page in front of the server hands the app, for one client
/// configuration only: in memory, never on disk, gone with the configuration, the same rules as
/// the other apps (domain, host-only, path and Secure matching, Set-Cookie read in order, expiry
/// by this store's own clock). At most 16; the one that expires first makes room.
public final class ServerCookies: @unchecked Sendable, CustomStringConvertible {
    struct Kept {
        let name: String
        let value: String
        let domain: String
        let path: String
        let hostOnly: Bool
        let secure: Bool
        let expires: Date
    }

    static let most = 16

    /// This store's own id, never another store's: what tells one client configuration's store
    /// from the next one's. Copies of a client share the store, and so the id.
    public let id = UUID()

    private let now: @Sendable () -> Date
    private let lock = NSLock()
    private var kept: [Kept] = []

    public init(now: @escaping @Sendable () -> Date = { Date() }) {
        self.now = now
    }

    /// Takes the gateway cookies from an answer's Set-Cookie values, in the order they came. One
    /// that has expired removes the cookie of the same name, domain and path.
    public func keep(_ setCookies: [String], from url: URL) {
        let time = now()
        lock.withLock {
            for line in setCookies {
                guard let cookie = Self.parse(line, from: url, now: time), isGatewayCookie(cookie.name) else { continue }
                kept.removeAll { $0.name == cookie.name && $0.domain == cookie.domain && $0.path == cookie.path }
                if cookie.expires > time { kept.append(cookie) }
            }
            while kept.count > Self.most, let earliest = kept.indices.min(by: { kept[$0].expires < kept[$1].expires }) {
                kept.remove(at: earliest)
            }
        }
    }

    /// "a=1; b=2" for the cookies this address may have that have not expired, or nil when there
    /// are none.
    public func cookieHeader(for url: URL) -> String? {
        let time = now()
        return lock.withLock {
            kept.removeAll { $0.expires <= time }
            let sent = kept.filter { Self.matches($0, url) }
            return sent.isEmpty ? nil : sent.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
        }
    }

    public var isEmpty: Bool {
        let time = now()
        return lock.withLock { !kept.contains { $0.expires > time } }
    }

    /// Cookie values are secrets, so only how many are kept is ever printed.
    public var description: String { "ServerCookies(\(lock.withLock { kept.count }) kept)" }

    static func parse(_ line: String, from url: URL, now: Date) -> Kept? {
        guard let host = url.host?.lowercased(), !host.isEmpty else { return nil }
        let parts = line.split(separator: ";", omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespaces) }
        guard let pair = parts.first, let equals = pair.firstIndex(of: "=") else { return nil }
        let name = pair[..<equals].trimmingCharacters(in: .whitespaces)
        let value = pair[pair.index(after: equals)...].trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { return nil }
        var expires = Date.distantFuture
        var maxAge: Int?
        var domain: String?
        var path: String?
        var secure = false
        for attribute in parts.dropFirst() {
            let split = attribute.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            let key = split[0].trimmingCharacters(in: .whitespaces).lowercased()
            let text = split.count > 1 ? split[1].trimmingCharacters(in: .whitespaces) : ""
            switch key {
            case "expires": if let date = httpDate(text) { expires = date }
            case "max-age": if let seconds = Int(text) { maxAge = seconds }
            case "domain": domain = text
            case "path": path = text
            case "secure": secure = true
            default: break
            }
        }
        if let maxAge { expires = maxAge <= 0 ? .distantPast : now.addingTimeInterval(TimeInterval(maxAge)) }
        var hostOnly = true
        var cookieDomain = host
        if var given = domain?.lowercased(), !given.isEmpty {
            if given.hasPrefix(".") { given.removeFirst() }
            guard domainMatches(host, given) else { return nil }
            cookieDomain = given
            hostOnly = false
        }
        let cookiePath = path.flatMap { $0.hasPrefix("/") ? $0 : nil } ?? defaultPath(url)
        return Kept(name: name, value: value, domain: cookieDomain, path: cookiePath, hostOnly: hostOnly, secure: secure, expires: expires)
    }

    static func matches(_ cookie: Kept, _ url: URL) -> Bool {
        guard let host = url.host?.lowercased() else { return false }
        let domainOK = cookie.hostOnly ? host == cookie.domain : domainMatches(host, cookie.domain)
        guard domainOK, pathMatches(requestPath(url), cookie.path) else { return false }
        return !cookie.secure || url.scheme?.lowercased() == "https"
    }

    static func domainMatches(_ host: String, _ domain: String) -> Bool {
        host == domain || (host.hasSuffix("." + domain) && !isAddress(host))
    }

    /// An IP address, which no domain cookie ever matches.
    static func isAddress(_ host: String) -> Bool {
        host.contains(":") || host.allSatisfy { $0.isNumber || $0 == "." }
    }

    static func pathMatches(_ path: String, _ cookiePath: String) -> Bool {
        if path == cookiePath { return true }
        guard path.hasPrefix(cookiePath) else { return false }
        return cookiePath.hasSuffix("/") || path.dropFirst(cookiePath.count).first == "/"
    }

    static func requestPath(_ url: URL) -> String {
        let path = URLComponents(url: url, resolvingAgainstBaseURL: true)?.percentEncodedPath ?? url.path
        return path.isEmpty ? "/" : path
    }

    /// The path a cookie with none takes: the request's, up to its last "/".
    static func defaultPath(_ url: URL) -> String {
        let path = requestPath(url)
        guard let slash = path.lastIndex(of: "/"), slash != path.startIndex else { return "/" }
        return String(path[..<slash])
    }

    /// An HTTP date in any of the forms servers write in Expires.
    static func httpDate(_ text: String) -> Date? {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "GMT")
        for format in ["EEE, dd MMM yyyy HH:mm:ss zzz", "EEEE, dd-MMM-yy HH:mm:ss zzz", "EEE, dd-MMM-yyyy HH:mm:ss zzz", "EEE MMM d HH:mm:ss yyyy"] {
            formatter.dateFormat = format
            if let date = formatter.date(from: text) { return date }
        }
        return nil
    }
}

/// Keeps the sign-in page cookies an answer from one of the server's own addresses sets: the
/// funnel's final answers, every redirect hop, and the byte delegates' answers.
public func storeCookies(of response: HTTPURLResponse, in cookies: ServerCookies, scope: ServerScope) {
    guard let url = response.url, scope.covers(url), let value = response.value(forHTTPHeaderField: "Set-Cookie") else { return }
    cookies.keep(splitSetCookie(value), from: url)
}

/// A Cookie header with the request's own cookies first (any sign-in page cookie among them
/// dropped, so none goes twice) and the store's after them; nil for none.
func cookieLine(_ existing: String?, adding: String?) -> String? {
    let own = (existing ?? "").split(separator: ";").map { $0.trimmingCharacters(in: .whitespaces) }
        .filter { !$0.isEmpty && !isGatewayCookie(cookieName($0)) }
    let line = (own + [adding].compactMap { $0 }).joined(separator: "; ")
    return line.isEmpty ? nil : line
}

/// Whether a cookie in the phone's shared cookie store would go to one of these hosts. The first
/// launch of this version deletes every such cookie: nothing reads that store for a server any
/// more.
public func isKeptServerCookie(domain: String, hosts: Set<String>) -> Bool {
    var name = domain.lowercased()
    if name.hasPrefix(".") { name.removeFirst() }
    return hosts.contains { host in
        let lower = host.lowercased()
        return lower == name || lower.hasSuffix("." + name)
    }
}

/// Deletes every cookie in `store` that would go to one of `hosts`: the first launch's purge of the
/// phone's shared cookie store.
public func forgetKeptServerCookies(in store: HTTPCookieStorage, hosts: Set<String>) {
    for cookie in store.cookies ?? [] where isKeptServerCookie(domain: cookie.domain, hosts: hosts) {
        store.deleteCookie(cookie)
    }
}

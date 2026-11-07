import Foundation

/// Where a server's own things may go: its main and home addresses, and for a plain http one the
/// same host over https too, where the server or the proxy in front of it moves the app up. Its
/// extra headers and this install's id and name go only to those origins (scheme, host and port),
/// its client certificate only to those hosts, and a sign-in page's headers (`GatewayNames`) only
/// to its gateway origins.
public struct ServerScope: Sendable, Equatable {
    public private(set) var origins: Set<String>
    public private(set) var hosts: Set<String>
    /// The main address and the home network address, when the scope was made from them
    /// (`init(main:home:)`); nil for a scope made from a plain list, which has no gateway
    /// origins.
    public let main: URL?
    public let home: URL?

    /// The headers every request may carry wherever it goes; any other one is the server's own.
    static let plainHeaders: Set<String> = [
        "accept", "accept-encoding", "accept-language", "content-length", "content-type", "if-range", "range", "user-agent",
    ]

    public init(_ addresses: [URL] = []) {
        origins = Self.reached(addresses)
        hosts = Set(addresses.compactMap { $0.host?.lowercased() }.filter { !$0.isEmpty })
        main = nil
        home = nil
    }

    /// The scope of a server with this main address and, when it has one, this home address.
    public init(main: URL, home: URL?) {
        let addresses = [main] + (home.map { [$0] } ?? [])
        origins = Self.reached(addresses)
        hosts = Set(addresses.compactMap { $0.host?.lowercased() }.filter { !$0.isEmpty })
        self.main = main
        self.home = home
    }

    public var isEmpty: Bool { origins.isEmpty }

    /// Where a sign-in page's headers may go: the main address, the home address only when it is
    /// https, and the same host over https for either one when it is plain http. None at all when
    /// the main address is plain http to a host outside the home network, where anyone on the way
    /// could read them.
    public var gatewayOrigins: Set<String> {
        guard let main, let origin = ServerAddress.origin(main) else { return [] }
        if main.scheme?.lowercased() != "https" && !ServerAddress.isPrivate(main) { return [] }
        var out: Set<String> = [origin]
        if let movedUp = Self.secureOrigin(main) { out.insert(movedUp) }
        if let home {
            if home.scheme?.lowercased() == "https", let homeOrigin = ServerAddress.origin(home) { out.insert(homeOrigin) }
            if let movedUp = Self.secureOrigin(home) { out.insert(movedUp) }
        }
        return out
    }

    /// Whether a sign-in page's headers may go to this address.
    public func isGatewayOrigin(_ url: URL?) -> Bool {
        guard let origin = url.flatMap(ServerAddress.origin) else { return false }
        return gatewayOrigins.contains(origin)
    }

    /// Whether a request to this address goes to the server.
    public func covers(_ url: URL?) -> Bool {
        guard let origin = url.flatMap(ServerAddress.origin) else { return false }
        return origins.contains(origin)
    }

    /// Whether this host is one of the server's own. With no host to go by, it is not.
    public func isOwnHost(_ host: String?) -> Bool {
        guard let host, !host.isEmpty else { return false }
        return hosts.contains(host.lowercased())
    }

    /// The names of the headers a redirected request has to lose: none while it stays at the
    /// server's addresses or where it was first sent; elsewhere every header but the plain ones.
    public func headersToDrop(_ headers: [String: String], to url: URL?, first: URL?) -> [String] {
        if covers(url) { return [] }
        if let to = url.flatMap(ServerAddress.origin), let from = first.flatMap(ServerAddress.origin), to == from { return [] }
        return headers.keys.filter { !Self.plainHeaders.contains($0.lowercased()) }.sorted()
    }

    /// Each address's origin, and for a plain http one its host over https as well.
    static func reached(_ addresses: [URL]) -> Set<String> {
        Set(addresses.flatMap { [ServerAddress.origin($0), secureOrigin($0)].compactMap { $0 } })
    }

    /// The same host over https on https's own port, for a plain http address; nil for any other.
    static func secureOrigin(_ url: URL) -> String? {
        guard url.scheme?.lowercased() == "http", let host = url.host?.lowercased(), !host.isEmpty else { return nil }
        return "https://\(host):443"
    }
}

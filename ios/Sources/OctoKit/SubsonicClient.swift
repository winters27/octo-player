import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// How the app proves who it is to the server.
public enum AuthMode: String, Codable, Sendable {
    /// md5(password + salt) with a new salt each request; the password never travels.
    case token
    /// The password itself, hex encoded, for servers that check passwords against a directory.
    case legacyPassword
    /// A key made on the server, sent in place of a username and password. Only servers that
    /// list the apiKeyAuthentication extension take it.
    case apiKey
}

/// Who is signing in and the secret that proves it: the password, or for an API key the key.
/// With a key the username is only for showing, and may be empty until the server names it.
/// The secret is never printed.
public struct Credentials: Sendable, Equatable, CustomStringConvertible {
    public var username: String
    var secret: String
    public var mode: AuthMode

    public init(username: String, password: String, mode: AuthMode = .token) {
        self.username = username
        self.secret = password
        self.mode = mode
    }

    /// The query parameters that prove who is asking, fresh for each request.
    func authParams(salt: String = Credentials.newSalt()) -> [(String, String)] {
        switch mode {
        case .token: [("u", username), ("t", MD5.hex(secret + salt)), ("s", salt)]
        case .legacyPassword: [("u", username), ("p", "enc:" + secret.utf8.map { String(format: "%02x", $0) }.joined())]
        case .apiKey: [("apiKey", secret)]
        }
    }

    /// The same sign-in under the name the server gave it.
    public func named(_ name: String) -> Credentials {
        Credentials(username: name, password: secret, mode: mode)
    }

    static func newSalt() -> String {
        (0..<8).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
    }

    public var description: String { "Credentials(username: \(username), mode: \(mode))" }
}

/// This install as its own server knows it: an id made once and a name a person reads, so an
/// Octo server can tell one phone from another.
public struct DeviceIdentity: Sendable, Equatable {
    public var id: String
    public var name: String

    public init(id: String, name: String) {
        let printable = id.unicodeScalars.filter { (0x21...0x7E).contains($0.value) }
        self.id = printable.isEmpty ? "unknown" : String(String.UnicodeScalarView(printable))
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        self.name = trimmed.isEmpty ? "Octo device" : String(trimmed.prefix(64))
    }

    /// The name as it travels in a header: UTF-8, percent-encoded.
    var headerName: String {
        name.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(.init(charactersIn: "-._~"))) ?? name
    }

    public var headers: [String: String] {
        ["X-Octo-Device-Id": id, "X-Octo-Device-Name": headerName]
    }
}

public enum OctoError: Error, Equatable, LocalizedError {
    case wrongCredentials(String)
    case authUnsupported(code: Int, message: String)
    case notFound(String)
    case server(code: Int, message: String)
    /// An answer that is not a music server's. `gateway` names the sign-in page in front of the
    /// server when one answered, and `loginSent` is whether the app's sign-in for it went along.
    case notSubsonic(gateway: Gateway? = nil, loginSent: Bool = false)
    case http(Int, gateway: Gateway? = nil, loginSent: Bool = false)

    public var errorDescription: String? {
        switch self {
        case .wrongCredentials: "Wrong username or password."
        case .authUnsupported(_, let message): message.isEmpty ? "This server wants a different way of signing in." : message
        case .notFound(let message): message.isEmpty ? "Not found." : message
        case .server(_, let message): message
        case .notSubsonic(let gateway?, let loginSent), .http(_, let gateway?, let loginSent): GatewayWords.failure(gateway, loginSent: loginSent)
        case .notSubsonic: "That address answers, but not as a music server."
        case .http(let status, _, _): "The server answered \(status)."
        }
    }
}

/// One signed-in server: builds signed addresses and makes the calls the app needs.
public final class SubsonicClient: Sendable {
    public static let apiVersion = "1.16.1"
    public static let clientName = "Octo"

    public let baseURL: URL
    public let credentials: Credentials
    public let device: DeviceIdentity
    public let appVersion: String
    /// Headers the server needs, such as an access token for a proxy in front of it. They go
    /// only to the server's addresses, and a sign-in page's (`GatewayNames`) only to its gateway
    /// addresses: `headers(for:)` is the one way out.
    private let extraHeaders: [String: String]
    public let session: URLSession
    /// Where this server's own things may go: its main and home addresses.
    public let scope: ServerScope
    /// The sign-in page's session cookies for this configuration, shared by its copies. Another
    /// configuration (another server, a sign-out, new headers) is a new client with a new store.
    public let cookies: ServerCookies
    /// How long a call may take, when shorter than each call's own limit.
    let timeout: TimeInterval?
    /// The music folder the library is limited to, nil for all of them. Every album list,
    /// artist list, folder index and search carries it.
    public let musicFolderId: String?

    /// `scope` names the server's main and home addresses; left out, it is `baseURL` alone.
    public init(baseURL: URL, credentials: Credentials, device: DeviceIdentity, appVersion: String,
                customHeaders: [String: String] = [:], session: URLSession = .shared, timeout: TimeInterval? = nil,
                musicFolderId: String? = nil, scope: ServerScope? = nil, cookies: ServerCookies = ServerCookies()) {
        self.baseURL = baseURL
        self.credentials = credentials
        self.device = device
        self.appVersion = appVersion
        self.extraHeaders = customHeaders
        self.session = session
        self.scope = scope ?? ServerScope(main: baseURL, home: nil)
        self.cookies = cookies
        self.timeout = timeout
        self.musicFolderId = musicFolderId
    }

    public var userAgent: String { octoUserAgent(appVersion) }

    /// The same server with other credentials, its scope and cookies.
    public func with(_ credentials: Credentials) -> SubsonicClient {
        SubsonicClient(baseURL: baseURL, credentials: credentials, device: device, appVersion: appVersion,
                       customHeaders: extraHeaders, session: session, timeout: timeout, musicFolderId: musicFolderId,
                       scope: scope, cookies: cookies)
    }

    /// The same server reached at another address, like its home network address, with its scope
    /// and cookies. `timeout` limits each call further.
    public func at(_ address: URL, timeout: TimeInterval? = nil) -> SubsonicClient {
        SubsonicClient(baseURL: address, credentials: credentials, device: device, appVersion: appVersion,
                       customHeaders: extraHeaders, session: session, timeout: timeout ?? self.timeout, musicFolderId: musicFolderId,
                       scope: scope, cookies: cookies)
    }

    /// The same server with its library limited to one music folder, nil for all of them.
    public func limited(to folderId: String?) -> SubsonicClient {
        SubsonicClient(baseURL: baseURL, credentials: credentials, device: device, appVersion: appVersion,
                       customHeaders: extraHeaders, session: session, timeout: timeout, musicFolderId: folderId,
                       scope: scope, cookies: cookies)
    }

    /// The same server through another session, such as a short-lived one while the server
    /// restarts, with its scope and cookies.
    public func through(_ session: URLSession) -> SubsonicClient {
        SubsonicClient(baseURL: baseURL, credentials: credentials, device: device, appVersion: appVersion,
                       customHeaders: extraHeaders, session: session, timeout: timeout, musicFolderId: musicFolderId,
                       scope: scope, cookies: cookies)
    }

    /// A signed address for an endpoint. Every call gets a fresh salt, so never use one as a
    /// cache key.
    public func url(_ endpoint: String, _ params: [(String, String)] = []) -> URL {
        var parts = URLComponents(url: baseURL.appendingPathComponent("rest").appendingPathComponent(endpoint),
                                  resolvingAgainstBaseURL: false)!
        let query = credentials.authParams()
            + [("v", Self.apiVersion), ("c", Self.clientName), ("f", "json")]
            + params
        parts.percentEncodedQueryItems = query.map {
            URLQueryItem(name: $0.0, value: $0.1.addingPercentEncoding(withAllowedCharacters: Self.queryValue))
        }
        return parts.url!
    }

    /// Signs an address outside /rest, such as Octo's admin pages, the same way. A fresh salt each time.
    public func signed(_ address: URL) -> URL {
        var parts = URLComponents(url: address, resolvingAgainstBaseURL: false)!
        let query = credentials.authParams() + [("v", Self.apiVersion), ("c", Self.clientName)]
        parts.percentEncodedQueryItems = (parts.percentEncodedQueryItems ?? []) + query.map {
            URLQueryItem(name: $0.0, value: $0.1.addingPercentEncoding(withAllowedCharacters: Self.queryValue))
        }
        return parts.url!
    }

    /// Query values with `+`, `&` and `=` escaped too, so a password or a search survives.
    static let queryValue: CharacterSet = .urlQueryAllowed.subtracting(.init(charactersIn: "+&=?/"))

    /// The headers a request to `url` carries: at the server's own addresses its extra headers
    /// (a sign-in page's only at its gateway addresses) and this install's id and name; the
    /// app's name everywhere.
    public func headers(for url: URL) -> [String: String] {
        var out: [String: String] = [:]
        if scope.covers(url) {
            let gateway = scope.isGatewayOrigin(url)
            for (name, value) in extraHeaders where gateway || !GatewayNames.isGatewayName(name) { out[name] = value }
            out.merge(device.headers) { $1 }
        }
        out["User-Agent"] = userAgent
        return out
    }

    /// A request ready to go to the server: the headers for its address (any it already has
    /// win), and the sign-in page's cookies for it after any cookie it carries, with URLSession's
    /// own cookie handling off.
    public func prepared(_ request: URLRequest) -> URLRequest {
        var out = request
        out.httpShouldHandleCookies = false
        guard let url = request.url else { return out }
        for (name, value) in headers(for: url) where out.value(forHTTPHeaderField: name) == nil {
            out.setValue(value, forHTTPHeaderField: name)
        }
        let kept = scope.covers(url) ? cookies.cookieHeader(for: url) : nil
        out.setValue(cookieLine(out.value(forHTTPHeaderField: "Cookie"), adding: kept), forHTTPHeaderField: "Cookie")
        return out
    }

    func request(_ url: URL, timeout: TimeInterval = 30) -> URLRequest {
        var request = URLRequest(url: url, timeoutInterval: min(timeout, self.timeout ?? timeout))
        for (name, value) in headers(for: url) { request.setValue(value, forHTTPHeaderField: name) }
        return request
    }

    /// Calls an endpoint and decodes the part of the answer under `key`, or the whole answer
    /// when `key` is nil.
    public func get<T: Decodable>(_ endpoint: String, _ params: [(String, String)] = [], key: String?,
                                  as type: T.Type = T.self, timeout: TimeInterval = 30) async throws -> T {
        try Self.decode(try await body(of: request(url(endpoint, params), timeout: timeout)), key: key)
    }

    /// One call's body, through `ServerExchange`: an error status is `OctoError.http`, and a body
    /// that is neither empty nor JSON is `OctoError.notSubsonic`, each naming the sign-in page in
    /// front of the server when one answered.
    func body(of request: URLRequest) async throws -> Data {
        let answer = try await ServerExchange.answer(for: request, client: self)
        if !answer.isOK { throw OctoError.http(answer.status, gateway: answer.gateway, loginSent: answer.loginSent) }
        if !answer.isBlank && !looksLikeJson(answer.data) {
            throw OctoError.notSubsonic(gateway: answer.gateway, loginSent: answer.loginSent)
        }
        return answer.data
    }

    /// Calls an endpoint that answers with nothing but its status.
    public func call(_ endpoint: String, _ params: [(String, String)] = []) async throws {
        let _: Empty = try await get(endpoint, params, key: nil)
    }

    static func decode<T: Decodable>(_ data: Data, key: String?) throws -> T {
        let decoder = JSONDecoder()
        decoder.userInfo[.payloadKey] = key ?? ""
        let body: Envelope<T>
        do {
            body = try decoder.decode(Envelope<T>.self, from: data)
        } catch {
            throw OctoError.notSubsonic()
        }
        let answer = body.response
        if answer.status != "ok" {
            let code = answer.error?.code ?? 0
            let message = answer.error?.message ?? ""
            if answer.error?.octoConflict == true { throw PlaylistChanged(message: message) }
            switch code {
            case 40, 44: throw OctoError.wrongCredentials(message)
            case 41, 42: throw OctoError.authUnsupported(code: code, message: message)
            case 70: throw OctoError.notFound(message)
            default: throw OctoError.server(code: code, message: message)
            }
        }
        guard let value = answer.value else { throw OctoError.notSubsonic() }
        return value
    }
}

struct Empty: Decodable {}

extension CodingUserInfoKey {
    static let payloadKey = CodingUserInfoKey(rawValue: "octo.payloadKey")!
}

struct AnyKey: CodingKey {
    var stringValue: String
    var intValue: Int? { nil }
    init(_ string: String) { stringValue = string }
    init?(stringValue: String) { self.stringValue = stringValue }
    init?(intValue: Int) { nil }
}

struct Envelope<T: Decodable>: Decodable {
    let response: Answer<T>

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        response = try c.decode(Answer<T>.self, forKey: AnyKey("subsonic-response"))
    }
}

struct Answer<T: Decodable>: Decodable {
    struct Failure: Decodable { let code: Int?; let message: String?; let octoConflict: Bool? }
    let status: String
    let error: Failure?
    let value: T?

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        status = (try? c.decode(String.self, forKey: AnyKey("status"))) ?? ""
        error = try? c.decodeIfPresent(Failure.self, forKey: AnyKey("error"))
        let key = decoder.userInfo[.payloadKey] as? String ?? ""
        if status != "ok" {
            value = nil
        } else if key.isEmpty {
            value = try? T(from: decoder)
        } else {
            value = try? c.decodeIfPresent(T.self, forKey: AnyKey(key))
        }
    }
}

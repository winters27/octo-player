import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// What can stand in front of a server and answer in its place: a sign-in gateway the apps know
/// by name, a plain password prompt (HTTP Basic), or a sign-in page they do not know, a public
/// Wi-Fi's portal among them. The raw values are the names the other apps and the shared
/// fixtures use.
public enum Gateway: String, Sendable, Equatable, Codable, CaseIterable {
    case authentik = "Authentik", authelia = "Authelia", cloudflareAccess = "CloudflareAccess",
         passwordPrompt = "PasswordPrompt", signInPage = "SignInPage"
}

/// The three names a gateway login uses, whatever its kind. Compared without case.
public enum GatewayNames {
    public static let authorization = "Authorization"
    public static let cfId = "CF-Access-Client-Id"
    public static let cfSecret = "CF-Access-Client-Secret"

    static let all = [authorization, cfId, cfSecret]

    public static func isGatewayName(_ name: String) -> Bool {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        return all.contains { $0.caseInsensitiveCompare(trimmed) == .orderedSame }
    }
}

/// One answer on the way to the final one: each redirect hop, then the final answer, with its
/// headers in the order they came. Addresses carry the sign-in in their query and headers carry
/// cookies, so neither is ever printed.
public struct AnswerHop: Sendable, Equatable, CustomStringConvertible {
    public let url: URL?
    public let status: Int
    public let headers: [(String, String)]

    public init(url: URL?, status: Int, headers: [(String, String)]) {
        self.url = url
        self.status = status
        self.headers = headers
    }

    /// A hop as URLSession saw it.
    public init(_ response: HTTPURLResponse) {
        let pairs: [(String, String)] = response.allHeaderFields.compactMap { pair in
            guard let name = pair.key as? String, let value = pair.value as? String else { return nil }
            return (name, value)
        }
        self.init(url: response.url, status: response.statusCode, headers: pairs)
    }

    public static func == (a: AnswerHop, b: AnswerHop) -> Bool {
        a.url == b.url && a.status == b.status && a.headers.elementsEqual(b.headers) { $0.0 == $1.0 && $0.1 == $1.1 }
    }

    public var description: String {
        let shown = url.map { $0.absoluteString.components(separatedBy: "?")[0] } ?? "nil"
        return "AnswerHop(url=\(shown), status=\(status), headers=\(headers.map(\.0)))"
    }

    /// The last value of a header, its name compared without case.
    func header(_ name: String) -> String? {
        headers.last { $0.0.caseInsensitiveCompare(name) == .orderedSame }?.1
    }

    /// Every value of a header, in order.
    func values(_ name: String) -> [String] {
        headers.filter { $0.0.caseInsensitiveCompare(name) == .orderedSame }.map(\.1)
    }

    /// Where a Location header points, read against this hop's address.
    var location: URL? {
        guard let value = header("Location") else { return nil }
        return URL(string: value, relativeTo: url)?.absoluteURL
    }
}

/// What an answer shows about what gave it: the address asked, every hop in order (the last is
/// the final answer), the start of an HTML final body (at most 16 KB), and whether the app's own
/// sign-in for a gateway went with the request.
public struct AnswerFacts: Sendable, CustomStringConvertible {
    public let requested: URL?
    public let hops: [AnswerHop]
    public let htmlBody: String?
    public let loginSent: Bool

    public init(requested: URL?, hops: [AnswerHop], htmlBody: String?, loginSent: Bool) {
        self.requested = requested
        self.hops = hops
        self.htmlBody = htmlBody
        self.loginSent = loginSent
    }

    public var description: String {
        let shown = requested.map { $0.absoluteString.components(separatedBy: "?")[0] } ?? "nil"
        return "AnswerFacts(requested=\(shown), hops=\(hops), htmlBody=\(htmlBody.map { "\($0.count)" } ?? "nil"), loginSent=\(loginSent))"
    }
}

/// The gateway that gave this answer, or nil when the server itself did. Cookie and header marks
/// count only on an answer a gateway gives (a redirect, a 401 or 403, or a web page), so the
/// server's own errors after a header sign-in are never taken for one, and a JSON answer from the
/// server's own address never is. JSON is told by the final answer's Content-Type, as the other
/// apps tell it. The first rule that matches wins.
public func recognizeGateway(_ facts: AnswerFacts) -> Gateway? {
    guard let last = facts.hops.last else { return nil }
    let lastType = mediaType(last.header("Content-Type"))
    let same = sameServer(facts.requested, last.url)
    if same && isJsonType(lastType) { return nil }
    let places = facts.hops.flatMap { hop in [hop.url, hop.location].compactMap { $0 } }
    let gatewayAnswers = facts.hops.filter(looksLikeGateway)
    let cookies = gatewayAnswers.flatMap { $0.values("Set-Cookie").flatMap(splitSetCookie) }.map(cookieName)
    let body = (facts.htmlBody ?? "").lowercased()
    if places.contains(where: isAuthentikPlace) || cookies.contains(where: { $0.hasPrefix("authentik_proxy") })
        || body.contains("goauthentik") || body.contains("aria-label=\"authentik\"") {
        return .authentik
    }
    if places.contains(where: isCloudflarePlace) || cookies.contains("CF_Authorization")
        || gatewayAnswers.contains(where: { $0.header("cf-access-aud") != nil || $0.header("cf-access-domain") != nil }) {
        return .cloudflareAccess
    }
    if places.contains(where: isAutheliaPlace) || cookies.contains("authelia_session") { return .authelia }
    if last.status == 401,
       last.values("WWW-Authenticate").contains(where: { $0.trimmingCharacters(in: .whitespaces).lowercased().hasPrefix("basic") }) {
        return .passwordPrompt
    }
    if !same && lastType == "text/html" { return .signInPage }
    if facts.loginSent && (last.status == 401 || last.status == 403) && !isJsonType(lastType) { return .signInPage }
    return nil
}

/// The media type of a Content-Type value, lowercased, its parameters dropped:
/// "text/html; charset=utf-8" is "text/html". Nil for none.
public func mediaType(_ contentType: String?) -> String? {
    guard let contentType else { return nil }
    let type = String(contentType.split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false)[0])
        .trimmingCharacters(in: .whitespaces).lowercased()
    return type.isEmpty ? nil : type
}

public func isHtml(_ contentType: String?) -> Bool { mediaType(contentType) == "text/html" }

/// Whether a body reads as JSON: { or [ first, after any whitespace or BOM.
public func looksLikeJson(_ data: Data) -> Bool {
    guard let first = firstMeaningfulByte(data) else { return false }
    return first == UInt8(ascii: "{") || first == UInt8(ascii: "[")
}

/// Whether a body holds nothing but whitespace.
public func isBlankBody(_ data: Data) -> Bool { firstMeaningfulByte(data) == nil }

/// "<" first, after a UTF-8 BOM and any whitespace: an HTML page kept as if it were media.
public func looksLikeHtmlFile(_ firstBytes: Data) -> Bool {
    let bytes = Array(firstBytes)
    var at = bytes.starts(with: [0xEF, 0xBB, 0xBF]) ? 3 : 0
    while at < bytes.count && spaceBytes.contains(bytes[at]) { at += 1 }
    return at < bytes.count && bytes[at] == UInt8(ascii: "<")
}

private let spaceBytes: Set<UInt8> = [0x20, 0x09, 0x0D, 0x0A]

/// The first byte that is neither whitespace nor part of a UTF-8 BOM.
private func firstMeaningfulByte(_ data: Data) -> UInt8? {
    let bytes = Array(data)
    var at = 0
    while at < bytes.count {
        if bytes.count - at >= 3 && bytes[at] == 0xEF && bytes[at + 1] == 0xBB && bytes[at + 2] == 0xBF {
            at += 3
        } else if spaceBytes.contains(bytes[at]) {
            at += 1
        } else {
            return bytes[at]
        }
    }
    return nil
}

func isJsonType(_ type: String?) -> Bool {
    type == "application/json" || type == "text/json" || type?.hasSuffix("+json") == true
}

private func looksLikeGateway(_ hop: AnswerHop) -> Bool {
    (300...399).contains(hop.status) || hop.status == 401 || hop.status == 403 || isHtml(hop.header("Content-Type"))
}

private func encodedPath(_ url: URL) -> String {
    URLComponents(url: url, resolvingAgainstBaseURL: true)?.percentEncodedPath ?? url.path
}

private func isAuthentikPlace(_ url: URL) -> Bool {
    let path = encodedPath(url)
    return path.contains("/outpost.goauthentik.io/") || path.hasPrefix("/if/flow/")
}

private func isCloudflarePlace(_ url: URL) -> Bool {
    (url.host?.lowercased().hasSuffix(".cloudflareaccess.com") ?? false) || encodedPath(url).hasPrefix("/cdn-cgi/access/")
}

private func isAutheliaPlace(_ url: URL) -> Bool {
    let names = Set((URLComponents(url: url, resolvingAgainstBaseURL: true)?.queryItems ?? []).map(\.name))
    return names.contains("rd") && names.contains("rm")
}

/// The same server: the same origin, or the same host moved up from http to https.
func sameServer(_ requested: URL?, _ answered: URL?) -> Bool {
    guard let requested, let answered, let from = ServerAddress.origin(requested), let to = ServerAddress.origin(answered) else {
        return false
    }
    if from == to { return true }
    return requested.host?.lowercased() == answered.host?.lowercased()
        && requested.scheme?.lowercased() == "http" && answered.scheme?.lowercased() == "https"
}

/// Set-Cookie values one by one. URLSession joins several Set-Cookie headers into one value with
/// commas; a comma starts a new cookie only where a name and "=" follow it, never inside an
/// Expires date.
func splitSetCookie(_ value: String) -> [String] {
    var out: [String] = []
    var current = ""
    for piece in value.components(separatedBy: ",") {
        if !current.isEmpty && startsCookie(piece) {
            out.append(current)
            current = piece
        } else {
            current = current.isEmpty ? piece : current + "," + piece
        }
    }
    if !current.isEmpty { out.append(current) }
    return out.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
}

private func startsCookie(_ text: String) -> Bool {
    let trimmed = text.drop { $0 == " " || $0 == "\t" }
    guard let equals = trimmed.firstIndex(of: "=") else { return false }
    let name = trimmed[..<equals]
    return !name.isEmpty && name.allSatisfy { HeaderRules.nameCharacters.contains($0) }
}

/// A cookie's name: what comes before its first "=".
func cookieName(_ cookie: String) -> String {
    String(cookie.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)[0]).trimmingCharacters(in: .whitespaces)
}

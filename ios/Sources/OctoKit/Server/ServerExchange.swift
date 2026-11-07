import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// The most of a web page read to tell which sign-in page sent it.
let htmlPeek = 16 * 1024

/// One answer from the server, whatever its status: its body, how it ended, the sign-in page in
/// front of the server that gave it when one did, and whether the app's own sign-in for that
/// page went with the request.
public struct ServerAnswer: Sendable {
    public let data: Data
    public let response: HTTPURLResponse
    public let gateway: Gateway?
    public let loginSent: Bool

    public var status: Int { response.statusCode }
    public var isOK: Bool { (200..<300).contains(status) }
    /// Nothing to read: a 204 or 205, or a body of nothing but whitespace.
    public var isBlank: Bool { status == 204 || status == 205 || isBlankBody(data) }
}

/// The one way server code asks the server for anything. Each request gets the client's headers
/// for its address and the sign-in page's cookies (`SubsonicClient.prepared`); every redirect
/// goes through `hop`, which keeps the server's rules for headers and cookies; the final
/// answer's cookies are kept whatever its status; and the answer is read for a sign-in page in
/// front of the server. A failure to reach the server comes out as URLSession gave it.
public enum ServerExchange {
    /// The answer as it came, whatever its status, with the sign-in page that gave it named.
    public static func answer(for request: URLRequest, client: SubsonicClient) async throws -> ServerAnswer {
        let sent = client.prepared(request)
        let (data, response, hops) = try await send(sent, client: client)
        return try read(data, response, hops: hops, sent: sent, client: client)
    }

    /// A JSON route's body. An error status, or a body that is neither JSON nor empty, is
    /// `SubsonicError.notSubsonic`, naming the sign-in page in front of the server when one
    /// answered. A 204, a 205 or a blank body comes back as no data.
    public static func data(for request: URLRequest, client: SubsonicClient, endpoint: String) async throws -> (Data, HTTPURLResponse) {
        let got = try await answer(for: request, client: client)
        guard got.isOK else { throw notSubsonic(got, endpoint: endpoint) }
        if got.isBlank { return (Data(), got.response) }
        guard looksLikeJson(got.data) else { throw notSubsonic(got, endpoint: endpoint) }
        return (got.data, got.response)
    }

    /// A song's, cover's or file's bytes, whole. An error status, or a web page where media was
    /// asked for, is refused (`refusal`).
    public static func media(for request: URLRequest, client: SubsonicClient, endpoint: String) async throws -> (Data, HTTPURLResponse) {
        let got = try await answer(for: request, client: client)
        if let refused = refusal(got, endpoint: endpoint) { throw refused }
        return (got.data, got.response)
    }

    /// The error for an answer a JSON route cannot use: "HTTP <code> from <endpoint>" for an
    /// error status, else the plain words, with the sign-in page that gave it.
    public static func notSubsonic(_ answer: ServerAnswer, endpoint: String) -> SubsonicError {
        if answer.isOK {
            return .notSubsonic(SubsonicError.notSubsonicWords, status: nil, gateway: answer.gateway, loginSent: answer.loginSent)
        }
        return .notSubsonic("HTTP \(answer.status) from \(endpoint)", status: answer.status, gateway: answer.gateway, loginSent: answer.loginSent)
    }

    /// Why an answer where a song, cover or file was asked for cannot be played or kept: a sign-in
    /// page in the server's place (`signInPageInstead`: a web page, or a 401 or 403 it is known
    /// by), named when it is known, else an unknown sign-in page; or any other error status. Nil
    /// for a usable answer.
    public static func refusal(_ answer: ServerAnswer, endpoint: String) -> SubsonicError? {
        if let page = signInPageInstead(status: answer.status, contentType: answer.response.value(forHTTPHeaderField: "Content-Type"),
                                        gateway: answer.gateway) {
            return .notSubsonic(GatewayWords.playFailure, status: answer.isOK ? nil : answer.status, gateway: page, loginSent: answer.loginSent)
        }
        return answer.isOK ? nil : notSubsonic(answer, endpoint: endpoint)
    }

    #if canImport(Darwin)
    /// A song's bytes as they come, by the same rules as `media`: an error status or a web page
    /// is read for its sign-in page and refused, its task canceled.
    public static func bytes(for request: URLRequest, client: SubsonicClient, endpoint: String) async throws -> (URLSession.AsyncBytes, HTTPURLResponse) {
        let sent = client.prepared(request)
        let hops = ExchangeHops(client: client, first: sent.url)
        let (bytes, response) = try await client.session.bytes(for: sent, delegate: hops)
        guard let http = response as? HTTPURLResponse else {
            bytes.task.cancel()
            throw URLError(.badServerResponse)
        }
        let page = isHtml(http.value(forHTTPHeaderField: "Content-Type"))
        if (200..<300).contains(http.statusCode) && !page {
            storeCookies(of: http, in: client.cookies, scope: client.scope)
            return (bytes, http)
        }
        var peek = Data()
        if page {
            for try await byte in bytes {
                peek.append(byte)
                if peek.count >= htmlPeek { break }
            }
        }
        bytes.task.cancel()
        let got = try read(peek, http, hops: hops.recorded, sent: sent, client: client)
        throw refusal(got, endpoint: endpoint) ?? notSubsonic(got, endpoint: endpoint)
    }

    /// A file fetched to disk by the same rules as `media`. A refused one is deleted.
    public static func download(for request: URLRequest, client: SubsonicClient, endpoint: String) async throws -> (URL, HTTPURLResponse) {
        let sent = client.prepared(request)
        let hops = ExchangeHops(client: client, first: sent.url)
        let (file, response) = try await client.session.download(for: sent, delegate: hops)
        guard let http = response as? HTTPURLResponse else {
            try? FileManager.default.removeItem(at: file)
            throw URLError(.badServerResponse)
        }
        var peek = Data()
        if !(200..<300).contains(http.statusCode) || isHtml(http.value(forHTTPHeaderField: "Content-Type")),
           let handle = try? FileHandle(forReadingFrom: file) {
            peek = (try? handle.read(upToCount: htmlPeek)) ?? Data()
            try? handle.close()
        }
        let got = try read(peek, http, hops: hops.recorded, sent: sent, client: client)
        if let refused = refusal(got, endpoint: endpoint) {
            try? FileManager.default.removeItem(at: file)
            throw refused
        }
        return (file, http)
    }

    /// Where a request to the server ends after its redirects, each one through `hop` as every
    /// call's are: a one-byte ask whose body is never read, its final answer's cookies kept. A
    /// failure to reach the server comes out as URLSession gave it.
    public static func finalAddress(of request: URLRequest, client: SubsonicClient) async throws -> URL {
        var asked = client.prepared(request)
        asked.setValue("bytes=0-0", forHTTPHeaderField: "Range")
        let hops = ExchangeHops(client: client, first: asked.url)
        let (bytes, response) = try await client.session.bytes(for: asked, delegate: hops)
        bytes.task.cancel()
        guard let http = response as? HTTPURLResponse, let url = http.url else { throw URLError(.badServerResponse) }
        storeCookies(of: http, in: client.cookies, scope: client.scope)
        return url
    }
    #endif

    static func send(_ request: URLRequest, client: SubsonicClient) async throws -> (Data, URLResponse, [AnswerHop]) {
        #if canImport(Darwin)
        let hops = ExchangeHops(client: client, first: request.url)
        let (data, response) = try await client.session.data(for: request, delegate: hops)
        return (data, response, hops.recorded)
        #else
        let (data, response) = try await client.session.data(for: request)
        return (data, response, [])
        #endif
    }

    /// The final answer: its cookies kept, whatever its status, and read for a sign-in page.
    static func read(_ data: Data, _ response: URLResponse, hops: [AnswerHop], sent: URLRequest,
                     client: SubsonicClient) throws -> ServerAnswer {
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        storeCookies(of: http, in: client.cookies, scope: client.scope)
        let loginSent = carriesGatewayLogin(sent)
        let html = isHtml(http.value(forHTTPHeaderField: "Content-Type")) ? String(decoding: data.prefix(htmlPeek), as: UTF8.self) : nil
        let facts = AnswerFacts(requested: sent.url, hops: hops + [AnswerHop(http)], htmlBody: html, loginSent: loginSent)
        return ServerAnswer(data: data, response: http, gateway: recognizeGateway(facts), loginSent: loginSent)
    }
}

/// Whether an answer where a song, cover or file was asked of the server is a sign-in page in its
/// place, and which: a web page with a success status (only a sign-in page sends one there), or a
/// 401 or 403 the recognizer names (`gateway`, what `recognizeGateway` made of it) or that is a web
/// page. Such an answer fails once: never played or kept, and not asked for again. Nil for any
/// other answer, which each route handles by its own rules.
public func signInPageInstead(status: Int, contentType: String?, gateway: Gateway?) -> Gateway? {
    let page = isHtml(contentType)
    if (200..<300).contains(status) { return page ? gateway ?? .signInPage : nil }
    guard status == 401 || status == 403 else { return nil }
    return gateway ?? (page ? Gateway.signInPage : nil)
}

/// The same for a byte delegate's answer as it starts, read from its status and headers alone
/// (no body has come yet), for a request sent to one of the server's own addresses. Nil for a
/// request sent anywhere else.
public func signInPageInstead(of response: HTTPURLResponse, sent: URLRequest?, scope: ServerScope) -> Gateway? {
    guard scope.covers(sent?.url) else { return nil }
    let facts = AnswerFacts(requested: sent?.url, hops: [AnswerHop(response)], htmlBody: nil, loginSent: carriesGatewayLogin(sent))
    return signInPageInstead(status: response.statusCode, contentType: response.value(forHTTPHeaderField: "Content-Type"),
                             gateway: recognizeGateway(facts))
}

/// Whether a request carries the app's own sign-in for a sign-in page (one of `GatewayNames`).
func carriesGatewayLogin(_ request: URLRequest?) -> Bool {
    (request?.allHTTPHeaderFields ?? [:]).keys.contains(where: GatewayNames.isGatewayName)
}

/// A request with each of a sign-in page's headers (`GatewayNames`) it lacks, from the headers the
/// signing client gives its address (`headers`, the client's `headers(for:)`, which names them
/// only at its gateway addresses). Every other header stays as it is.
public func addingGatewayLogin(_ request: URLRequest, headers: (URL) -> [String: String]) -> URLRequest {
    guard let url = request.url else { return request }
    var out = request
    for (name, value) in headers(url) where GatewayNames.isGatewayName(name) && out.value(forHTTPHeaderField: name) == nil {
        out.setValue(value, forHTTPHeaderField: name)
    }
    return out
}

/// The request a background download goes out with, at the address its redirects end at
/// (`landed`, as `ServerExchange.finalAddress` follows them): the client's headers for that
/// address (`headers(for:)`: the server's own and, at a gateway address, its sign-in page's; off
/// the server the app's name alone), and `serverOnly` only at one of the server's addresses.
/// Cookies are off, since the system carries the transfer on by itself.
public func backgroundDownloadRequest(_ landed: URL, client: SubsonicClient, serverOnly: [String: String] = [:]) -> URLRequest {
    var request = URLRequest(url: landed)
    for (name, value) in client.headers(for: landed) { request.setValue(value, forHTTPHeaderField: name) }
    if client.scope.covers(landed) {
        for (name, value) in serverOnly { request.setValue(value, forHTTPHeaderField: name) }
    }
    request.httpShouldHandleCookies = false
    return request
}

/// One redirect hop of a request to the server: the hop's sign-in page cookies are kept, the
/// next request loses what may not go where it leads (every header but the plain ones once it
/// leaves the server and where it was first sent, a sign-in page's headers anywhere but its
/// gateway addresses), at a gateway address gets each sign-in page header it lacks from the
/// signing client's `headers` (`addingGatewayLogin`), and gets the store's cookies for its
/// address. Nil refuses the redirect: one that leads anywhere but http or https.
public func hop(response: HTTPURLResponse, newRequest: URLRequest, first: URL?, cookies: ServerCookies,
                scope: ServerScope, headers: (URL) -> [String: String]) -> URLRequest? {
    guard let url = newRequest.url, let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" else { return nil }
    storeCookies(of: response, in: cookies, scope: scope)
    var next = newRequest
    next.httpShouldHandleCookies = false
    for name in scope.headersToDrop(next.allHTTPHeaderFields ?? [:], to: url, first: first) {
        next.setValue(nil, forHTTPHeaderField: name)
    }
    if scope.isGatewayOrigin(url) {
        next = addingGatewayLogin(next, headers: headers)
    } else {
        for name in (next.allHTTPHeaderFields ?? [:]).keys where GatewayNames.isGatewayName(name) {
            next.setValue(nil, forHTTPHeaderField: name)
        }
    }
    let kept = scope.covers(url) ? cookies.cookieHeader(for: url) : nil
    next.setValue(cookieLine(next.value(forHTTPHeaderField: "Cookie"), adding: kept), forHTTPHeaderField: "Cookie")
    return next
}

#if canImport(Darwin)
/// The hops of one call, as its own task delegate sees them: each redirect is kept for the
/// recognizer and goes through `hop`. It answers nothing else, so the session's own delegate
/// keeps the certificate checks, the client certificate and its notes on how a call ended.
final class ExchangeHops: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let client: SubsonicClient
    private let first: URL?
    private let lock = NSLock()
    private var hops: [AnswerHop] = []

    init(client: SubsonicClient, first: URL?) {
        self.client = client
        self.first = first
        super.init()
    }

    var recorded: [AnswerHop] { lock.withLock { hops } }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest) async -> URLRequest? {
        lock.withLock { hops.append(AnswerHop(response)) }
        return hop(response: response, newRequest: request, first: first, cookies: client.cookies, scope: client.scope,
                   headers: client.headers(for:))
    }
}
#endif

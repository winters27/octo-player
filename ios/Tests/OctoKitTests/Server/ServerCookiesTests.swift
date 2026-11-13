import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@testable import OctoKit

/// A sign-in page's session cookies: which are kept, where they go, when they end. Expiries are
/// given as Expires dates, read against the store's own clock, the same vectors as the other apps.
@Suite struct ServerCookiesTests {
    let clock = GatewayTestClock("2026-11-06T18:00:00Z")
    let music = URL(string: "https://music.example.com/rest/ping.view")!

    var jar: ServerCookies {
        let clock = self.clock
        return ServerCookies(now: { clock.now })
    }

    func expires(_ day: Int) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "GMT")!
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "GMT")
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
        return formatter.string(from: calendar.date(from: DateComponents(year: 2026, month: 12, day: day))!)
    }

    @Test func onlyASignInPagesOwnCookiesAreKept() {
        let jar = self.jar
        jar.keep(["octo_admin=a1; Path=/; HttpOnly", "octo_family=f1; Path=/", "authentik_proxy_3c=p1; Path=/",
                  "authelia_session=s1; Path=/", "CF_Authorization=c1; Path=/", "session=x; Path=/"], from: music)
        #expect(jar.cookieHeader(for: music) == "authentik_proxy_3c=p1; authelia_session=s1; CF_Authorization=c1")
    }

    @Test func anExpiryInThePastRemovesTheCookie() {
        let jar = self.jar
        jar.keep(["authentik_proxy_a=p1; Path=/"], from: music)
        jar.keep(["authentik_proxy_a=; Path=/; Expires=Thu, 01 Jan 2026 00:00:00 GMT"], from: music)
        #expect(jar.cookieHeader(for: music) == nil)
        #expect(jar.isEmpty)
    }

    @Test func aDeleteThenASetInOneAnswerKeepsTheNewOne() {
        let jar = self.jar
        jar.keep(["authentik_proxy_a=old; Path=/"], from: music)
        jar.keep(["authentik_proxy_a=; Path=/; Expires=Thu, 01 Jan 2026 00:00:00 GMT", "authentik_proxy_a=new; Path=/"], from: music)
        #expect(jar.cookieHeader(for: music) == "authentik_proxy_a=new")
    }

    @Test func aSecureCookieNeverGoesOverHttp() {
        let jar = self.jar
        jar.keep(["authentik_proxy_a=p1; Path=/; Secure"], from: music)
        #expect(jar.cookieHeader(for: music) == "authentik_proxy_a=p1")
        #expect(jar.cookieHeader(for: URL(string: "http://music.example.com/rest/ping.view")!) == nil)
    }

    @Test func aHostOnlyCookieNeverGoesToASubdomainAndADomainOneDoes() {
        let jar = self.jar
        jar.keep(["authentik_proxy_a=p1; Path=/"], from: music)
        #expect(jar.cookieHeader(for: URL(string: "https://home.music.example.com/rest/ping.view")!) == nil)
        jar.keep(["authelia_session=s1; Path=/; Domain=.example.com"], from: music)
        #expect(jar.cookieHeader(for: URL(string: "https://auth.example.com/")!) == "authelia_session=s1")
        jar.keep(["CF_Authorization=c1; Path=/; Domain=other.net"], from: music)
        #expect(jar.cookieHeader(for: URL(string: "https://other.net/")!) == nil)
    }

    @Test func aCookieWithNoPathKeepsToItsFolder() {
        let jar = self.jar
        jar.keep(["authentik_proxy_p=1"], from: URL(string: "https://music.example.com/octo/rest/ping")!)
        #expect(jar.cookieHeader(for: URL(string: "https://music.example.com/octo/rest/stream")!) == "authentik_proxy_p=1")
        #expect(jar.cookieHeader(for: URL(string: "https://music.example.com/octo/api/x")!) == nil)
        #expect(jar.cookieHeader(for: URL(string: "https://music.example.com/octo/restless")!) == nil)
    }

    @Test func theCookieThatExpiresFirstMakesRoom() throws {
        let jar = self.jar
        for day in [2, 3, 4, 5, 1] + Array(6...17) {
            jar.keep(["authentik_proxy_\(day)=v; Path=/; Expires=\(expires(day))"], from: music)
        }
        let sent = try #require(jar.cookieHeader(for: music)).components(separatedBy: "; ")
        #expect(sent.count == 16)
        #expect(!sent.contains("authentik_proxy_1=v"))
        #expect(sent.contains("authentik_proxy_17=v"))
        #expect(sent.contains("authentik_proxy_2=v"))
    }

    @Test func theStoreReadsItsOwnClock() {
        let jar = self.jar
        jar.keep(["authentik_proxy_a=p1; Path=/; Expires=Sat, 07 Nov 2026 18:00:00 GMT", "authelia_session=s1; Path=/; Max-Age=60"], from: music)
        #expect(jar.cookieHeader(for: music) == "authentik_proxy_a=p1; authelia_session=s1")
        clock.now = clock.now.addingTimeInterval(61)
        #expect(jar.cookieHeader(for: music) == "authentik_proxy_a=p1")
        clock.now = ISO8601DateFormatter().date(from: "2026-11-07T18:00:01Z")!
        #expect(jar.cookieHeader(for: music) == nil)
    }

    @Test func theStoreNeverPrintsItsValues() {
        let jar = self.jar
        jar.keep(["authentik_proxy_a=p1-secret; Path=/"], from: music)
        #expect(!jar.description.contains("p1-secret"))
    }

    /// Red when `id` is made anew on each read, or is one value for every store.
    @Test func eachStoreHasAnIdOfItsOwnThatItsClientsCopiesShare() {
        let store = ServerCookies()
        #expect(store.id == store.id)
        #expect(store.id != ServerCookies().id)
        let client = SubsonicClient(baseURL: music, credentials: Credentials(username: "a", password: "b"),
                                    device: DeviceIdentity(id: "abc", name: "Phone"), appVersion: "1.0", cookies: store)
        #expect(client.with(Credentials(username: "c", password: "d")).cookies.id == store.id)
        #expect(client.at(URL(string: "http://192.168.1.20:4533")!).cookies.id == store.id)
    }

    @Test func aByteAnswersCookieIsKeptOnlyFromTheServer() {
        let jar = self.jar
        let scope = ServerScope(main: URL(string: "https://music.example.com/octo")!, home: nil)
        storeCookies(of: HTTPURLResponse(url: URL(string: "https://music.example.com/octo/rest/stream")!, statusCode: 206, httpVersion: nil,
                                         headerFields: ["Set-Cookie": "authentik_proxy_s=1; Path=/"])!, in: jar, scope: scope)
        storeCookies(of: HTTPURLResponse(url: URL(string: "https://cdn.example.net/x")!, statusCode: 200, httpVersion: nil,
                                         headerFields: ["Set-Cookie": "authentik_proxy_o=9; Path=/"])!, in: jar, scope: scope)
        #expect(jar.cookieHeader(for: music) == "authentik_proxy_s=1")
        #expect(jar.cookieHeader(for: URL(string: "https://cdn.example.net/x")!) == nil)
    }

    @Test func theFunnelKeepsAnAnswersCookieAndSendsItNextTime() async throws {
        let server = FakeServer()
        server.route { request in
            if request.headers["Cookie"] == nil {
                return FakeServer.Reply(status: 200, body: Data(FakeServer.okBody().utf8),
                                        headers: ["Content-Type": "application/json", "Set-Cookie": "octo_admin=a1; Path=/, authentik_proxy_a=1; Path=/"])
            }
            return FakeServer.Reply(status: 200, body: Data(FakeServer.okBody().utf8))
        }
        let client = server.client()
        _ = try await client.ping()
        _ = try await client.with(Credentials(username: "winters", password: "old")).ping()
        #expect(server.requests[0].headers["Cookie"] == nil)
        #expect(server.requests[1].headers["Cookie"] == "authentik_proxy_a=1")
        // Another configuration is another client with an empty store.
        let fresh = server.client()
        #expect(fresh.cookies.isEmpty)
        _ = try await fresh.ping()
        #expect(server.requests[2].headers["Cookie"] == nil)
    }

    @Test func aStaleCookieWithTheHeaderPassesAndTheNewCookieReplacesIt() async throws {
        let server = FakeServer()
        let basic = basicCredentials(username: "alice", password: "app password")
        server.route { request in
            let cookie = request.headers["Cookie"] ?? ""
            if cookie.contains("authentik_proxy_s=fresh") { return FakeServer.Reply(status: 200, body: Data(FakeServer.okBody().utf8)) }
            if request.headers["Authorization"] == basic {
                return FakeServer.Reply(status: 200, body: Data(FakeServer.okBody().utf8),
                                        headers: ["Content-Type": "application/json", "Set-Cookie": "authentik_proxy_s=fresh; Path=/"])
            }
            return GatewayPages.reply(401)
        }
        let client = server.client(headers: ["Authorization": basic])
        client.cookies.keep(["authentik_proxy_s=stale; Path=/"], from: server.base)
        _ = try await client.ping()
        _ = try await client.ping()
        #expect(server.requests[0].headers["Cookie"] == "authentik_proxy_s=stale")
        #expect(server.requests[0].headers["Authorization"] == basic)
        #expect(server.requests[1].headers["Cookie"] == "authentik_proxy_s=fresh")
        #expect(server.requests[1].headers["Authorization"] == basic)
    }

    @Test func theFirstLaunchPurgeTakesEveryCookieOfTheKeptHosts() {
        let hosts: Set<String> = ["music.example.com", "192.168.1.20"]
        #expect(isKeptServerCookie(domain: "music.example.com", hosts: hosts))
        #expect(isKeptServerCookie(domain: ".example.com", hosts: hosts))
        #expect(isKeptServerCookie(domain: "192.168.1.20", hosts: hosts))
        #expect(!isKeptServerCookie(domain: "other.net", hosts: hosts))
        #expect(!isKeptServerCookie(domain: "home.music.example.com", hosts: hosts))
    }

    /// The purge run on a cookie store of the system's own kind: the kept hosts' cookies go, a
    /// sign-in page's and Octo's own alike, and another host's stays. Red when it deletes nothing,
    /// or more than the kept hosts' cookies.
    @Test func theFirstLaunchPurgeDeletesTheKeptHostsCookiesFromAStore() throws {
        let store = HTTPCookieStorage.sharedCookieStorage(forGroupContainerIdentifier: "octo-tests-first-launch-purge")
        store.cookieAcceptPolicy = .always
        for left in store.cookies ?? [] { store.deleteCookie(left) }
        defer { for left in store.cookies ?? [] { store.deleteCookie(left) } }
        for (name, domain) in [("authentik_proxy_a", "music.example.com"), ("authelia_session", ".example.com"),
                               ("octo_admin", "192.168.1.20"), ("elsewhere", "other.net")] {
            store.setCookie(try #require(HTTPCookie(properties: [.name: name, .value: "1", .domain: domain, .path: "/"])))
        }
        #expect(store.cookies?.count == 4)
        forgetKeptServerCookies(in: store, hosts: ["music.example.com", "192.168.1.20"])
        #expect(store.cookies?.map(\.name) == ["elsewhere"])
    }
}

/// One redirect hop, tested directly (Linux cannot follow one through URLProtocol): the hop's
/// cookie kept and sent on the next hop, and the headers each address may have.
@Suite struct ServerHopTests {
    let main = URL(string: "https://music.example.com/octo")!
    let home = URL(string: "http://192.168.1.20:4533")!
    let first = URL(string: "https://music.example.com/octo/rest/ping")!
    let basic = basicCredentials(username: "alice", password: "app password")

    func redirect(_ from: URL, to location: String, cookie: String? = nil) -> HTTPURLResponse {
        var fields = ["Location": location]
        if let cookie { fields["Set-Cookie"] = cookie }
        return HTTPURLResponse(url: from, statusCode: 302, httpVersion: "HTTP/1.1", headerFields: fields)!
    }

    /// The next hop's request as URLSession makes it: the first request's headers on the new address.
    func carried(_ to: String) -> URLRequest {
        var request = URLRequest(url: URL(string: to)!)
        request.setValue(basic, forHTTPHeaderField: "Authorization")
        request.setValue("p", forHTTPHeaderField: "X-Proxy-Token")
        request.setValue("abc", forHTTPHeaderField: "X-Octo-Device-Id")
        request.setValue("Octo/1.0 (iPhone)", forHTTPHeaderField: "User-Agent")
        request.setValue("octo_family=f1; authentik_proxy_old=0", forHTTPHeaderField: "Cookie")
        return request
    }

    /// The client that signs the requests: the sign-in page's three headers and a proxy header,
    /// for a server with this scope.
    func signer(_ scope: ServerScope) -> SubsonicClient {
        SubsonicClient(baseURL: scope.main ?? main, credentials: Credentials(username: "alice", password: "pw"),
                       device: DeviceIdentity(id: "abc", name: "Phone"), appVersion: "1.0",
                       customHeaders: ["Authorization": basic, "CF-Access-Client-Id": "id", "CF-Access-Client-Secret": "secret",
                                       "X-Proxy-Token": "p"],
                       scope: scope)
    }

    /// One hop with the headers of the client that signs for `scope`.
    func hopped(_ response: HTTPURLResponse, _ request: URLRequest, first: URL?, cookies: ServerCookies = ServerCookies(),
                scope: ServerScope) -> URLRequest? {
        hop(response: response, newRequest: request, first: first, cookies: cookies, scope: scope, headers: signer(scope).headers(for:))
    }

    @Test func aRedirectHopsCookieIsKeptAndGoesWithTheNextHop() throws {
        let cookies = ServerCookies()
        let to = "https://music.example.com/outpost.goauthentik.io/start?rd=x"
        let next = try #require(hopped(redirect(first, to: to, cookie: "authentik_proxy_a=1; Path=/"), carried(to),
                                       first: first, cookies: cookies, scope: ServerScope(main: main, home: home)))
        #expect(next.value(forHTTPHeaderField: "Cookie") == "octo_family=f1; authentik_proxy_a=1")
        #expect(next.value(forHTTPHeaderField: "Authorization") == basic)
        #expect(next.value(forHTTPHeaderField: "X-Proxy-Token") == "p")
        #expect(!next.httpShouldHandleCookies)
        #expect(cookies.cookieHeader(for: first) == "authentik_proxy_a=1")
    }

    @Test func aHopToTheHttpHomeLosesTheSignInPagesHeaders() throws {
        let to = "http://192.168.1.20:4533/rest/ping"
        let next = try #require(hopped(redirect(first, to: to), carried(to), first: first, scope: ServerScope(main: main, home: home)))
        #expect(next.value(forHTTPHeaderField: "Authorization") == nil)
        #expect(next.value(forHTTPHeaderField: "CF-Access-Client-Id") == nil)
        #expect(next.value(forHTTPHeaderField: "X-Proxy-Token") == "p")
        #expect(next.value(forHTTPHeaderField: "Cookie") == "octo_family=f1")
    }

    @Test func aHopElsewhereLosesEverythingButThePlainHeaders() throws {
        let cookies = ServerCookies()
        let to = "https://auth.example.com/if/flow/default-authentication-flow/"
        let next = try #require(hopped(redirect(first, to: to, cookie: "authentik_proxy_a=1; Path=/"), carried(to),
                                       first: first, cookies: cookies, scope: ServerScope(main: main, home: home)))
        #expect(next.value(forHTTPHeaderField: "Authorization") == nil)
        #expect(next.value(forHTTPHeaderField: "X-Proxy-Token") == nil)
        #expect(next.value(forHTTPHeaderField: "X-Octo-Device-Id") == nil)
        #expect(next.value(forHTTPHeaderField: "Cookie") == nil)
        #expect(next.value(forHTTPHeaderField: "User-Agent") == "Octo/1.0 (iPhone)")
        // A cookie the other host sets on its own hop is never kept.
        let away = URL(string: to)!
        _ = hopped(redirect(away, to: "https://auth.example.com/next", cookie: "authentik_proxy_z=9; Path=/"),
                   URLRequest(url: URL(string: "https://auth.example.com/next")!), first: first, cookies: cookies,
                   scope: ServerScope(main: main, home: home))
        #expect(cookies.cookieHeader(for: away) == nil)
    }

    /// Red when `ServerScope.reached` (headers and cookies dropped) or `gatewayOrigins`'s
    /// `secureOrigin` lines (Authorization dropped) are taken out.
    @Test func aMoveUpToHttpsOnTheSameHostKeepsTheHeadersAndCookies() throws {
        let lan = URL(string: "http://192.168.1.20:4533")!
        let plainFirst = URL(string: "http://192.168.1.20:4533/rest/ping")!
        let cookies = ServerCookies()
        let to = "https://192.168.1.20/rest/ping"
        let next = try #require(hopped(redirect(plainFirst, to: to, cookie: "authentik_proxy_a=1; Path=/"), carried(to),
                                       first: plainFirst, cookies: cookies, scope: ServerScope(main: lan, home: nil)))
        #expect(next.value(forHTTPHeaderField: "Authorization") == basic)
        #expect(next.value(forHTTPHeaderField: "X-Proxy-Token") == "p")
        #expect(next.value(forHTTPHeaderField: "X-Octo-Device-Id") == "abc")
        #expect(next.value(forHTTPHeaderField: "Cookie") == "octo_family=f1; authentik_proxy_a=1")
    }

    /// The http home of an https server: its own request never carries the sign-in page's
    /// headers, and its move up to https on the same host gets them from the signing client.
    /// Red when `hop` only keeps and drops headers.
    @Test func anHttpHomesMoveUpToHttpsGetsTheSignIn() throws {
        let scope = ServerScope(main: main, home: home)
        let sent = signer(scope).prepared(URLRequest(url: URL(string: "http://192.168.1.20:4533/rest/stream")!))
        #expect(sent.value(forHTTPHeaderField: "Authorization") == nil)
        #expect(sent.value(forHTTPHeaderField: "CF-Access-Client-Id") == nil)
        #expect(sent.value(forHTTPHeaderField: "X-Proxy-Token") == "p")
        let fromHome = try #require(sent.url)
        var up = sent
        up.url = URL(string: "https://192.168.1.20/rest/stream")!
        let next = try #require(hopped(redirect(fromHome, to: "https://192.168.1.20/rest/stream"), up, first: fromHome, scope: scope))
        #expect(next.value(forHTTPHeaderField: "Authorization") == basic)
        #expect(next.value(forHTTPHeaderField: "CF-Access-Client-Id") == "id")
        #expect(next.value(forHTTPHeaderField: "CF-Access-Client-Secret") == "secret")
        #expect(next.value(forHTTPHeaderField: "X-Proxy-Token") == "p")
        #expect(next.value(forHTTPHeaderField: "X-Octo-Device-Id") == "abc")
    }

    /// Red when the sign-in is added on a hop to a host that is not the server's.
    @Test func anHttpHomesMoveToAnotherHostGetsNoSignIn() throws {
        let scope = ServerScope(main: main, home: home)
        let sent = signer(scope).prepared(URLRequest(url: URL(string: "http://192.168.1.20:4533/rest/stream")!))
        let fromHome = try #require(sent.url)
        var away = sent
        away.url = URL(string: "https://192.168.1.21/rest/stream")!
        let next = try #require(hopped(redirect(fromHome, to: "https://192.168.1.21/rest/stream"), away, first: fromHome, scope: scope))
        #expect(next.value(forHTTPHeaderField: "Authorization") == nil)
        #expect(next.value(forHTTPHeaderField: "CF-Access-Client-Id") == nil)
        #expect(next.value(forHTTPHeaderField: "CF-Access-Client-Secret") == nil)
        #expect(next.value(forHTTPHeaderField: "X-Proxy-Token") == nil)
        #expect(next.value(forHTTPHeaderField: "X-Octo-Device-Id") == nil)
    }

    /// Where a background download's redirects end, followed by `hop` from the client's own
    /// request to the http home, as `ServerExchange.finalAddress` follows them.
    func finalOfHomeDownload(to location: String, scope: ServerScope) throws -> URL {
        let sent = signer(scope).prepared(URLRequest(url: URL(string: "http://192.168.1.20:4533/rest/stream?id=s1")!))
        let fromHome = try #require(sent.url)
        var moved = sent
        moved.url = URL(string: location)!
        let next = try #require(hopped(redirect(fromHome, to: location), moved, first: fromHome, scope: scope))
        return try #require(next.url)
    }

    /// Red when the background request is built for the asked address, or without the client's
    /// headers for where the redirects end.
    @Test func aBackgroundDownloadMovedUpToTheHttpsTwinCarriesTheSignIn() throws {
        let scope = ServerScope(main: main, home: home)
        let landed = try finalOfHomeDownload(to: "https://192.168.1.20/rest/stream?id=s1", scope: scope)
        let request = backgroundDownloadRequest(landed, client: signer(scope), serverOnly: ["X-Octo-Purpose": "offline"])
        #expect(request.url?.absoluteString == "https://192.168.1.20/rest/stream?id=s1")
        #expect(request.value(forHTTPHeaderField: "Authorization") == basic)
        #expect(request.value(forHTTPHeaderField: "CF-Access-Client-Id") == "id")
        #expect(request.value(forHTTPHeaderField: "CF-Access-Client-Secret") == "secret")
        #expect(request.value(forHTTPHeaderField: "X-Proxy-Token") == "p")
        #expect(request.value(forHTTPHeaderField: "X-Octo-Device-Id") == "abc")
        #expect(request.value(forHTTPHeaderField: "X-Octo-Purpose") == "offline")
        #expect(request.value(forHTTPHeaderField: "Cookie") == nil)
        #expect(!request.httpShouldHandleCookies)
        // The http home itself, unmoved, never carries the sign-in page's headers.
        let plain = backgroundDownloadRequest(URL(string: "http://192.168.1.20:4533/rest/stream?id=s1")!, client: signer(scope))
        #expect(plain.value(forHTTPHeaderField: "Authorization") == nil)
        #expect(plain.value(forHTTPHeaderField: "CF-Access-Client-Id") == nil)
        #expect(plain.value(forHTTPHeaderField: "X-Proxy-Token") == "p")
    }

    /// Red when a background download moved to another host gets anything of the server's.
    @Test func aBackgroundDownloadMovedToAnotherHostCarriesNone() throws {
        let scope = ServerScope(main: main, home: home)
        let landed = try finalOfHomeDownload(to: "https://192.168.1.21/rest/stream?id=s1", scope: scope)
        let request = backgroundDownloadRequest(landed, client: signer(scope), serverOnly: ["X-Octo-Purpose": "offline"])
        #expect(request.url?.host == "192.168.1.21")
        #expect(request.value(forHTTPHeaderField: "Authorization") == nil)
        #expect(request.value(forHTTPHeaderField: "CF-Access-Client-Id") == nil)
        #expect(request.value(forHTTPHeaderField: "CF-Access-Client-Secret") == nil)
        #expect(request.value(forHTTPHeaderField: "X-Proxy-Token") == nil)
        #expect(request.value(forHTTPHeaderField: "X-Octo-Device-Id") == nil)
        #expect(request.value(forHTTPHeaderField: "X-Octo-Purpose") == nil)
        #expect(request.value(forHTTPHeaderField: "User-Agent") != nil)
    }

    /// Red when the move up is counted for any host, not the address's own.
    @Test func aMoveUpToAnotherHostKeepsNone() throws {
        let lan = URL(string: "http://192.168.1.20:4533")!
        let plainFirst = URL(string: "http://192.168.1.20:4533/rest/ping")!
        let cookies = ServerCookies()
        cookies.keep(["authentik_proxy_a=1; Path=/"], from: plainFirst)
        let to = "https://192.168.1.21/rest/ping"
        let next = try #require(hopped(redirect(plainFirst, to: to), carried(to), first: plainFirst, cookies: cookies,
                                       scope: ServerScope(main: lan, home: nil)))
        #expect(next.value(forHTTPHeaderField: "Authorization") == nil)
        #expect(next.value(forHTTPHeaderField: "X-Proxy-Token") == nil)
        #expect(next.value(forHTTPHeaderField: "X-Octo-Device-Id") == nil)
        #expect(next.value(forHTTPHeaderField: "Cookie") == nil)
        #expect(next.value(forHTTPHeaderField: "User-Agent") == "Octo/1.0 (iPhone)")
    }

    /// Red when a public plain http main address gets gateway origins from its move up.
    @Test func aPublicHttpAddressMovedUpKeepsItsCookiesButNeverGetsTheSignIn() throws {
        let plain = URL(string: "http://music.example.com")!
        let plainFirst = URL(string: "http://music.example.com/rest/ping")!
        let to = "https://music.example.com/rest/ping"
        let next = try #require(hopped(redirect(plainFirst, to: to, cookie: "authentik_proxy_a=1; Path=/"), carried(to),
                                       first: plainFirst, scope: ServerScope(main: plain, home: nil)))
        #expect(next.value(forHTTPHeaderField: "Authorization") == nil)
        #expect(next.value(forHTTPHeaderField: "X-Proxy-Token") == "p")
        #expect(next.value(forHTTPHeaderField: "Cookie") == "octo_family=f1; authentik_proxy_a=1")
    }

    @Test func aHopToAnotherSchemeIsRefused() {
        #expect(hopped(redirect(first, to: "ftp://music.example.com/x"), URLRequest(url: URL(string: "ftp://music.example.com/x")!),
                       first: first, scope: ServerScope(main: main, home: home)) == nil)
    }
}

import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@testable import OctoKit

/// Where a server's headers go: a sign-in page's only to its gateway addresses, the rest to the
/// server's addresses, nothing but the app's name anywhere else.
@Suite struct HeaderScopeTests {
    let main = URL(string: "https://music.example.com/octo")!
    let home = URL(string: "http://192.168.1.20:4533")!
    let basic = basicCredentials(username: "alice", password: "app password")

    func client(_ scope: ServerScope, base: URL? = nil) -> SubsonicClient {
        SubsonicClient(baseURL: base ?? main, credentials: Credentials(username: "alice", password: "pw"),
                       device: DeviceIdentity(id: "abc", name: "Phone"), appVersion: "1.0",
                       customHeaders: ["Authorization": basic, "cf-access-client-id": "id", "X-Proxy-Token": "p"], scope: scope)
    }

    @Test func gatewayAddressesAreTheMainOneAndAnHttpsHome() {
        #expect(ServerScope(main: main, home: home).gatewayOrigins == ["https://music.example.com:443", "https://192.168.1.20:443"])
        #expect(ServerScope(main: main, home: URL(string: "https://home.example.com:8443")!).gatewayOrigins
            == ["https://music.example.com:443", "https://home.example.com:8443"])
        #expect(ServerScope(main: home, home: nil).gatewayOrigins == ["http://192.168.1.20:4533", "https://192.168.1.20:443"])
        #expect(ServerScope(main: URL(string: "http://music.example.com")!, home: URL(string: "https://home.example.com")!).gatewayOrigins.isEmpty)
        #expect(ServerScope([main, home]).gatewayOrigins.isEmpty)
        #expect(ServerScope(main: main, home: home).covers(URL(string: "http://192.168.1.20:4533/rest/ping")!))
    }

    @Test func aSignInPagesHeadersGoOnlyToItsAddresses() {
        let headers = client(ServerScope(main: main, home: home))
        let atMain = headers.headers(for: URL(string: "https://music.example.com/octo/rest/ping")!)
        #expect(atMain["Authorization"] == basic)
        #expect(atMain["cf-access-client-id"] == "id")
        #expect(atMain["X-Proxy-Token"] == "p")
        #expect(atMain["X-Octo-Device-Id"] == "abc")
        #expect(atMain["User-Agent"] == "Octo/1.0 (iPhone)")
        let atHome = headers.headers(for: URL(string: "http://192.168.1.20:4533/rest/stream")!)
        #expect(atHome["Authorization"] == nil)
        #expect(atHome["cf-access-client-id"] == nil)
        #expect(atHome["X-Proxy-Token"] == "p")
        #expect(atHome["X-Octo-Device-Id"] == "abc")
        #expect(headers.headers(for: URL(string: "https://cdn.example.net/cover.jpg")!) == ["User-Agent": "Octo/1.0 (iPhone)"])
    }

    @Test func anHttpsHomeGetsThemToo() {
        let secureHome = URL(string: "https://192.168.1.20:4534")!
        #expect(client(ServerScope(main: main, home: secureHome)).headers(for: secureHome.appendingPathComponent("rest/ping"))["Authorization"] == basic)
    }

    /// Red when `ServerScope.reached` or the `secureOrigin` lines of `gatewayOrigins` are taken out.
    @Test func anHttpAddressAlsoReachesItsOwnHostOverHttps() {
        let scope = ServerScope(main: main, home: home)
        let movedUp = URL(string: "https://192.168.1.20/rest/stream")!
        #expect(scope.origins == ["https://music.example.com:443", "http://192.168.1.20:4533", "https://192.168.1.20:443"])
        #expect(scope.hosts == ["music.example.com", "192.168.1.20"])
        #expect(scope.isGatewayOrigin(movedUp))
        #expect(!scope.isGatewayOrigin(home))
        #expect(!scope.covers(URL(string: "https://192.168.1.21/rest/stream")!))
        #expect(!scope.covers(URL(string: "https://192.168.1.20:4534/rest/stream")!))
        let atMovedUp = client(scope).headers(for: movedUp)
        #expect(atMovedUp["Authorization"] == basic)
        #expect(atMovedUp["X-Proxy-Token"] == "p")
        #expect(atMovedUp["X-Octo-Device-Id"] == "abc")
        let plain = ServerScope(main: URL(string: "http://music.example.com")!, home: nil)
        #expect(plain.covers(URL(string: "https://music.example.com/rest/ping")!))
        #expect(plain.gatewayOrigins.isEmpty)
        #expect(ServerScope([home]).covers(movedUp))
        #expect(ServerScope(main: main, home: nil).origins == ["https://music.example.com:443"])
    }

    @Test func aPublicHttpAddressNeverGetsThem() {
        let plain = URL(string: "http://music.example.com")!
        let headers = client(ServerScope(main: plain, home: nil), base: plain)
        #expect(headers.headers(for: plain.appendingPathComponent("rest/ping"))["Authorization"] == nil)
        #expect(headers.headers(for: plain.appendingPathComponent("rest/ping"))["X-Proxy-Token"] == "p")
        #expect(needsHttps(["Authorization": basic], scope: headers.scope))
        #expect(!needsHttps(["X-Proxy-Token": "p"], scope: headers.scope))
        #expect(!needsHttps(["Authorization": basic], scope: ServerScope(main: main, home: nil)))
    }

    @Test func everyCopyKeepsTheScopeAndTheCookies() {
        let original = client(ServerScope(main: main, home: home))
        for copy in [original.with(Credentials(username: "b", password: "c")), original.at(home), original.at(home, timeout: 2),
                     original.limited(to: "3"), original.through(FakeServer.session)] {
            #expect(copy.scope == original.scope)
            #expect(copy.cookies === original.cookies)
        }
        #expect(original.at(home, timeout: 2).timeout == 2)
        #expect(client(ServerScope(main: main, home: nil)).cookies !== original.cookies)
        let plain = SubsonicClient(baseURL: main, credentials: Credentials(username: "a", password: "b"),
                                   device: DeviceIdentity(id: "abc", name: "Phone"), appVersion: "1.0")
        #expect(plain.scope == ServerScope(main: main, home: nil))
    }

    @Test func aPreparedRequestKeepsItsOwnHeadersAndAddsTheCookies() {
        let headers = client(ServerScope(main: main, home: home))
        headers.cookies.keep(["authentik_proxy_a=1; Path=/"], from: main)
        var request = URLRequest(url: URL(string: "https://music.example.com/octo/api/family/join")!)
        request.setValue("Octo/9 (iPhone)", forHTTPHeaderField: "User-Agent")
        request.setValue("octo_family=f1", forHTTPHeaderField: "Cookie")
        let ready = headers.prepared(request)
        #expect(ready.value(forHTTPHeaderField: "User-Agent") == "Octo/9 (iPhone)")
        #expect(ready.value(forHTTPHeaderField: "Authorization") == basic)
        #expect(ready.value(forHTTPHeaderField: "Cookie") == "octo_family=f1; authentik_proxy_a=1")
        #expect(!ready.httpShouldHandleCookies)
        #expect(headers.prepared(ready).value(forHTTPHeaderField: "Cookie") == "octo_family=f1; authentik_proxy_a=1")
        #expect(headers.prepared(URLRequest(url: home.appendingPathComponent("rest/ping"))).value(forHTTPHeaderField: "Cookie") == nil)
    }
}

import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@testable import OctoKit

/// A sign-in page answering a song, cover or file request in the server's place fails once on
/// every byte route: a web page with a success status, or a 401 or 403 it is known by or that is
/// a web page. The server's own refusals keep each route's own rules, and a request sent anywhere
/// else is never judged.
@Suite struct SignInPageInsteadTests {
    let main = URL(string: "https://music.example.com/octo")!
    let song = URL(string: "https://music.example.com/octo/rest/stream?id=1")!
    let basic = basicCredentials(username: "alice", password: "app password")

    var scope: ServerScope { ServerScope(main: main, home: nil) }

    func answer(_ status: Int, _ headers: [String: String] = [:], at url: URL? = nil) -> HTTPURLResponse {
        HTTPURLResponse(url: url ?? song, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
    }

    func sent(_ url: URL? = nil, login: Bool = false) -> URLRequest {
        var request = URLRequest(url: url ?? song)
        request.setValue("bytes=0-", forHTTPHeaderField: "Range")
        if login { request.setValue(basic, forHTTPHeaderField: "Authorization") }
        return request
    }

    /// Red when the rule's 401 and 403 branch is taken out (or gives nil for a named one).
    @Test func theRuleOnItsOwn() {
        #expect(signInPageInstead(status: 200, contentType: "text/html; charset=utf-8", gateway: nil) == .signInPage)
        #expect(signInPageInstead(status: 206, contentType: "audio/flac", gateway: .authentik) == nil)
        #expect(signInPageInstead(status: 401, contentType: nil, gateway: .authelia) == .authelia)
        #expect(signInPageInstead(status: 403, contentType: "text/html", gateway: nil) == .signInPage)
        #expect(signInPageInstead(status: 403, contentType: "application/json", gateway: nil) == nil)
        #expect(signInPageInstead(status: 404, contentType: "text/html", gateway: .authentik) == nil)
        #expect(signInPageInstead(status: 502, contentType: "text/html", gateway: nil) == nil)
    }

    /// Red when the byte delegates' form stops asking the recognizer.
    @Test func aRefusalASignInPageIsKnownByIsOne() {
        #expect(signInPageInstead(of: answer(401, ["Location": "https://auth.example.com/?rd=https%3A%2F%2Fmusic.example.com&rm=GET"]),
                                  sent: sent(), scope: scope) == .authelia)
        #expect(signInPageInstead(of: answer(403, ["cf-access-aud": "abc", "Content-Type": "text/plain"]), sent: sent(), scope: scope)
            == .cloudflareAccess)
        #expect(signInPageInstead(of: answer(401, ["WWW-Authenticate": #"Basic realm="music""#]), sent: sent(), scope: scope) == .passwordPrompt)
        #expect(signInPageInstead(of: answer(401, ["Set-Cookie": "authentik_proxy_a=1; Path=/"]), sent: sent(), scope: scope) == .authentik)
        // Its own sign-in went along and it said no.
        #expect(signInPageInstead(of: answer(403, ["Content-Type": "text/plain"]), sent: sent(login: true), scope: scope) == .signInPage)
    }

    /// Red when a 401 or 403 web page is left to the route's retries.
    @Test func aRefusalThatIsAWebPageIsOneToo() {
        #expect(signInPageInstead(of: answer(401, ["Content-Type": "text/html; charset=utf-8"]), sent: sent(), scope: scope) == .signInPage)
        #expect(signInPageInstead(of: answer(200, ["Content-Type": "text/html"]), sent: sent(), scope: scope) == .signInPage)
        #expect(signInPageInstead(of: answer(200, ["Content-Type": "text/html", "Set-Cookie": "authentik_proxy_a=1; Path=/"]), sent: sent(),
                                  scope: scope) == .authentik)
    }

    /// Red when every 401 or 403 counts as a sign-in page.
    @Test func theServersOwnAnswersAreNever() {
        #expect(signInPageInstead(of: answer(206, ["Content-Type": "audio/flac"]), sent: sent(), scope: scope) == nil)
        #expect(signInPageInstead(of: answer(401, ["Content-Type": "application/json"]), sent: sent(login: true), scope: scope) == nil)
        #expect(signInPageInstead(of: answer(403), sent: sent(), scope: scope) == nil)
        #expect(signInPageInstead(of: answer(404, ["Content-Type": "text/html"]), sent: sent(), scope: scope) == nil)
        #expect(signInPageInstead(of: answer(503, ["Content-Type": "text/html"]), sent: sent(), scope: scope) == nil)
    }

    /// Red when the scope check is taken out.
    @Test func aRequestSentElsewhereIsNeverJudged() {
        let outside = URL(string: "https://cdn.example.net/cover.jpg")!
        #expect(signInPageInstead(of: answer(401, ["WWW-Authenticate": "Basic"], at: outside), sent: sent(outside), scope: scope) == nil)
        #expect(signInPageInstead(of: answer(200, ["Content-Type": "text/html"], at: outside), sent: sent(outside), scope: scope) == nil)
        #expect(signInPageInstead(of: answer(401, ["WWW-Authenticate": "Basic"]), sent: nil, scope: scope) == nil)
    }

    /// Red when `ServerExchange.refusal` reads a named 401 as any other error status ("HTTP 401
    /// from stream"), or when the funnel asks twice.
    @Test func theExchangeRefusesItOnceAsASignInPage() async {
        let server = FakeServer()
        server.route { _ in GatewayPages.reply(401) }
        let client = server.client()
        let request = URLRequest(url: server.base.appendingPathComponent("rest/stream"))
        await #expect(throws: SubsonicError.notSubsonic(GatewayWords.playFailure, status: 401, gateway: .authentik, loginSent: false)) {
            _ = try await ServerExchange.media(for: request, client: client, endpoint: "stream")
        }
        #expect(server.requests.count == 1)
        server.route { _ in FakeServer.Reply(status: 403, body: Data(#"{"error":"admin only"}"#.utf8)) }
        await #expect(throws: SubsonicError.notSubsonic("HTTP 403 from stream", status: 403)) {
            _ = try await ServerExchange.media(for: request, client: client, endpoint: "stream")
        }
    }
}

import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@testable import OctoKit

/// Every OctoKit server route reads its answer through ServerExchange: a sign-in page in front of
/// the server is named on any status, a blank answer or a 204 passes as nothing, and nothing is
/// remembered or signed out for it.
@Suite struct ServerExchangeTests {
    let server = FakeServer()
    let basic = basicCredentials(username: "alice", password: "app password")
    var authentik: String { GatewayWords.failure(.authentik, loginSent: false) }

    func page(_ status: Int) { server.route { _ in GatewayPages.reply(status) } }

    func at(_ path: String) -> URLRequest { URLRequest(url: server.base.appendingPathComponent(path)) }

    @Test func aJsonAnswerPassesAndABlankOneOrA204PassesAsNothing() async throws {
        server.route { request in
            switch request.path {
            case "/a": FakeServer.Reply(status: 204, body: Data())
            case "/b": FakeServer.Reply(status: 200, body: Data(" \n".utf8), headers: ["Content-Type": "text/html"])
            default: FakeServer.Reply(status: 200, body: Data(FakeServer.okBody().utf8))
            }
        }
        let client = server.client()
        let (json, _) = try await ServerExchange.data(for: at("c"), client: client, endpoint: "c")
        #expect(looksLikeJson(json))
        let (none, noContent) = try await ServerExchange.data(for: at("a"), client: client, endpoint: "a")
        #expect(none.isEmpty)
        #expect(noContent.statusCode == 204)
        let (blank, _) = try await ServerExchange.data(for: at("b"), client: client, endpoint: "b")
        #expect(blank.isEmpty)
    }

    @Test func aSignInPageIsNamedOnAnyStatusAndTheServersOwnJsonNever() async {
        page(401)
        await #expect(throws: SubsonicError.notSubsonic("HTTP 401 from ping", status: 401, gateway: .authentik, loginSent: false)) {
            _ = try await ServerExchange.data(for: at("rest/ping"), client: server.client(), endpoint: "ping")
        }
        page(200)
        await #expect(throws: SubsonicError.notSubsonic(SubsonicError.notSubsonicWords, status: nil, gateway: .authentik, loginSent: false)) {
            _ = try await ServerExchange.data(for: at("rest/ping"), client: server.client(), endpoint: "ping")
        }
        server.route { _ in FakeServer.Reply(status: 503, body: Data(#"{"error":"busy"}"#.utf8)) }
        await #expect(throws: SubsonicError.notSubsonic("HTTP 503 from ping", status: 503)) {
            _ = try await ServerExchange.data(for: at("rest/ping"), client: server.client(), endpoint: "ping")
        }
    }

    @Test func loginSentIsTrueOnlyWithTheSignInPageFieldsFilled() async {
        page(401)
        await #expect(throws: SubsonicError.notSubsonic("HTTP 401 from ping", status: 401, gateway: .authentik, loginSent: false)) {
            _ = try await server.client().ping()
        }
        await #expect(throws: SubsonicError.notSubsonic("HTTP 401 from ping", status: 401, gateway: .authentik, loginSent: true)) {
            _ = try await server.client(headers: ["Authorization": basic]).ping()
        }
        #expect(server.requests.last?.headers["Authorization"] == basic)
    }

    @Test func everyClientCallNamesTheSignInPage() async {
        page(401)
        let client = server.client()
        await #expect(throws: OctoError.http(401, gateway: .authentik, loginSent: false)) { _ = try await client.user("alice") }
        await #expect(throws: OctoError.http(401, gateway: .authentik, loginSent: false)) {
            try await client.updatePlaylist("p1", name: "Mix", formPost: true)
        }
        page(200)
        await #expect(throws: OctoError.notSubsonic(gateway: .authentik, loginSent: false)) { _ = try await client.user("alice") }
        #expect(userMessage(OctoError.http(401, gateway: .authentik)) == authentik)
    }

    @Test func aSignInPageNeverReadsAsAMissingFeature() async {
        page(401)
        #expect(await server.client().supportsIfKnown("octoTransitions") == nil)
        server.route { _ in FakeServer.Reply(status: 404, body: Data()) }
        #expect(await server.client().supportsIfKnown("octoTransitions") == false)
    }

    @Test func anInviteMetByASignInPageSaysSoAndNeverCrashes() async {
        page(200)
        #expect(await joinWithInvite(client: server.joinClient(), token: "t", displayName: "Alex", password: "long enough")
            == .failed(authentik, unreachable: false, gateway: .authentik))
        page(401)
        #expect(await joinWithInvite(client: server.joinClient(headers: ["Authorization": basic]), token: "t", displayName: "Alex",
                                     password: "long enough")
            == .failed(GatewayWords.failure(.authentik, loginSent: true), unreachable: false, gateway: .authentik, loginSent: true))
        #expect(server.requests.last?.headers["Authorization"] == basic)
    }

    @MainActor @Test func aManagersChangeMetByASignInPageSaysSo() async {
        page(200)
        do {
            _ = try await server.client().familyWeb().newInvite(username: "sam")
            Issue.record("a sign-in page was read as an answer")
        } catch {
            #expect(SubsonicError(error).gateway == .authentik)
            #expect(FamilyModel.actionProblem(error) == authentik)
            #expect(FamilyModel.readProblem(error) == authentik)
        }
    }

    @Test func aSignInPageDuringAHandOverIsNeverAnExpiredLink() async {
        let link = FamilyHandOverLink(base: server.base.absoluteString, token: "tok_9", key: HandOverBox.newKey(), server: "https://music.example.com")
        let refused = HandOverResult.refused(authentik, gateway: .authentik)
        page(401)
        #expect(await receiveHandOver(link, connect: { server.joinClient(at: $0) }, deviceName: "Phone", platform: .iOS) == refused)
        server.route { request in
            request.endpoint == "redeemFamilySignIn"
                ? FakeServer.Reply(status: 200, body: Data(FakeServer.okBody(#""familySignInRedeemed":{"id":"r_1"}"#).utf8))
                : GatewayPages.reply(401)
        }
        #expect(await receiveHandOver(link, connect: { server.joinClient(at: $0) }, deviceName: "Phone", platform: .iOS, pollMs: 10) == refused)
    }

    @Test func anAdminCallMetByASignInPageSaysSo() async {
        page(401)
        let admin = OctoAdmin(store: AdminStore())
        let signIn = AdminSignIn(server: "winters@home", client: server.client(), primary: server.base, keptServers: 1)
        await #expect(throws: AdminUnavailable(authentik)) { _ = try await admin.status(server.base, signIn) }
        await #expect(throws: AdminUnavailable(authentik)) { try await admin.refreshStations(server.base, signIn) }
        #expect(await admin.locate(signIn) == nil)
    }

    @Test func aServerUpdateMetByASignInPageIsNoAnswerAndItsStartSaysWhy() async {
        page(401)
        let client = server.client()
        #expect(await ServerUpdateCalls.readState(client) == .missed)
        #expect(await ServerUpdateCalls.start(client, tag: "2026.11.06") == .refused(authentik))
        server.route { _ in FakeServer.Reply(status: 403, body: Data()) }
        #expect(await ServerUpdateCalls.readState(client) == .refused)
        #expect(await ServerUpdateCalls.start(client, tag: "2026.11.06") == .refused(ServerUpdateWords.adminOnly))
    }

    @Test func aPasswordChangeMetByASignInPageChangesNothingAndSaysWhy() async {
        page(401)
        #expect(await changeOwnPassword(server.client(), current: "old", new: "new password") == .failed(authentik))
        #expect(server.requests.count == 1)
    }

    @Test func aKeptServerBehindASignInPageSaysSoOnItsRow() {
        let error = SubsonicError.notSubsonic("HTTP 401 from ping", status: 401, gateway: .authentik, loginSent: true)
        #expect(reachOf(error) == .signInPage(.authentik, loginSent: true))
        #expect(reachOf(OctoError.wrongCredentials("x")) == .wrongPassword)
        #expect(reachOf(URLError(.timedOut)) == .unreachable)
        #expect(statusLine(serverType: "octo", serverVersion: "0.9.3", signedOut: false, check: ServerCheck(reachOf(error)))
            == "Octo 0.9.3 · Authentik didn't accept the sign-in")
        #expect(statusLine(serverType: "octo", serverVersion: "0.9.3", signedOut: false, check: ServerCheck(.needsHttps))
            == "Octo 0.9.3 · Needs https for its sign-in page")
        #expect(ServerCheck(.needsHttps).isWarning)
        #expect(ServerCheck(.signInPage(.signInPage, loginSent: false)).isWarning)
        #expect(!ServerCheck(.unknown).isWarning)
    }

    @Test func mediaRefusesAPageWhereAPictureWasAsked() async throws {
        server.route { request in
            switch request.path {
            case "/cover": FakeServer.Reply(status: 200, body: Data([0xFF, 0xD8]), headers: ["Content-Type": "image/jpeg"])
            case "/gone": FakeServer.Reply(status: 404, body: Data())
            default: GatewayPages.reply(200, "<html><body>Welcome</body></html>")
            }
        }
        let client = server.client()
        let (picture, _) = try await ServerExchange.media(for: at("cover"), client: client, endpoint: "getCoverArt")
        #expect(picture == Data([0xFF, 0xD8]))
        await #expect(throws: SubsonicError.notSubsonic(GatewayWords.playFailure, status: nil, gateway: .signInPage, loginSent: false)) {
            _ = try await ServerExchange.media(for: at("page"), client: client, endpoint: "getCoverArt")
        }
        await #expect(throws: SubsonicError.notSubsonic("HTTP 404 from getCoverArt", status: 404)) {
            _ = try await ServerExchange.media(for: at("gone"), client: client, endpoint: "getCoverArt")
        }
    }

    @Test func aLoopFileThatIsAPageIsNeverKeptAndOneKeptBeforeIsFetchedAgain() async throws {
        let body = Data((0..<512).map { UInt8($0 & 0xFF) })
        server.route { request in
            request.path == "/page.mp4" ? GatewayPages.reply(200)
                : FakeServer.Reply(status: 200, body: body, headers: ["Content-Type": "video/mp4", "Content-Length": "\(body.count)"])
        }
        let fetcher = LoopFetcher(session: FakeServer.session, client: server.client())
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("loops-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let refused = dir.appendingPathComponent("a.mp4")
        #expect(!(await fetcher.fetch(LoopFile(url: server.base.appendingPathComponent("page.mp4"), seconds: 1), to: refused)))
        #expect(!FileManager.default.fileExists(atPath: refused.path))
        let kept = dir.appendingPathComponent("b.mp4")
        try Data("<!DOCTYPE html><html></html>".utf8).write(to: kept)
        #expect(await fetcher.fetch(LoopFile(url: server.base.appendingPathComponent("loop.mp4"), seconds: 1), to: kept))
        #expect(try Data(contentsOf: kept) == body)
    }

    @Test func theSignInPageCrossesEveryErrorKind() {
        let error = SubsonicError.notSubsonic("HTTP 401 from ping", status: 401, gateway: .authelia, loginSent: true)
        #expect(plainError(error) as? OctoError == .http(401, gateway: .authelia, loginSent: true))
        #expect(SubsonicError(plainError(error)) == .notSubsonic("HTTP 401", status: 401, gateway: .authelia, loginSent: true))
        let page = SubsonicError.notSubsonic(SubsonicError.notSubsonicWords, status: nil, gateway: .authentik, loginSent: false)
        #expect(plainError(page) as? OctoError == .notSubsonic(gateway: .authentik, loginSent: false))
        #expect(SubsonicError(plainError(page)) == page)
        #expect(error.gateway == .authelia)
        #expect(error.loginSent)
        #expect(error.status == 401)
        #expect(SubsonicError.wrongCredentials("x").gateway == nil)
        let words = GatewayWords.failure(.authelia, loginSent: true)
        #expect(userMessage(plainError(error)) == words)
        #expect(SignInWords.describe(plainError(error)) == words)
        #expect(userMessage(for: plainError(error)) == words)
        #expect(OctoError.notSubsonic(gateway: .authentik).errorDescription == authentik)
        #expect(userMessage(OctoError.http(503)) == SignInWords.restarting)
    }
}

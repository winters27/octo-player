import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@testable import OctoKit

/// A fetched song's cover on the admin screen: the one Octo serves, on the admin pages' own
/// address and signed, when the server names it; and the server's own pictures told from outside
/// ones, so only outside ones load without the server's connection.
@Suite struct AdminCoverTests {
    let base = URL(string: "http://192.168.50.21:5274/")!

    func signed(_ url: URL) -> URL { URL(string: url.absoluteString + "&u=me")! }

    /// Red when `withServedCover` hands the record back unchanged.
    @Test func aCoverOctoServesTakesThePlaceOfTheSavedOne() {
        let record = DownloadRecord(title: "Angel", coverArtUrl: "https://cdn.example.net/a.jpg",
                                    cover: "/api/admin/downloads/cover?at=2026-10-30T01%3A30%3A00.9766532Z")
        #expect(withServedCover(record, base: base, sign: signed).coverArtUrl
            == "http://192.168.50.21:5274/api/admin/downloads/cover?at=2026-10-30T01%3A30%3A00.9766532Z&u=me")
    }

    /// Red when the "//" or "/" checks are taken out.
    @Test func aServerThatNamesNoneOrAnotherHostKeepsTheSavedOne() {
        let old = DownloadRecord(title: "Old", coverArtUrl: "https://cdn.example.net/b.jpg")
        #expect(withServedCover(old, base: base, sign: signed) == old)
        let away = DownloadRecord(title: "Away", coverArtUrl: "https://cdn.example.net/c.jpg", cover: "//elsewhere.example.net/x")
        #expect(withServedCover(away, base: base, sign: signed) == away)
        let full = DownloadRecord(title: "Full", coverArtUrl: "https://cdn.example.net/d.jpg", cover: "https://elsewhere.example.net/x")
        #expect(withServedCover(full, base: base, sign: signed) == full)
    }

    /// Red when `OctoAdmin.downloads` stops mapping its records, or `cover` is not read.
    @Test func theDownloadsListReadsTheServedCoverAndSignsIt() async throws {
        let server = FakeServer()
        server.route { request in
            switch request.path {
            case "/api/admin/status": FakeServer.Reply(status: 200, body: Data(#"{"octo":{"ok":true}}"#.utf8))
            case "/api/admin/downloads":
                FakeServer.Reply(status: 200, body: Data(#"{"downloads":[{"title":"Angel","coverArtUrl":"https://cdn.example.net/a.jpg","cover":"/api/admin/downloads/cover?at=2026-10-30T01%3A30%3A00Z"}]}"#.utf8))
            default: FakeServer.Reply(status: 404, body: Data())
            }
        }
        let admin = OctoAdmin(store: AdminStore())
        let signIn = AdminSignIn(server: "winters@home", client: server.client(), primary: server.base, keptServers: 1)
        let found = try #require(await admin.locate(signIn))
        let records = try await admin.downloads(found, signIn)
        let shown = try #require(records.first?.coverArtUrl.flatMap { URLComponents(string: $0) })
        #expect(shown.host == server.host)
        #expect(shown.path == "/api/admin/downloads/cover")
        #expect(shown.queryItems?.first { $0.name == "at" }?.value == "2026-10-30T01:30:00Z")
        #expect(shown.queryItems?.first { $0.name == "u" }?.value == "winters")
    }

    /// Red when `isServerPicture` stops looking at the scope or at the admin pages' address.
    @Test func theServersOwnPicturesAreToldFromOutsideOnes() {
        let main = URL(string: "https://music.example.com")!
        let client = SubsonicClient(baseURL: main, credentials: Credentials(username: "a", password: "b"),
                                    device: DeviceIdentity(id: "abc", name: "Phone"), appVersion: "1.0",
                                    scope: ServerScope(main: main, home: URL(string: "http://192.168.1.20:4533")!))
        #expect(isServerPicture(URL(string: "https://music.example.com/rest/getCoverArt?id=1")!, client: client, adminBase: nil))
        #expect(isServerPicture(URL(string: "http://192.168.1.20:4533/x.jpg")!, client: client, adminBase: nil))
        #expect(isServerPicture(base.appendingPathComponent("api/admin/downloads/cover"), client: client, adminBase: base))
        #expect(!isServerPicture(URL(string: "http://192.168.50.21:5275/x.jpg")!, client: client, adminBase: base))
        #expect(!isServerPicture(URL(string: "https://cdn.example.net/a.jpg")!, client: client, adminBase: base))
    }
}

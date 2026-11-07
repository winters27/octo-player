import Foundation
import Testing
@testable import OctoKit

/// Signing in to a kept server from its own form keeps that server: moved to https, it is still
/// one server under its id, with its sign-in page value.
@Suite struct KeepBaseTests {
    let basic = basicCredentials(username: "alice", password: "app password")
    let plain = URL(string: "http://music.example.com")!
    let secure = URL(string: "https://music.example.com")!

    var kept: StoredServer { StoredServer(id: "alice-plain", address: plain, username: "alice", headerNames: ["Authorization"]) }

    /// Red when `keepBase` stops building on the server the form was opened for, when
    /// `keptServerId` makes a new id for the same username, or when `serversAfterKeep` adds
    /// beside the server it built on.
    @Test func signingInAgainAtHttpsKeepsOneServerWithItsIdAndItsSignInPageValue() throws {
        let servers = [kept]
        #expect(keepBase(formFor: "alice-plain", address: secure, username: "alice", servers: servers) == .kept(kept))
        let id = keptServerId(before: kept, username: "alice", address: secure, taken: [])
        #expect(id == "alice-plain")
        let headers = resolveConnectionHeaders(rows: [], gateway: GatewayDraft(kind: .password, saved: .password),
                                               saved: ["Authorization": basic], mainHttps: true, mainPrivate: false)
        #expect(headers == .ready(["Authorization": basic]))
        let moved = StoredServer(id: id, address: secure, username: "alice", headerNames: ["Authorization"])
        let after = serversAfterKeep(servers, before: kept, now: moved)
        #expect(after.count == 1)
        #expect(after.first?.id == "alice-plain")
        #expect(after.first?.address == secure)
        // A new sign-in at the new address builds on nothing, which is why the form names its server.
        #expect(keepBase(formFor: nil, address: secure, username: "alice", servers: servers) == .new)
        #expect(serversAfterKeep(servers, before: nil, now: moved).count == 2)
    }

    /// Red when a gone server or another kept one with the same account is let through.
    @Test func aFormForAKeptServerThatIsGoneOrMeetsAnotherSaysSo() {
        let other = StoredServer(id: "alice-secure", address: secure, username: "alice")
        #expect(keepBase(formFor: "missing", address: secure, username: "alice", servers: [kept]) == .gone)
        #expect(keepBase(formFor: "alice-plain", address: secure, username: "alice", servers: [kept, other]) == .alreadyKept(other))
        #expect(keepBase(formFor: nil, address: secure, username: "alice", servers: [kept, other]) == .kept(other))
        #expect(keptServerId(before: kept, username: "bob", address: secure, taken: ["alice-plain"])
            == freshServerId(username: "bob", address: "https://music.example.com/", taken: ["alice-plain"]))
    }
}

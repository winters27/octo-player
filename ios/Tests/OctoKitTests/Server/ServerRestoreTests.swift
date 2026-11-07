import Foundation
import Testing
@testable import OctoKit

/// App start never signs in to a server whose sign-in page password would go over plain http
/// across the internet: its form asks for https instead, and the row of the server in use says so.
@Suite struct ServerRestoreTests {
    let basic = basicCredentials(username: "alice", password: "app password")
    let publicPlain = ServerScope(main: URL(string: "http://music.example.com")!, home: nil)
    let secure = ServerScope(main: URL(string: "https://music.example.com")!, home: nil)
    let lan = ServerScope(main: URL(string: "http://192.168.1.20:4533")!, home: nil)

    /// Red when `serverRestore` loses its `needsHttps` line.
    @Test func aServerThatNeedsHttpsIsNeverSignedInToAtStart() {
        #expect(serverRestore(signedOut: false, hasSecret: true, headers: ["Authorization": basic], scope: publicPlain) == .needsHttps)
        #expect(serverRestore(signedOut: false, hasSecret: true, headers: ["cf-access-client-id": "id"], scope: publicPlain) == .needsHttps)
        #expect(serverRestore(signedOut: false, hasSecret: true, headers: ["Authorization": basic], scope: secure) == .signIn)
        #expect(serverRestore(signedOut: false, hasSecret: true, headers: ["Authorization": basic], scope: lan) == .signIn)
        #expect(serverRestore(signedOut: false, hasSecret: true, headers: ["X-Proxy-Token": "p"], scope: publicPlain) == .signIn)
    }

    /// Red when the https question comes before a sign-out or a lost secret.
    @Test func aSignOutAndALostSecretComeFirst() {
        #expect(serverRestore(signedOut: true, hasSecret: true, headers: ["Authorization": basic], scope: publicPlain) == .leave)
        #expect(serverRestore(signedOut: true, hasSecret: false, headers: [:], scope: secure) == .leave)
        #expect(serverRestore(signedOut: false, hasSecret: false, headers: ["Authorization": basic], scope: publicPlain) == .signOut)
    }

    /// Red when `statusLine` hides the https need or a sign-in page's refusal for the server in
    /// use, or `warns(inUse:)` warns for anything else on it.
    @Test func theRowOfTheServerInUseShowsOnlyItsSignInPageTrouble() {
        #expect(statusLine(serverType: "octo", serverVersion: "0.9.3", signedOut: false, check: ServerCheck(.needsHttps), inUse: true)
            == "Octo 0.9.3 · \(GatewayWords.rowNeedsHttps)")
        #expect(statusLine(serverType: "octo", serverVersion: "0.9.3", signedOut: false, check: ServerCheck(.unreachable), inUse: true)
            == "Octo 0.9.3")
        #expect(statusLine(serverType: "octo", serverVersion: "0.9.3", signedOut: false,
                           check: ServerCheck(.signInPage(.authentik, loginSent: true)), inUse: true)
            == "Octo 0.9.3 · \(GatewayWords.row(.authentik, loginSent: true))")
        #expect(ServerCheck(.signInPage(.authentik, loginSent: true)).warns(inUse: true))
        #expect(ServerCheck(.signInPage(.authelia, loginSent: false)).warns(inUse: true))
        #expect(ServerCheck(.needsHttps).warns(inUse: true))
        #expect(!ServerCheck(.unreachable).warns(inUse: true))
        #expect(ServerCheck(.unreachable).warns(inUse: false))
        #expect(ServerCheck(.needsHttps).warns(inUse: false))
        #expect(!ServerCheck(.answers).warns(inUse: false))
    }
}

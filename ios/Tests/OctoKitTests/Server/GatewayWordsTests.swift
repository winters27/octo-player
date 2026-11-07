import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@testable import OctoKit

/// Every gateway word against the fixture the other apps test against too.
@Suite struct GatewayWordsTests {
    static func swiftWords() -> [String: String] {
        var words: [String: String] = [
            "label.section": GatewayWords.sectionLabel,
            "label.caption": GatewayWords.caption,
            "label.username": GatewayWords.usernameLabel,
            "label.password": GatewayWords.passwordLabel,
            "label.clientId": GatewayWords.clientIdLabel,
            "label.clientSecret": GatewayWords.clientSecretLabel,
            "label.saved": GatewayWords.savedLabel,
            "join.hint": GatewayWords.joinHint,
            "share.line": GatewayWords.shareLine,
            "play.failure": GatewayWords.playFailure,
            "needsHttps": GatewayWords.needsHttps,
            "row.needsHttps": GatewayWords.rowNeedsHttps,
            "problem.bothPassword": GatewayWords.bothPassword,
            "problem.bothToken": GatewayWords.bothToken,
            "problem.colon": GatewayWords.colon,
            "problem.control": GatewayWords.control,
            "problem.reservedRow": GatewayWords.reservedRow,
        ]
        for gateway in Gateway.allCases {
            words["name.\(gateway.rawValue)"] = GatewayWords.name(gateway)
            words["hint.\(gateway.rawValue)"] = GatewayWords.hint(gateway)
            for sent in [false, true] { words["failure.\(gateway.rawValue).\(sent)"] = GatewayWords.failure(gateway, loginSent: sent) }
            for sent in [false, true] { words["row.\(gateway.rawValue).\(sent)"] = GatewayWords.row(gateway, loginSent: sent) }
        }
        for kind in GatewayKind.allCases { words["label.kind.\(kind.rawValue)"] = GatewayWords.kindLabel(kind) }
        return words
    }

    @Test func everyWordMatchesTheSharedFixture() throws {
        let fixture = try JSONDecoder().decode([String: String].self, from: try gatewayFixture("words.json"))
        let words = Self.swiftWords()
        #expect(Set(words.keys) == Set(fixture.keys))
        for (key, text) in fixture { #expect(words[key] == text, "\(key)") }
    }

    @Test func wordsArePlainAsciiWithNoDashes() {
        for (key, text) in Self.swiftWords() {
            #expect(text.unicodeScalars.allSatisfy { $0.isASCII }, "\(key)")
            #expect(!text.contains("\u{2014}") && !text.contains("\u{2013}"), "\(key)")
        }
    }

    @Test func anErrorFromASignInPageIsToldInItsOwnWords() {
        #expect(GatewayWords.failureFor(SubsonicError.notSubsonic("HTTP 401 from ping", status: 401, gateway: .authelia, loginSent: true))
            == "Authelia didn't accept that username and password.")
        #expect(GatewayWords.failureFor(OctoError.http(403, gateway: .cloudflareAccess)) == GatewayWords.failure(.cloudflareAccess, loginSent: false))
        #expect(GatewayWords.failureFor(SubsonicError.notSubsonic("HTTP 500 from ping", status: 500)) == nil)
        #expect(GatewayWords.failureFor(URLError(.timedOut)) == nil)
        #expect(PlayFailure.signInPage.words == GatewayWords.playFailure)
        #expect(playFailureOf(.signInPage) == .signInPage)
    }
}

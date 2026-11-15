import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@testable import OctoKit

/// Every gateway word against the fixture the other apps test against too.
@Suite struct GatewayWordsTests {
    /// Every sign-in page met, and none.
    static let detected: [Gateway?] = [nil] + Gateway.allCases.map { Optional($0) }

    static func swiftWords() -> [String: String] {
        var words: [String: String] = [
            "label.section": GatewayWords.sectionLabel,
            "label.caption": GatewayWords.caption,
            "label.clientId": GatewayWords.clientIdLabel,
            "label.clientSecret": GatewayWords.clientSecretLabel,
            "pointer": GatewayWords.pointer,
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
        for kind in GatewayKind.allCases {
            words["label.kind.\(kind.rawValue)"] = GatewayWords.kindLabel(kind)
            if let helper = GatewayWords.kindHelper(kind) { words["label.helper.\(kind.rawValue)"] = helper }
            if let tip = GatewayWords.tip(kind) { words["tip.\(kind.rawValue)"] = tip }
        }
        for kind in [GatewayKind.password, .cloudflareToken] {
            for met in detected {
                let labels = GatewayWords.fieldLabels(kind, detected: met)
                let key = "field.\(kind.rawValue).\(met?.rawValue ?? "none")"
                words["\(key).first"] = labels.first
                words["\(key).second"] = labels.second
            }
        }
        return words
    }

    @Test func everyWordMatchesTheSharedFixture() throws {
        let fixture = try JSONDecoder().decode([String: String].self, from: try gatewayFixture("words.json"))
        let words = Self.swiftWords()
        #expect(Set(words.keys) == Set(fixture.keys))
        for (key, text) in fixture { #expect(words[key] == text, "\(key)") }
    }

    /// Plain ASCII with no dashes. The one exception is in the words `glued` lists: a no-break
    /// space, which keeps a wrapped line's last two words together, or a word joiner, which keeps
    /// "Wi-Fi" and "/rest/" whole.
    @Test func wordsArePlainAsciiWithNoDashes() {
        let glued: Set<String> = ["pointer", "failure.SignInPage.false", "hint.Authentik", "hint.CloudflareAccess", "hint.SignInPage",
                                  "tip.CloudflareToken"]
        for (key, text) in Self.swiftWords() {
            #expect(text.unicodeScalars.allSatisfy { $0.isASCII || (glued.contains(key) && [0xA0, 0x2060].contains($0.value)) }, "\(key)")
            #expect(!text.contains("\u{2014}") && !text.contains("\u{2013}"), "\(key)")
        }
    }

    /// Red when a sign-in page field row reads like the server's own username or password again.
    @Test func noSignInPageFieldIsNamedLikeTheServersOwnLogin() {
        for kind in GatewayKind.allCases {
            for met in Self.detected {
                let labels = GatewayWords.fieldLabels(kind, detected: met)
                for label in [labels.first, labels.second] {
                    #expect(label != SignInWords.username && label != SignInWords.password, "\(kind) \(String(describing: met))")
                }
            }
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

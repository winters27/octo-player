import Foundation
import Testing
@testable import OctoKit

/// Every sign-in word against the fixture the phone and desktop apps test against too. Templates are
/// filled with `{name}` (and `{date}`), so the fixture shows where a name goes.
@Suite struct SignInWordsTests {
    /// Each way of signing in with the name the other apps give it in the fixture.
    static let modes: [(mode: AuthMode, name: String)] = [(.token, "Token"), (.legacyPassword, "LegacyPassword"), (.apiKey, "ApiKey")]

    static func swiftWords() -> [String: String] {
        var words: [String: String] = [
            "advanced": SignInWords.advanced,
            "advanced.line": SignInWords.advancedLine,
            "group.gettingThere": SignInWords.groupGettingThere,
            "group.signingIn": SignInWords.groupSigningIn,
            "group.specialSetups": SignInWords.groupSpecialSetups,
            "row.serverAddress": SignInWords.serverAddress,
            "row.serverName": SignInWords.serverName,
            "row.username": SignInWords.username,
            "row.password": SignInWords.password,
            "row.apiKey": SignInWords.apiKey,
            "row.yourName": SignInWords.yourName,
            "row.passwordAgain": SignInWords.passwordAgain,
            "row.homeAddress": SignInWords.homeAddress,
            "row.method": SignInWords.method,
            "row.customHeaders": SignInWords.customHeaders,
            "row.headerName": SignInWords.headerName,
            "row.headerValue": SignInWords.headerValue,
            "addHeader": SignInWords.addHeader,
            "removeHeader": SignInWords.removeHeader("{name}"),
            "removeHeader.blank": SignInWords.removeHeader(""),
            "placeholder.required": SignInWords.required,
            "placeholder.unchanged": SignInWords.unchanged,
            "placeholder.home": SignInWords.homePlaceholder,
            "placeholder.name": SignInWords.namePlaceholder,
            "tag.optional": SignInWords.optionalTag,
            "tag.optional.name": SignInWords.optionalName("{name}"),
            "note.home": SignInWords.homeNote,
            "description.headers": SignInWords.headersDescription,
            "note.apiKeyInTheOpen": SignInWords.apiKeyInTheOpen,
            "method.helper.ApiKey.known": SignInWords.methodHelper(.apiKey, takesApiKeys: true),
            "error.unreachable": SignInWords.unreachable,
            "error.restarting": SignInWords.restarting,
            "error.notMusic": SignInWords.notMusic,
            "error.wrongPassword": SignInWords.wrongPassword,
            "error.wayRefused": SignInWords.wayRefused,
            "error.somethingWrong": SignInWords.somethingWrong,
            "error.badAddress": SignInWords.badAddress,
            "error.badHomeAddress": SignInWords.badHomeAddress,
            "error.missingSecret": SignInWords.missingSecret,
            "error.gone": SignInWords.gone,
            "error.alreadyKept": SignInWords.alreadyKept("{name}"),
            "legacy.hint": SignInWords.legacyHint,
            "legacy.unsafe": SignInWords.legacyUnsafe,
            "cert.title": SignInWords.certTitle,
            "cert.none": SignInWords.certNone,
            "cert.choose": SignInWords.certChoose,
            "cert.change": SignInWords.certChange,
            "cert.remove": SignInWords.certRemove,
            "cert.line": SignInWords.certificateLine("{name}", date: "{date}"),
            "cert.password.title": SignInWords.certPasswordTitle,
            "cert.password.hint": SignInWords.certPasswordHint,
            "cert.password.field": SignInWords.certPasswordField,
            "cert.password.use": SignInWords.certPasswordUse,
            "cert.asked": SignInWords.certAsked,
            "cert.refused": SignInWords.certRefused,
            "cert.keyKind": SignInWords.certKeyKind,
            "cert.wrongPassword": SignInWords.certWrongPassword,
            "cert.notAFile": SignInWords.certNotAFile,
            "cert.noKey": SignInWords.certNoKey,
            "cert.notForClients": SignInWords.certNotForClients,
            "cert.expired": SignInWords.certExpired,
            "cert.notYetValid": SignInWords.certNotYetValid,
            "cert.tooBig": SignInWords.certTooBig,
            "link.whileBusy": SignInWords.linkWhileBusy,
            "link.forAnotherAccount": SignInWords.linkForAnotherAccount,
            "link.forAnotherServer": SignInWords.linkForAnotherServer,
            "link.codeElsewhere": SignInWords.codeElsewhere,
            "link.afterAddressChange": SignInWords.linkAfterAddressChange,
            "link.whileSignInOpen": SignInWords.linkWhileSignInOpen,
            "link.askWhileSignInOpen": SignInWords.askWhileSignInOpen("{name}"),
            "trust.title": SignInWords.trustTitle,
            "trust.changed.title": SignInWords.trustChangedTitle,
            "trust.host": SignInWords.trustHost,
            "trust.fingerprint": SignInWords.trustFingerprint,
            "trust.confirm": SignInWords.trustConfirm,
            "trust.refused": SignInWords.notTrusted,
            "trust.detail": SignInWords.trustDetail("{device}"),
            "trust.changed.detail": SignInWords.trustChangedDetail,
        ]
        for (mode, name) in modes {
            words["method.\(name)"] = SignInWords.methodLabel(mode)
            words["method.helper.\(name)"] = SignInWords.methodHelper(mode, takesApiKeys: nil)
        }
        for gateway in Gateway.allCases {
            for sent in [false, true] {
                words["inGroup.\(gateway.rawValue).\(sent)"] = GatewayWords.inGroup(GatewayWords.failure(gateway, loginSent: sent))
            }
        }
        return words
    }

    @Test func everyWordMatchesTheSharedFixture() throws {
        let fixture = try JSONDecoder().decode([String: String].self, from: try gatewayFixture("signin-words.json"))
        let words = Self.swiftWords()
        #expect(Set(words.keys) == Set(fixture.keys))
        for (key, text) in fixture { #expect(words[key] == text, "\(key)") }
    }

    /// Shared words are plain ASCII, so no dash or curly quote slips in. The one exception is in the
    /// words `glued` lists: a no-break space, which keeps a wrapped line's last two words together,
    /// or a word joiner, which keeps "Wi-Fi" whole.
    @Test func everyWordIsPlainAscii() {
        let glued: Set<String> = ["advanced.line", "note.home"]
        for (key, text) in Self.swiftWords() {
            let mayGlue = glued.contains(key) || key.hasPrefix("inGroup.")
            #expect(text.unicodeScalars.allSatisfy { (0x20...0x7E).contains($0.value) || (mayGlue && [0xA0, 0x2060].contains($0.value)) }, "\(key)")
        }
    }

    /// A row from Add header starts blank; its Remove button still has a name.
    @Test func aBlankHeaderNameHasItsOwnRemoveName() {
        #expect(SignInWords.removeHeader("   ") == "Remove header")
        #expect(SignInWords.removeHeader(" X-Token ") == "Remove header X-Token")
    }

    /// The API key's line says what the server said about keys, once known.
    @Test func theApiKeyLineFollowsWhatTheServerSaid() {
        #expect(SignInWords.methodHelper(.apiKey, takesApiKeys: nil) == SignInWords.methodHelper(.apiKey, takesApiKeys: false))
        #expect(SignInWords.methodHelper(.apiKey, takesApiKeys: nil) != SignInWords.methodHelper(.apiKey, takesApiKeys: true))
    }

    /// Red when the group's error note sends the person "under Advanced", where they already are,
    /// or when inGroup rewrites a refusal's words or the words every other screen shows.
    @Test func theGroupsErrorNoteNeverSendsThePersonUnderAdvanced() {
        for gateway in Gateway.allCases {
            #expect(!GatewayWords.inGroup(GatewayWords.failure(gateway, loginSent: false)).contains("under Advanced"), "\(gateway)")
            #expect(GatewayWords.inGroup(GatewayWords.failure(gateway, loginSent: true)) == GatewayWords.failure(gateway, loginSent: true))
            #expect(GatewayWords.failure(gateway, loginSent: false).hasSuffix("fields under Advanced."), "\(gateway)")
        }
    }
}

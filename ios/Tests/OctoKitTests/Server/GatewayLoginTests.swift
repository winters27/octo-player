import Foundation
import Testing
@testable import OctoKit

/// The sign-in page fields: the Basic header, the kind read from saved headers, and every row of
/// the resolve table.
@Suite struct GatewayLoginTests {
    let basicAlice = "Basic YWxpY2U6cMOkc3N3b3Jk"

    func resolve(_ rows: [HeaderRules.Row], _ draft: GatewayDraft, saved: [String: String] = [:], https: Bool = true,
                 isPrivate: Bool = false) -> ResolvedHeaders {
        resolveConnectionHeaders(rows: rows, gateway: draft, saved: saved, mainHttps: https, mainPrivate: isPrivate)
    }

    func resolve(_ draft: GatewayDraft, saved: [String: String] = [:], https: Bool = true,
                 isPrivate: Bool = false) -> ResolvedHeaders {
        resolve([], draft, saved: saved, https: https, isPrivate: isPrivate)
    }

    @Test func basicIsTheNfcBytesWhateverFormTheyWereTypedIn() {
        let composed = basicCredentials(username: "alice", password: "p\u{E4}ssword")
        let decomposed = basicCredentials(username: "alice", password: "pa\u{308}ssword")
        #expect(Array(composed.utf8) == Array(basicAlice.utf8))
        #expect(Array(decomposed.utf8) == Array(basicAlice.utf8))
        let sent = Data(base64Encoded: String(decomposed.dropFirst("Basic ".count))).map { Array($0) }
        #expect(sent == [0x61, 0x6C, 0x69, 0x63, 0x65, 0x3A, 0x70, 0xC3, 0xA4, 0x73, 0x73, 0x77, 0x6F, 0x72, 0x64])
    }

    @Test func theSavedKindIsReadFromTheValues() {
        #expect(savedGatewayKind(["Authorization": "Basic YQ=="]) == .password)
        #expect(savedGatewayKind(["authorization": "basic YQ=="]) == .password)
        #expect(savedGatewayKind(["Authorization": "Bearer t"]) == .none)
        #expect(savedGatewayKind(["CF-Access-Client-Id": "i", "cf-access-client-secret": "s", "Authorization": "Basic YQ=="]) == .cloudflareToken)
        #expect(savedGatewayKind(["CF-Access-Client-Id": "i"]) == .none)
        #expect(savedGatewayNames(["Authorization": "Basic YQ==", "X-Proxy": "p"], kind: .password) == ["Authorization"])
        #expect(savedGatewayNames(["CF-Access-Client-Id": "i", "cf-access-client-secret": "s", "Authorization": "Basic YQ=="], kind: .cloudflareToken)
            == ["CF-Access-Client-Id", "cf-access-client-secret"])
        #expect(savedGatewayNames(["Authorization": "Bearer t"], kind: .none).isEmpty)
    }

    @Test func noneSendsNoSignInPageHeaders() {
        #expect(resolve(GatewayDraft(kind: .none, saved: .password), saved: ["Authorization": "Basic YQ=="]) == .ready([:]))
        #expect(resolve([HeaderRules.Row(name: "X-Proxy", value: "p", saved: false)], GatewayDraft()) == .ready(["X-Proxy": "p"]))
    }

    @Test func theSavedKindLeftEmptyKeepsItsValues() {
        #expect(resolve(GatewayDraft(kind: .password, saved: .password), saved: ["Authorization": "Basic YQ==", "X-Proxy": "p"])
            == .ready(["Authorization": "Basic YQ=="]))
        let token = ["CF-Access-Client-Id": "i", "CF-Access-Client-Secret": "s"]
        #expect(resolve(GatewayDraft(kind: .cloudflareToken, saved: .cloudflareToken), saved: token) == .ready(token))
    }

    @Test func anUnchangedEditOfAServerSavedBeforeThisVersionSaves() {
        let saved = ["Authorization": "Basic YQ==", "X-Proxy": "p"]
        let kind = savedGatewayKind(saved)
        let shown = saved.keys.filter { !savedGatewayNames(saved, kind: kind).contains($0) }.map { HeaderRules.Row(name: $0, value: "", saved: true) }
        #expect(resolve(shown, GatewayDraft(kind: kind, saved: kind), saved: saved) == .ready(saved))
    }

    @Test func bothFieldsMakeTheHeaders() {
        #expect(resolve(GatewayDraft(kind: .password, first: " alice ", second: "p\u{E4}ssword")) == .ready(["Authorization": basicAlice]))
        #expect(resolve(GatewayDraft(kind: .cloudflareToken, first: "id.access", second: "secret"))
            == .ready(["CF-Access-Client-Id": "id.access", "CF-Access-Client-Secret": "secret"]))
    }

    @Test func oneFieldOrANewKindLeftEmptyIsAProblem() {
        #expect(resolve(GatewayDraft(kind: .password, first: "alice")) == .problem(GatewayWords.bothPassword))
        #expect(resolve(GatewayDraft(kind: .password)) == .problem(GatewayWords.bothPassword))
        #expect(resolve(GatewayDraft(kind: .cloudflareToken, second: "secret")) == .problem(GatewayWords.bothToken))
        #expect(resolve(GatewayDraft(kind: .cloudflareToken, saved: .password), saved: ["Authorization": "Basic YQ=="])
            == .problem(GatewayWords.bothToken))
    }

    @Test func aColonOrAControlCharacterIsAProblem() {
        #expect(resolve(GatewayDraft(kind: .password, first: "al:ice", second: "pw")) == .problem(GatewayWords.colon))
        #expect(resolve(GatewayDraft(kind: .password, first: "alice", second: "p\nw")) == .problem(GatewayWords.control))
        #expect(resolve(GatewayDraft(kind: .cloudflareToken, first: "id\u{E9}", second: "s")) == .problem(GatewayWords.control))
    }

    @Test func aRowUsingTheChosenKindsNameIsAProblem() {
        let row = HeaderRules.Row(name: "authorization", value: "Bearer t", saved: false)
        #expect(resolve([row], GatewayDraft(kind: .password, first: "alice", second: "pw")) == .problem(GatewayWords.reservedRow))
        // Another kind's name is a row of its own, as Cloudflare in front of Authentik needs.
        #expect(resolve([row], GatewayDraft(kind: .cloudflareToken, first: "i", second: "s"))
            == .ready(["authorization": "Bearer t", "CF-Access-Client-Id": "i", "CF-Access-Client-Secret": "s"]))
    }

    @Test func plainHttpAcrossTheInternetIsRefusedAndAtHomeAllowed() {
        let draft = GatewayDraft(kind: .password, first: "alice", second: "pw")
        #expect(resolve(draft, https: false) == .problem(GatewayWords.needsHttps))
        #expect(resolve([HeaderRules.Row(name: "Authorization", value: "Basic YQ==", saved: false)], GatewayDraft(), https: false)
            == .problem(GatewayWords.needsHttps))
        #expect(resolve(draft, https: false, isPrivate: true) == .ready(["Authorization": basicCredentials(username: "alice", password: "pw")]))
        #expect(gatewayAllowed(mainHttps: true, mainPrivate: false))
        #expect(!gatewayAllowed(mainHttps: false, mainPrivate: false))
    }

    @Test func aSavedRowLeftEmptyKeepsItsValue() {
        #expect(resolve([HeaderRules.Row(name: "x-proxy", value: "", saved: true)], GatewayDraft(), saved: ["X-Proxy": "p"]) == .ready(["x-proxy": "p"]))
    }

    @Test func theDraftIsNeverPrinted() {
        #expect(!GatewayDraft(kind: .password, first: "alice", second: "hunter2").description.contains("hunter2"))
    }
}

/// How the form reacts to a sign-in page, across attempts.
@Suite struct GatewayReactionTests {
    let none = GatewayFormState()
    let here = "https://music.example.com"

    @Test func aGatewayOpensAdvancedAndPicksTheKindOnlyFromNone() {
        #expect(reactToFailure(none, attemptAddress: here, currentAddress: here, gateway: .authentik)
            == GatewayFormState(advancedOpen: true, kind: .password, detected: .authentik, detectedFor: here, callout: true, calloutPending: true))
        #expect(reactToFailure(none, attemptAddress: here, currentAddress: here, gateway: .cloudflareAccess).kind == .cloudflareToken)
        let picked = GatewayFormState(kind: .cloudflareToken)
        #expect(reactToFailure(picked, attemptAddress: here, currentAddress: here, gateway: .authelia).kind == .cloudflareToken)
        #expect(reactToFailure(none, attemptAddress: here, currentAddress: here, gateway: nil) == none)
    }

    @Test func theMostSpecificGatewayIsKept() {
        let seen = reactToFailure(none, attemptAddress: here, currentAddress: here, gateway: .authentik)
        #expect(reactToFailure(seen, attemptAddress: here, currentAddress: here, gateway: .passwordPrompt).detected == .authentik)
        #expect(reactToFailure(seen, attemptAddress: here, currentAddress: here, gateway: .signInPage).detected == .authentik)
        let prompt = reactToFailure(none, attemptAddress: here, currentAddress: here, gateway: .passwordPrompt)
        #expect(reactToFailure(prompt, attemptAddress: here, currentAddress: here, gateway: .authelia).detected == .authelia)
        #expect(moreSpecific(.signInPage, .passwordPrompt) == .passwordPrompt)
        #expect(moreSpecific(.authentik, .cloudflareAccess) == .cloudflareAccess)
        #expect(moreSpecific(.authelia, nil) == .authelia)
        #expect(moreSpecific(nil, nil) == nil)
    }

    @Test func aResultForAnAddressNoLongerTypedIsDropped() {
        #expect(reactToFailure(none, attemptAddress: "https://old.example.com", currentAddress: here, gateway: .authentik) == none)
    }

    @Test func anotherAddressForgetsTheGateway() {
        let seen = reactToFailure(none, attemptAddress: here, currentAddress: here, gateway: .authentik)
        #expect(addressChanged(seen, address: " \(here) ") == seen)
        let moved = addressChanged(seen, address: "https://other.example.com")
        #expect(moved == GatewayFormState(advancedOpen: true, kind: .password, detected: nil, detectedFor: nil))
    }
}

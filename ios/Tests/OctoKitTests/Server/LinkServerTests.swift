import Foundation
import Testing
@testable import OctoKit

/// A family link's way into a form, against the vectors the phone and desktop apps run too.
@Suite struct LinkServerTests {
    struct Vectors: Decodable {
        let linkForm: [LinkCase]
        let norm: [NormRow]
        let linkHome: [HomeRow]
        let signInOpen: [OpenRow]
    }

    struct LinkRow: Decodable {
        let server: String
        let base: String?
        let home: String?
        let username: String?
        let invite: Bool
    }

    struct SignInRow: Decodable {
        let server: String
        let home: String?
        let username: String
    }

    struct Shown: Decodable, Equatable {
        let action: String
        let why: String?
        let address: String?
        let home: String?
        let useForm: Bool?
    }

    struct StateRow: Decodable {
        let anchored: Bool
        let anchorEnded: Bool
        let hadAddress: Bool
        let taken: [String]
        let typed: [String]
        let note: String?
    }

    struct Step: Decodable {
        let op: String
        let address: String?
        let home: String?
        let nonBlank: Bool?
        let main: String?
        let username: String?
        let link: LinkRow?
        let busy: Bool?
        let signIn: SignInRow?
        let words: String?
        let expect: Shown?
        let state: StateRow
    }

    struct LinkCase: Decodable {
        let name: String
        let kind: String
        let steps: [Step]
    }

    struct NormRow: Decodable {
        let address: String
        let expect: String?
    }

    struct HomeRow: Decodable {
        let forget: Bool
        let home: String
        let linkHome: String?
        let expect: String
    }

    struct OpenRow: Decodable {
        let busy: Bool
        let question: Bool
        let holdsReceived: Bool
        let holdsSignUp: Bool
        let expect: Bool
    }

    static func vectors() throws -> Vectors {
        try JSONDecoder().decode(Vectors.self, from: try gatewayFixture("signin-vectors.json"))
    }

    /// An action as the vectors write it.
    static func shown(_ action: LinkAction) -> Shown {
        switch action {
        case .refuse(let why): Shown(action: "Refuse", why: why.rawValue, address: nil, home: nil, useForm: nil)
        case .take(.fitting, _, _, _): Shown(action: "Fitting", why: nil, address: nil, home: nil, useForm: nil)
        case .take(let kind, let address, let home, _): Shown(action: kind.rawValue, why: nil, address: address, home: home, useForm: nil)
        }
    }

    static func shown(_ action: ReceivedAction) -> Shown {
        switch action {
        case .keep(let useForm): Shown(action: "Keep", why: nil, address: nil, home: nil, useForm: useForm)
        case .refuse(let why): Shown(action: "Refuse", why: why.rawValue, address: nil, home: nil, useForm: nil)
        case .forgetAndAdopt(let address, let home, _): Shown(action: "ForgetAndAdopt", why: nil, address: address, home: home, useForm: nil)
        }
    }

    @Test func everyLinkStepGivesItsActionAndState() throws {
        let cases = try Self.vectors().linkForm
        #expect(cases.count >= 50)
        for item in cases {
            var state = LinkFormState(try #require(LinkForm(rawValue: item.kind)))
            var taking: LinkAction?
            var got: ReceivedAction?
            for (index, step) in item.steps.enumerated() {
                let place = "\(item.name), step \(index + 1) (\(step.op))"
                switch step.op {
                case "edited":
                    state.edited(address: step.address, home: step.home, addressNonBlank: try #require(step.nonBlank))
                case "filled":
                    state.filled(LinkAnchor(main: try #require(step.main), home: step.home, username: try #require(step.username)))
                case "take":
                    let link = try #require(step.link)
                    let action = state.take(LinkFacts(server: link.server, base: link.base, home: link.home, username: link.username, invite: link.invite),
                                            busy: try #require(step.busy), home: try #require(step.home))
                    #expect(Self.shown(action) == step.expect, "\(place)")
                    taking = action
                case "took":
                    state.took(try #require(taking))
                case "received":
                    let signIn = try #require(step.signIn)
                    let action = state.received(ReceivedFacts(server: signIn.server, home: signIn.home, username: signIn.username))
                    #expect(Self.shown(action) == step.expect, "\(place)")
                    got = action
                case "adopted":
                    state.adopted(try #require(got))
                case "left":
                    state.left()
                case "noted":
                    state.noted(try #require(step.words))
                default:
                    Issue.record("\(place): no such op")
                }
                #expect((state.anchor != nil) == step.state.anchored, "\(place): anchored")
                #expect(state.anchorEnded == step.state.anchorEnded, "\(place): anchorEnded")
                #expect(state.hadAddress == step.state.hadAddress, "\(place): hadAddress")
                #expect(state.taken == step.state.taken, "\(place): taken")
                #expect(state.typed == step.state.typed, "\(place): typed")
                #expect(state.note == step.state.note, "\(place): note")
            }
        }
    }

    @Test func addressesAreComparedInOneForm() throws {
        let rows = try Self.vectors().norm
        #expect(rows.count >= 25)
        for row in rows { #expect(norm(row.address) == row.expect, "\(row.address)") }
    }

    @Test func aTakenLinkWritesTheHomeItNames() throws {
        for row in try Self.vectors().linkHome {
            #expect(linkHome(forget: row.forget, home: row.home, linkHome: row.linkHome) == row.expect, "\(row)")
        }
    }

    @Test func aSignInIsOpenWhileAnythingOfItWorksOnlyOnce() throws {
        let rows = try Self.vectors().signInOpen
        #expect(rows.count >= 6)
        for row in rows {
            #expect(signInOpen(busy: row.busy, question: row.question, holdsReceived: row.holdsReceived, holdsSignUp: row.holdsSignUp) == row.expect, "\(row)")
        }
    }

    @Test func eachKindOfLinkNamesWhatItCarries() {
        #expect(FamilyLink.invite(FamilyInviteLink(server: "https://music.example.com", token: "t", home: "http://music.lan")).facts
            == LinkFacts(server: "https://music.example.com", home: "http://music.lan", invite: true))
        #expect(FamilyLink.prefill(FamilySignInPrefill(server: "https://music.example.com", username: " ")).facts
            == LinkFacts(server: "https://music.example.com"))
        #expect(FamilyLink.prefill(FamilySignInPrefill(server: "https://music.example.com", username: "alex")).facts
            == LinkFacts(server: "https://music.example.com", username: "alex"))
        let handOver = FamilyHandOverLink(base: "http://192.168.1.20:4533", token: "t", key: "k", server: "https://music.example.com",
                                          home: "http://192.168.1.20:4533", username: "alex")
        #expect(FamilyLink.handOver(handOver).facts
            == LinkFacts(server: "https://music.example.com", base: "http://192.168.1.20:4533", home: "http://192.168.1.20:4533", username: "alex"))
        #expect(HandOverSignIn(username: "alex", secret: "s", server: "https://music.example.com", home: " ").facts.home == nil)
        #expect(HandOverSignIn(username: "alex", secret: "s", server: "https://music.example.com", home: "http://192.168.1.20:4533").facts.home
            == "http://192.168.1.20:4533")
    }

    @Test func everyRefusalSaysItInTheSharedWords() {
        #expect([LinkRefusal.busy, .anotherAccount, .anotherServer, .codeElsewhere, .addressChanged].map(\.words)
            == [SignInWords.linkWhileBusy, SignInWords.linkForAnotherAccount, SignInWords.linkForAnotherServer, SignInWords.codeElsewhere,
                SignInWords.linkAfterAddressChange])
    }
}

/// The iPhone's forget, as behavior: every field the forget names comes back cleared.
@Suite struct LinkFieldsTests {
    private func full(username: String = "alex") -> LinkFields {
        var headers = HeaderList.empty
        headers.replaced([.init(name: "Authorization", value: "", saved: true), .init(name: "X-Token", value: "abc", saved: false)])
        return LinkFields(headers: headers, gatewayKind: .password, gatewaySaved: .password, gatewayFirst: "alex", gatewaySecond: "pw",
                          legacy: true, useApiKey: true, apiKey: "key", password: "secret", joinName: "Alex", joinPassword: "long enough",
                          joinAgain: "long enough", username: username, takesApiKeys: true, certificatePicked: true)
    }

    @Test func aLinkThatNamesNoUsernameLeavesNothingOfTheLastServer() {
        let out = full().forgotten(linkUsername: nil)
        #expect(out.headers.rows.isEmpty)
        #expect(out.gatewayKind == .none && out.gatewaySaved == .none)
        #expect(out.gatewayFirst.isEmpty && out.gatewaySecond.isEmpty)
        #expect(!out.legacy && !out.useApiKey && out.apiKey.isEmpty)
        #expect(out.password.isEmpty)
        #expect(out.joinName.isEmpty && out.joinPassword.isEmpty && out.joinAgain.isEmpty)
        #expect(out.username.isEmpty)
        #expect(out.takesApiKeys == nil)
        #expect(!out.certificatePicked)
        #expect(full().forgotten(linkUsername: "   ").username.isEmpty)
    }

    @Test func aLinkThatNamesAUsernameKeepsTheFieldForItToFill() {
        let out = full().forgotten(linkUsername: "sam")
        #expect(out.username == "alex")
        #expect(out == full().forgotten(linkUsername: nil).withUsername("alex"))
    }
}

private extension LinkFields {
    func withUsername(_ name: String) -> LinkFields {
        var out = self
        out.username = name
        return out
    }
}

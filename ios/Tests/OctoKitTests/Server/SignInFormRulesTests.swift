import Foundation
import Testing
@testable import OctoKit

/// The sign-in form's rules against the vectors the phone and desktop apps run too, and the ids
/// beside the header rows.
@Suite struct SignInFormRulesTests {
    struct Vectors: Decodable {
        let gatewayForm: [FormCase]
        let authModeOf: [ModeRow]
        let flagsOf: [FlagsRow]
        let methodsShown: [ShownRow]
        let keeps: [KeepsRow]
        let advancedLayout: [LayoutRow]
        let headerList: [HeaderCase]
        let savedHeaderValues: [SavedValueCase]
    }

    struct FormState: Decodable {
        let advancedOpen: Bool
        let kind: String
        let detected: String?
        let detectedFor: String?
        let callout: Bool
        let calloutPending: Bool
        let keyPending: Bool
    }

    struct FormStep: Decodable {
        let op: String
        let attempt: String?
        let current: String?
        let gateway: String?
        let address: String?
        let kind: String?
        let from: String?
        let to: String?
        let keepsKey: Bool?
        let expect: FormState
    }

    struct FormCase: Decodable {
        let name: String
        let start: FormState
        let steps: [FormStep]
    }

    struct ModeRow: Decodable {
        let legacy: Bool
        let apiKey: Bool
        let expect: String
    }

    struct FlagsRow: Decodable {
        let mode: String
        let legacy: Bool
        let apiKey: Bool
    }

    struct ShownRow: Decodable {
        let takesApiKeys: Bool?
        let apiKeyOn: Bool
        let expect: [String]
    }

    struct KeepsRow: Decodable {
        let keepsSecret: Bool
        let savedMode: String?
        let mode: String
        let password: Bool
        let key: Bool
    }

    struct GroupRow: Decodable {
        let group: String
        let rows: [String]
    }

    struct LayoutExpect: Decodable {
        let groups: [GroupRow]
        let methods: [String]
    }

    struct LayoutRow: Decodable {
        let name: String
        let joining: Bool
        let takesApiKeys: Bool?
        let apiKeyOn: Bool
        let kind: String
        let certificateRow: Bool
        let expect: LayoutExpect
    }

    struct Row: Decodable, Equatable {
        let name: String
        let value: String
        let saved: Bool
    }

    struct HeaderStep: Decodable {
        let op: String
        let rows: [Row]?
        let row: Int?
        let removedIn: Int?
        let to: String?
        let expect: [Row]
    }

    struct HeaderCase: Decodable {
        let name: String
        let steps: [HeaderStep]
    }

    struct SavedValueCase: Decodable {
        let name: String
        let rows: [Row]
        let saved: [String: String]
        let expect: [String: String]
    }

    static func vectors() throws -> Vectors {
        try JSONDecoder().decode(Vectors.self, from: try gatewayFixture("signin-vectors.json"))
    }

    /// Each way of signing in by the name the other apps give it.
    static func mode(_ name: String?) throws -> AuthMode {
        switch try #require(name) {
        case "Token": return .token
        case "LegacyPassword": return .legacyPassword
        case "ApiKey": return .apiKey
        case let other: throw FixtureMissing(name: "mode \(other)")
        }
    }

    static func state(_ s: FormState) throws -> GatewayFormState {
        GatewayFormState(advancedOpen: s.advancedOpen, kind: try #require(GatewayKind(rawValue: s.kind)),
                         detected: try s.detected.map { try #require(Gateway(rawValue: $0)) }, detectedFor: s.detectedFor,
                         callout: s.callout, calloutPending: s.calloutPending, keyPending: s.keyPending)
    }

    @Test func everyFormStepGivesItsState() throws {
        let cases = try Self.vectors().gatewayForm
        #expect(cases.count >= 12)
        for item in cases {
            var now = try Self.state(item.start)
            for (index, step) in item.steps.enumerated() {
                switch step.op {
                case "reactToFailure":
                    now = reactToFailure(now, attemptAddress: try #require(step.attempt), currentAddress: try #require(step.current),
                                         gateway: try step.gateway.map { try #require(Gateway(rawValue: $0)) })
                case "addressChanged": now = addressChanged(now, address: try #require(step.address))
                case "gatewayEdited": now = gatewayEdited(now)
                case "kindPicked": now = kindPicked(now, kind: try #require(step.kind.flatMap(GatewayKind.init(rawValue:))))
                case "calm": now = calm(now)
                case "sending": now = sending(now)
                case "calloutShown": now = calloutShown(now)
                case "pointTo": now = pointTo(now)
                case "methodPicked":
                    now = methodPicked(now, oldMode: try Self.mode(step.from), newMode: try Self.mode(step.to), keepsKey: try #require(step.keepsKey))
                case "keyShown": now = keyShown(now)
                default: Issue.record("\(item.name): no op \(step.op)")
                }
                let expected = try Self.state(step.expect)
                #expect(now == expected, "\(item.name), step \(index + 1) (\(step.op))")
            }
        }
    }

    @Test func theMethodAndItsFlagsReadBothWays() throws {
        let vectors = try Self.vectors()
        #expect(vectors.authModeOf.count == 4)
        for row in vectors.authModeOf {
            let expected = try Self.mode(row.expect)
            #expect(authModeOf(legacy: row.legacy, apiKey: row.apiKey) == expected, "\(row)")
        }
        #expect(vectors.flagsOf.count == 3)
        for row in vectors.flagsOf {
            let flags = flagsOf(try Self.mode(row.mode))
            #expect(flags.legacy == row.legacy && flags.apiKey == row.apiKey, "\(row)")
        }
    }

    @Test func theMethodsOfferedFollowWhatTheServerSaid() throws {
        for row in try Self.vectors().methodsShown {
            let expected = try row.expect.map { try Self.mode($0) }
            #expect(methodsShown(takesApiKeys: row.takesApiKeys, apiKeyOn: row.apiKeyOn) == expected, "\(row)")
        }
    }

    @Test func aSavedSecretStandsInOnlyForItsOwnKind() throws {
        let rows = try Self.vectors().keeps
        #expect(rows.count >= 10)
        for row in rows {
            let saved = try row.savedMode.map { try Self.mode($0) }
            let mode = try Self.mode(row.mode)
            #expect(keepsPassword(keepsSecret: row.keepsSecret, savedMode: saved, mode: mode) == row.password, "\(row) password")
            #expect(keepsKey(keepsSecret: row.keepsSecret, savedMode: saved, mode: mode) == row.key, "\(row) key")
        }
    }

    @Test func advancedIsLaidOutInOneOrderEverywhere() throws {
        for row in try Self.vectors().advancedLayout {
            let groups = try row.expect.groups.map { group in
                LayoutGroup(group: try #require(AdvancedGroup(rawValue: group.group)),
                            rows: try group.rows.map { try #require(AdvancedRow(rawValue: $0)) })
            }
            let expected = AdvancedLayout(groups: groups, methods: try row.expect.methods.map { try Self.mode($0) })
            let layout = advancedLayout(joining: row.joining, takesApiKeys: row.takesApiKeys, apiKeyOn: row.apiKeyOn,
                                        kind: try #require(GatewayKind(rawValue: row.kind)), certificateRow: row.certificateRow)
            #expect(layout == expected, "\(row.name)")
        }
    }

    @Test func theGroupsCarryTheirTitlesInOrder() {
        #expect(AdvancedGroup.allCases.map(\.title) == [SignInWords.groupGettingThere, SignInWords.groupSigningIn, SignInWords.groupSpecialSetups])
    }

    /// Every header step by the rows' names, values and saved marks; after every step the ids are
    /// all different.
    @Test func everyHeaderStepGivesItsRows() throws {
        let cases = try Self.vectors().headerList
        #expect(cases.count >= 8)
        for item in cases {
            var list = HeaderList.empty
            var removed: [Int: UUID] = [:]
            for (index, step) in item.steps.enumerated() {
                let id: UUID? = step.row.map { list.rows[$0].id } ?? step.removedIn.flatMap { removed[$0] }
                switch step.op {
                case "replaced": list.replaced((step.rows ?? []).map { HeaderRules.Row(name: $0.name, value: $0.value, saved: $0.saved) })
                case "added": list.added()
                case "renamed": list.renamed(try #require(id), try #require(step.to))
                case "valued": list.valued(try #require(id), try #require(step.to))
                case "remove":
                    let gone = try #require(id)
                    list.remove(gone)
                    removed[index + 1] = gone
                default: Issue.record("\(item.name): no op \(step.op)")
                }
                let place = "\(item.name), step \(index + 1) (\(step.op))"
                #expect(list.rows.map { Row(name: $0.name, value: $0.value, saved: $0.saved) } == step.expect, "\(place)")
                #expect(Set(list.rows.map(\.id)).count == list.rows.count, "\(place)")
            }
        }
    }

    /// A saved row whose value is only spaces or a newline keeps its saved value, read the way the
    /// request is built.
    @Test func aBlankValueInASavedRowKeepsTheSavedOne() throws {
        let cases = try Self.vectors().savedHeaderValues
        #expect(cases.count >= 5)
        for item in cases {
            let rows = item.rows.map { HeaderRules.Row(name: $0.name, value: $0.value, saved: $0.saved) }
            #expect(rowHeaders(rows, saved: item.saved) == item.expect, "\(item.name)")
        }
    }

    // The ids beside the rows, which the shared vectors cannot name.

    private func filled() -> HeaderList {
        var list = HeaderList.empty
        list.replaced([.init(name: "A", value: "1", saved: false), .init(name: "B", value: "", saved: true), .init(name: "C", value: "3", saved: false)])
        return list
    }

    @Test func renamingOrTypingKeepsTheRowsId() {
        var list = filled()
        let ids = list.rows.map(\.id)
        list.renamed(ids[1], "X")
        list.valued(ids[1], "v")
        #expect(list.rows.map(\.id) == ids)
    }

    @Test func anAddedRowGetsAnIdOfItsOwnAtTheEnd() {
        var list = filled()
        let before = list.rows.map(\.id)
        let id = list.added()
        #expect(!before.contains(id))
        #expect(list.rows.map(\.id) == before + [id])
        #expect(list.rows.last.map { $0.name == "" && $0.value == "" && !$0.saved } == true)
    }

    @Test func removingKeepsEveryOtherRowsId() {
        var list = filled()
        let ids = list.rows.map(\.id)
        list.remove(ids[1])
        #expect(list.rows.map(\.id) == [ids[0], ids[2]])
    }

    @Test func aRefillGivesFreshIds() {
        var list = filled()
        let before = Set(list.rows.map(\.id))
        list.replaced([.init(name: "A", value: "1", saved: false), .init(name: "B", value: "", saved: true)])
        #expect(before.isDisjoint(with: list.rows.map(\.id)))
    }

    @Test func anOpOnAnIdNotThereChangesNothing() {
        var list = filled()
        let before = list
        list.renamed(UUID(), "X")
        list.valued(UUID(), "v")
        list.remove(UUID())
        #expect(list == before)
    }
}

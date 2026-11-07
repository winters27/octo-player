import Foundation
import Testing
@testable import OctoKit

@Suite struct ChartChoicesTests {
    private func charts(_ fields: String) throws -> ChartChoices {
        try SubsonicClient.decode(Data(FakeServer.okBody("\"charts\":{\(fields)}").utf8), key: "charts")
    }

    @Test func theListenersOwnChoicesDecode() throws {
        let answer = try charts(#""country":"br","setting":"br","pinned":["jp","kr"],"countries":["br","jp","kr","us"],"maxPinned":3,"#
            + #""serverCountry":"us","chart":[{"id":"34","name":"Popular right now","label":"Top songs","kind":"overall","on":true}]"#)
        #expect(answer.country == "br")
        #expect(answer.setting == "br")
        #expect(answer.chosen == "br")
        #expect(answer.pinned == ["jp", "kr"])
        #expect(answer.countries == ["br", "jp", "kr", "us"])
        #expect(answer.maxPinned == 3)
        #expect(answer.serverCountry == "us")
        #expect(answer.chart.map(\.id) == ["34"])
        #expect(answer.isPinned("JP"))
        #expect(!answer.isPinned("us"))
        #expect(!answer.pinsFull)
    }

    @Test func followingTheServerChoosesNoCountry() throws {
        let answer = try charts(#""country":"us","setting":"auto","pinned":["jp","kr","de"],"maxPinned":3,"chart":[]"#)
        #expect(answer.chosen == nil)
        #expect(answer.pinsFull)
    }

    @Test func anOlderServerSendsOnlyTheCountryAndCharts() throws {
        let answer = try charts(#""country":"us","chart":[{"id":"34","label":"Top songs"},{"id":"new","label":"Best New Songs"}]"#)
        #expect(answer.country == "us")
        #expect(answer.setting == nil)
        #expect(answer.chosen == nil)
        #expect(answer.pinned.isEmpty)
        #expect(answer.countries.isEmpty)
        #expect(answer.maxPinned == 0)
        #expect(answer.serverCountry == nil)
        // A server that does not say how many may be pinned decides itself.
        #expect(!answer.pinsFull)
        #expect(answer.chart.map(\.id) == ["34", "new"])
    }

    @Test func aMistypedFieldIsLeftEmpty() throws {
        let answer = try charts(#""country":"us","pinned":"jp","countries":7,"maxPinned":"3","chart":[]"#)
        #expect(answer.pinned.isEmpty)
        #expect(answer.countries.isEmpty)
        #expect(answer.maxPinned == 3)
    }
}

@Suite struct ChartCountryCallsTests {
    private static let answer = #""charts":{"country":"jp","setting":"jp","pinned":[],"countries":["jp","us"],"maxPinned":3,"chart":[]}"#

    @Test func theChartsOfACountryAskForIt() async throws {
        let server = FakeServer()
        server.ok(Self.answer)
        server.ok(Self.answer)
        let client = server.client()
        _ = try await client.charts(country: "jp")
        _ = try await client.charts()
        let sent = server.requests
        #expect(sent.map(\.endpoint) == ["getCharts", "getCharts"])
        #expect(sent[0].query["country"] == "jp")
        // The listener's own: no country sent.
        #expect(sent[1].query["country"] == nil)
    }

    @Test func aChartOfACountryAsksForIt() async throws {
        let server = FakeServer()
        server.ok(#""topSongs":{"source":"apple","country":"jp","entry":[]}"#)
        server.ok(#""topSongs":{"source":"apple","entry":[]}"#)
        let client = server.client()
        let japan = try await client.topChart(count: 50, chart: "18", country: "jp")
        _ = try await client.topChart(count: 50, chart: "18")
        let sent = server.requests
        #expect(sent.map(\.endpoint) == ["getTopChart", "getTopChart"])
        #expect(sent[0].query["chart"] == "18")
        #expect(sent[0].query["country"] == "jp")
        #expect(sent[1].query["country"] == nil)
        #expect(japan.country == "jp")
    }

    @Test func savingTheCountrySendsItAndAnswersTheCharts() async throws {
        let server = FakeServer()
        server.ok(Self.answer)
        let saved = try await server.client().setChartCountry("jp")
        #expect(server.requests.first?.endpoint == "setChartCountry")
        #expect(server.requests.first?.query["country"] == "jp")
        #expect(saved.chosen == "jp")
    }

    @Test func followingTheServerSavesAuto() async throws {
        let server = FakeServer()
        server.ok(Self.answer)
        _ = try await server.client().setChartCountry(chartCountryAuto)
        #expect(server.requests.first?.query["country"] == "auto")
    }

    @Test func pinningSendsOneCountryAtATime() async throws {
        let server = FakeServer()
        server.ok(Self.answer)
        server.ok(Self.answer)
        let client = server.client()
        _ = try await client.pinChartCountry("kr", pinned: true)
        _ = try await client.pinChartCountry("kr", pinned: false)
        let sent = server.requests
        #expect(sent.map(\.endpoint) == ["pinChartCountry", "pinChartCountry"])
        #expect(sent.map { $0.query["country"] } == ["kr", "kr"])
        #expect(sent.map { $0.query["pinned"] } == ["true", "false"])
    }

    @Test func aSaveAnsweredWithoutTheChartsThrows() async {
        let server = FakeServer()
        server.ok()
        server.ok()
        let client = server.client()
        await #expect(throws: OctoError.notSubsonic()) { _ = try await client.setChartCountry("jp") }
        await #expect(throws: OctoError.notSubsonic()) { _ = try await client.pinChartCountry("jp", pinned: true) }
    }

    @Test func aRefusalThrowsTheServersWords() async {
        let server = FakeServer()
        server.error(10, "You can pin at most 3 countries.")
        server.error(0, "Still loading your other countries. Try again in a moment.")
        let client = server.client()
        await #expect(throws: OctoError.server(code: 10, message: "You can pin at most 3 countries.")) {
            _ = try await client.pinChartCountry("de", pinned: true)
        }
        await #expect(throws: OctoError.server(code: 0, message: "Still loading your other countries. Try again in a moment.")) {
            _ = try await client.charts(country: "de")
        }
    }
}

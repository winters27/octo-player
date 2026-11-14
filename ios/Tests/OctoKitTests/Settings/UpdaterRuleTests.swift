import Foundation
import Testing
@testable import OctoKit

@Suite struct UpdaterRuleTests {
    private let profile = Data("<?xml version=\"1.0\"?><plist version=\"1.0\"><dict/></plist>".utf8)
    private let testBuild = "This is a test build, so it doesn't update itself."

    @Test func aStoreCopyLeavesUpdatesToApple() {
        #expect(UpdaterRule.off(source: nil, profile: nil, debug: false, version: "1.11.0", hasKeys: true) == UpdaterRule.fromApple)
        #expect(UpdaterRule.fromApple == "Octo updates through TestFlight or the App Store.")
        #expect(UpdaterRule.sourceKey == "OctoInstallSource")
    }

    @Test func theAppleLineWinsOverEveryOtherReason() {
        #expect(UpdaterRule.off(source: nil, profile: nil, debug: true, version: "Development build", hasKeys: false) == UpdaterRule.fromApple)
    }

    @Test func theSideloadIPALooksForItsOwnUpdatesWithOrWithoutAProfile() {
        #expect(UpdaterRule.off(source: "sideload", profile: nil, debug: false, version: "1.11.0", hasKeys: true) == nil)
        #expect(UpdaterRule.off(source: "sideload", profile: profile, debug: false, version: "1.11.0", hasKeys: true) == nil)
    }

    @Test func aCopyBuiltOnAComputerLooksForItsOwnUpdates() {
        #expect(UpdaterRule.off(source: nil, profile: profile, debug: false, version: "1.11.0", hasKeys: true) == nil)
        #expect(UpdaterRule.off(source: nil, profile: Data(), debug: false, version: "1.11.0", hasKeys: true) == nil)
    }

    @Test func anUnknownSourceIsNotASideload() {
        #expect(UpdaterRule.off(source: "testflight", profile: nil, debug: false, version: "1.11.0", hasKeys: true) == UpdaterRule.fromApple)
    }

    @Test func aTestBuildDoesNot() {
        #expect(UpdaterRule.off(source: "sideload", profile: nil, debug: true, version: "1.11.0", hasKeys: true) == testBuild)
        #expect(UpdaterRule.off(source: nil, profile: profile, debug: true, version: "1.11.0", hasKeys: true) == testBuild)
        #expect(UpdaterRule.off(source: nil, profile: profile, debug: false, version: "Development build", hasKeys: true) == testBuild)
        #expect(UpdaterRule.off(source: "sideload", profile: nil, debug: false, version: "1.11.0", hasKeys: false) == "This build has no key to check updates with.")
    }
}

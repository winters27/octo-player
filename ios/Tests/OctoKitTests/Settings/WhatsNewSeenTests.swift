import Foundation
import Testing
@testable import OctoKit

@Suite struct WhatsNewSeenTests {
    /// A store of its own for each test, removed afterwards.
    private func withStore(_ body: (UserDefaults) -> Void) {
        let name = "octo.test.whats-new.\(UUID().uuidString)"
        guard let defaults = UserDefaults(suiteName: name) else {
            Issue.record("no store")
            return
        }
        body(defaults)
        defaults.removePersistentDomain(forName: name)
    }

    @Test func aBuildNumberAnOlderBuildKeptCountsAsSeenOnce() {
        withStore { defaults in
            defaults.set(7, forKey: WhatsNewSeen.buildKey)
            let seen = WhatsNewSeen(defaults: defaults, current: "1.11.0")
            #expect(seen.lastSeen == "1.11.0")
            #expect(defaults.string(forKey: WhatsNewSeen.key) == "1.11.0")
            #expect(!seen.due(freshInstall: false))
            #expect(WhatsNewSeen(defaults: defaults, current: "1.12.0").due(freshInstall: false))
        }
    }

    @Test func theStoredVersionWinsOverABuildNumber() {
        withStore { defaults in
            defaults.set(7, forKey: WhatsNewSeen.buildKey)
            defaults.set("1.10.0", forKey: WhatsNewSeen.key)
            let seen = WhatsNewSeen(defaults: defaults, current: "1.11.0")
            #expect(seen.lastSeen == "1.10.0")
            #expect(seen.due(freshInstall: false))
        }
    }

    @Test func aRespinOfTheSameVersionHasNoNews() {
        withStore { defaults in
            WhatsNewSeen(defaults: defaults, current: "1.11.0").markSeen()
            #expect(!WhatsNewSeen(defaults: defaults, current: "1.11.0").due(freshInstall: false))
        }
    }

    @Test func aFreshInstallSettlesOnItsOwnVersion() {
        withStore { defaults in
            let seen = WhatsNewSeen(defaults: defaults, current: "1.11.0")
            #expect(!seen.due(freshInstall: true))
            seen.settle(freshInstall: true)
            #expect(defaults.string(forKey: WhatsNewSeen.key) == "1.11.0")
            #expect(WhatsNewSeen(defaults: defaults, current: "1.12.0").due(freshInstall: false))
        }
    }

    @Test func theVersionIsTheOnePeopleSee() {
        #expect(WhatsNewSeen.versionInfoKey == "CFBundleShortVersionString")
    }
}

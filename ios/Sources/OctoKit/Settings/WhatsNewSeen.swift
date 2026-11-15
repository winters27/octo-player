import Foundation

/// Which version's news was last put away, kept in its own store. TestFlight raises the build
/// number on every upload, so the version people see decides (1.11.0), never the build.
public final class WhatsNewSeen {
    /// The store's key for the version last seen.
    public static let key = "last_seen_release"
    /// Where older builds kept the build number they had shown the news for.
    public static let buildKey = "last_seen_version"
    /// The Info.plist key the running version is read from.
    public static let versionInfoKey = "CFBundleShortVersionString"

    private let defaults: UserDefaults
    public let current: String
    public private(set) var lastSeen: String?

    public init(defaults: UserDefaults, current: String) {
        self.defaults = defaults
        self.current = current
        let stored = defaults.string(forKey: Self.key)
        lastSeen = WhatsNew.seenVersion(stored: stored, storedBuild: defaults.object(forKey: Self.buildKey) as? Int, current: current)
        if stored == nil, let lastSeen { defaults.set(lastSeen, forKey: Self.key) }
    }

    public func due(freshInstall: Bool) -> Bool {
        WhatsNew.due(lastSeen: lastSeen, current: current, freshInstall: freshInstall)
    }

    /// A fresh install starts having seen this version, so later updates know where they stand.
    public func settle(freshInstall: Bool) {
        if lastSeen == nil, freshInstall { markSeen() }
    }

    public func markSeen() {
        defaults.set(current, forKey: Self.key)
        lastSeen = current
    }
}

import Foundation

/// Whether this copy of Octo looks for its own updates, and if not, why not.
public enum UpdaterRule {
    /// What About says on a copy from TestFlight or the App Store, which bring its updates.
    public static let fromApple = "Octo updates through TestFlight or the App Store."
    /// The Info.plist key the IPA made for sideloading carries, set to `sideload`.
    public static let sourceKey = "OctoInstallSource"

    /// Why the updater is off, or nil when it runs. `source` is the app's `OctoInstallSource`
    /// value: the IPA made for sideloading says `sideload`, whatever tool put it on the phone.
    /// Without it, `profile` decides: the bytes of the build's own embedded provisioning profile,
    /// or nil when it has none. A copy built and installed from a computer carries one; a copy
    /// from TestFlight or the App Store carries none.
    public static func off(source: String?, profile: Data?, debug: Bool, version: String, hasKeys: Bool) -> String? {
        if source != "sideload", profile == nil { return fromApple }
        if PlayerVersion.parse(version) == nil { return "This is a test build, so it doesn't update itself." }
        if !hasKeys { return "This build has no key to check updates with." }
        if debug { return "This is a test build, so it doesn't update itself." }
        return nil
    }
}

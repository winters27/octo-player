import Foundation

/// The look the whole app is drawn in. Stored by name, so a name never changes once shipped;
/// `light` says which Match slot a theme fills.
public enum ThemeChoice: String, StoredChoice, Codable {
    case octo = "Octo"
    case moss = "Moss"
    case dusk = "Dusk"
    case daylight = "Daylight"
    case paper = "Paper"

    public var label: String {
        switch self {
        case .octo: "Octo"
        case .moss: "Moss"
        case .dusk: "Dusk"
        case .daylight: "Daylight"
        case .paper: "Paper"
        }
    }

    public var light: Bool {
        switch self {
        case .octo, .moss, .dusk: false
        case .daylight, .paper: true
        }
    }

    /// The theme stored under `name`, or nil for an unknown or newer one.
    public static func named(_ name: String?) -> ThemeChoice? { allCases.first { $0.storedName == name } }
}

/// What is stored: the theme shown while Match is off, the light and dark themes Match uses, and
/// whether Match is on.
public struct ThemeSettings: Sendable, Equatable, Hashable {
    public var theme: ThemeChoice
    public var light: ThemeChoice
    public var dark: ThemeChoice
    public var match: Bool

    public init(theme: ThemeChoice = .octo, light: ThemeChoice = .daylight, dark: ThemeChoice = .octo, match: Bool = false) {
        self.theme = theme
        self.light = light
        self.dark = dark
        self.match = match
    }
}

/// The stored names read back. An unknown name, or a theme of the wrong kind in a slot, reads as
/// that field's default.
public func themeSettings(_ theme: String?, _ light: String?, _ dark: String?, _ match: Bool) -> ThemeSettings {
    ThemeSettings(
        theme: ThemeChoice.named(theme) ?? .octo,
        light: ThemeChoice.named(light).flatMap { $0.light ? $0 : nil } ?? .daylight,
        dark: ThemeChoice.named(dark).flatMap { !$0.light ? $0 : nil } ?? .octo,
        match: match
    )
}

/// The theme showing: with Match on, the slot for the device's mode.
public func activeTheme(_ settings: ThemeSettings, deviceDark: Bool) -> ThemeChoice {
    if !settings.match { return settings.theme }
    return deviceDark ? settings.dark : settings.light
}

/// Picking a theme makes it the theme and fills the slot of its kind, in one change, so the two
/// never disagree.
public func pickTheme(_ settings: ThemeSettings, _ choice: ThemeChoice) -> ThemeSettings {
    var next = settings
    next.theme = choice
    if choice.light { next.light = choice } else { next.dark = choice }
    return next
}

/// Match on or off. Turning it off keeps the theme that is showing.
public func setMatch(_ settings: ThemeSettings, on: Bool, deviceDark: Bool) -> ThemeSettings {
    var next = settings
    if on {
        next.match = true
    } else {
        next.theme = activeTheme(settings, deviceDark: deviceDark)
        next.match = false
    }
    return next
}

/// The line under the Match switch.
public func matchHelp(_ settings: ThemeSettings) -> String {
    "By day: \(settings.light.label). By night: \(settings.dark.label)."
}

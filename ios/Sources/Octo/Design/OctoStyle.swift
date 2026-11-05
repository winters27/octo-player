import OctoKit
import SwiftUI
import UIKit

extension EnvironmentValues {
    /// Every color the views below draw in: the theme showing with the accent chosen, or Octo's
    /// own surfaces over a cover. Set at the root from `ThemeState`.
    @Entry var octoPalette: ResolvedPalette = OctoPalette.octo
}

/// One of the palette's colors, resolved from the environment wherever it is drawn, so a view
/// over a cover and a view on the page draw the same name in their own colors.
struct OctoTone: ShapeStyle, Sendable, Hashable {
    enum Role: Sendable, Hashable {
        case background, backgroundSecondary, backgroundTertiary, surfaceRaised, sheetFill, edge, hairline
        case textPrimary, textSecondary, textMuted, selectedWords
        case accent, accentHover, accentTonal, accentSelected, accentFill, onAccentFill, focusRing, menuPress
        case complement, third, offTrack
        case glazeFilm, glazeSelected
        case inkPrimary, inkSecondary, inkTertiary, inkQuaternary, inkQuinary
    }

    let role: Role

    init(_ role: Role) { self.role = role }

    func resolve(in environment: EnvironmentValues) -> Color { environment.octoPalette.color(role) }
}

extension ResolvedPalette {
    /// The color drawn for `role`.
    func color(_ role: OctoTone.Role) -> Color { Color(argb: argb(role)) }

    /// The `0xAARRGGBB` drawn for `role`.
    func argb(_ role: OctoTone.Role) -> UInt32 {
        switch role {
        case .background: page
        case .backgroundSecondary: backgroundSecondary
        case .backgroundTertiary: card
        case .surfaceRaised: raised
        case .sheetFill: sheet
        case .edge: edge
        case .hairline: alphaArgb(ink, 80)
        case .textPrimary: text
        case .textSecondary: secondary
        case .textMuted: muted
        case .selectedWords: accents.selectedWords
        case .accent: accents.accent
        case .accentHover: accents.hover
        case .accentTonal: accents.tonal
        case .accentSelected: accents.selected
        case .accentFill: accents.fill
        case .onAccentFill: accents.onFill
        case .focusRing: accents.focusRing
        case .menuPress: accents.menuPress
        case .complement: complement
        case .third: third
        case .offTrack: offTrack
        case .glazeFilm: glazeFilm
        case .glazeSelected: light ? accents.selected : alphaArgb(0xFF00_0000, 720)
        case .inkPrimary: inkPrimary
        case .inkSecondary: inkSecondary
        case .inkTertiary: inkTertiary
        case .inkQuaternary: inkQuaternary
        case .inkQuinary: inkQuinary
        }
    }

    /// Whether the glass carries an accent other than the theme's own.
    var glassTinted: Bool { accents.glassAccent != theme.base.accent }

    /// The film laid into Octo's glass. On a dark theme the faintest dim, carrying the accent while
    /// the glass is tinted. On a light theme the sheet's base at the dark film's opacity plus a
    /// half (at most 0.92), or while tinted the accent's light form at the dark tint's opacity.
    var glazeFilm: UInt32 {
        let dark = glassFilm(accent: RGBA(argb: accents.glassAccent), tinted: glassTinted)
        let darkAlpha = Int((dark.alpha * 1000).rounded())
        guard light else { return dark.argb }
        if glassTinted { return alphaArgb(accents.glassAccent, darkAlpha) }
        return alphaArgb(theme.base.sheet ?? theme.base.page, min(920, 500 + darkAlpha))
    }

    /// Inside a selected row: the secondary, muted and accent words become the small-words color
    /// tested on the selected fill.
    var onSelectedRow: ResolvedPalette {
        var p = self
        p.secondary = accents.selectedWords
        p.muted = accents.selectedWords
        p.accents.accent = accents.selectedWords
        return p
    }
}

extension RGBA {
    /// The color as `0xAARRGGBB`, each channel rounded to a byte.
    var argb: UInt32 { UInt32(max(0, min(255, (alpha * 255).rounded()))) << 24 | hex }
}

/// Winters' Glass, the palette every Octo app shares, in the theme showing. On iPhone the glass
/// itself is Apple's Liquid Glass; these colors are what sits under and in it. Each one resolves
/// where it is drawn; a view that needs a plain `Color` reads `@Environment(\.octoPalette)`.
enum OctoColor {
    static let background = OctoTone(.background)
    /// A low-alpha wash that tints glass panels.
    static let backgroundSecondary = OctoTone(.backgroundSecondary)
    /// The solid surface under cards and glazed controls, and the artwork placeholder tile.
    static let backgroundTertiary = OctoTone(.backgroundTertiary)
    /// The accent chosen in Appearance, in the theme's form: selected states, sliders, switches,
    /// focus rings and filled buttons.
    static let accent = OctoTone(.accent)
    static let accentHover = OctoTone(.accentHover)
    static let surfaceRaised = OctoTone(.surfaceRaised)
    static let textPrimary = OctoTone(.textPrimary)
    /// Words stay in the theme's own secondary color whatever the accent.
    static let textSecondary = OctoTone(.textSecondary)
    /// The quietest words, still 4.5:1 on every layer.
    static let textMuted = OctoTone(.textMuted)
    /// Secondary, muted and accent words on a selected row.
    static let selectedWords = OctoTone(.selectedWords)
    static let error = Color(OctoPalette.error)
    /// A standard button's resting fill.
    static let accentTonal = OctoTone(.accentTonal)
    /// A selected list row.
    static let accentSelected = OctoTone(.accentSelected)
    /// The filled button, and the words on it.
    static let accentFill = OctoTone(.accentFill)
    static let onAccentFill = OctoTone(.onAccentFill)
    static let focusRing = OctoTone(.focusRing)
    /// A menu row under the finger: the selected row's tint, half as strong.
    static let menuPress = OctoTone(.menuPress)
    /// The theme's highlight and placeholder colors.
    static let complement = OctoTone(.complement)
    static let third = OctoTone(.third)
    /// A switch's track while off.
    static let offTrack = OctoTone(.offTrack)
    /// The edge round a card.
    static let edge = OctoTone(.edge)
    /// A destructive action's words; its icon stays in the words' color.
    static let destructive = Color(OctoPalette.destructive)
    static let signalRed = Color(OctoPalette.signalRed)
    static let signalOrange = Color(OctoPalette.signalOrange)
    static let signalYellow = Color(OctoPalette.signalYellow)
    static let signalGreen = Color(OctoPalette.signalGreen)
    static let signalBlue = Color(OctoPalette.signalBlue)
    /// A sheet's fill.
    static let sheetFill = OctoTone(.sheetFill)
    /// The film in Octo's glass, carrying the accent while Color the glass is on.
    static let glazeFilm = OctoTone(.glazeFilm)
    /// The pill that marks the chosen item inside glass.
    static let glazeSelected = OctoTone(.glazeSelected)
    /// Hairlines between rows of a menu.
    static let hairline = OctoTone(.hairline)
    /// The app icon's lavender, used where Octo marks something as its own.
    static let lavender = Color(hex: 0x9C8AF0)
}

/// The theme's ink at falling opacities, for what has no Octo color of its own.
enum OctoInkColor {
    static let primary = OctoTone(.inkPrimary)
    static let secondary = OctoTone(.inkSecondary)
    static let tertiary = OctoTone(.inkTertiary)
    static let quaternary = OctoTone(.inkQuaternary)
    static let quinary = OctoTone(.inkQuinary)
    /// How faint a control that cannot be used right now is.
    static let disabled = OctoInk.disabledAlpha
}

extension View {
    /// Draws what is inside over a cover: Octo's own surfaces and words with the accent in its
    /// form for dark surfaces, and the system's own parts dark.
    func octoOverArt() -> some View { modifier(OverArt()) }

    /// A selected row's fill in `shape`, with the secondary, muted and accent words inside it in
    /// the color tested on that fill.
    func octoSelectedFill<S: Shape>(_ shape: S = Rectangle()) -> some View { modifier(SelectedFill(shape: shape)) }
}

private struct OverArt: ViewModifier {
    @Environment(\.octoPalette) private var palette

    func body(content: Content) -> some View {
        content
            .environment(\.octoPalette, overArtPalette(palette))
            .environment(\.colorScheme, .dark)
    }
}

private struct SelectedFill<S: Shape>: ViewModifier {
    let shape: S
    @Environment(\.octoPalette) private var palette

    func body(content: Content) -> some View {
        content
            .environment(\.octoPalette, palette.onSelectedRow)
            .background(palette.color(.accentSelected), in: shape)
    }
}

/// Corner sizes, the same steps every Octo app uses.
enum OctoRadius {
    static let chrome = CGFloat(OctoRadii.chrome)
    static let chromeSmall = CGFloat(OctoRadii.chromeSmall)
    static let row = CGFloat(OctoRadii.row)
    static let artXs = CGFloat(OctoRadii.artXs)
    static let artS = CGFloat(OctoRadii.artS)
    static let artM = CGFloat(OctoRadii.artM)
    static let artL = CGFloat(OctoRadii.artL)
    static let dialog = CGFloat(OctoRadii.dialog)
    static let xs = CGFloat(OctoRadii.xs)
    static let s = CGFloat(OctoRadii.s)
    static let m = CGFloat(OctoRadii.m)
    static let l = CGFloat(OctoRadii.l)
}

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: opacity
        )
    }

    init(_ rgba: RGBA) {
        self.init(.sRGB, red: rgba.red, green: rgba.green, blue: rgba.blue, opacity: rgba.alpha)
    }
}

extension Font {
    /// The type scale, on SF. Sizes follow the phone's text size, as the other apps' do.
    static var octoDisplay: Font { octo(32, .black, .largeTitle) }
    static var octoTitle: Font { octo(24, .black, .title1) }
    static var octoHeadline: Font { octo(20, .bold, .title3) }
    static var octoSection: Font { octo(18, .bold, .headline) }
    static var octoBody: Font { octo(16, .medium, .body) }
    static var octoBodySmall: Font { octo(14, .regular, .subheadline) }
    static var octoCaption: Font { octo(12, .regular, .caption1) }
    static var octoLabel: Font { octo(13.5, .semibold, .footnote) }

    /// A size from the scale, grown or shrunk with the phone's text size.
    static func octo(_ size: CGFloat, _ weight: UIFont.Weight, _ style: UIFont.TextStyle = .body) -> Font {
        Font(UIFontMetrics(forTextStyle: style).scaledFont(for: .systemFont(ofSize: size, weight: weight)) as CTFont)
    }
}

/// The display title's letter spacing.
let octoDisplayTracking: CGFloat = -0.5

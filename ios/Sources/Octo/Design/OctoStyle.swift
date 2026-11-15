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
        case panelFill, panelRim
        case textPrimary, textSecondary, textMuted, selectedWords
        case accent, accentHover, accentTonal, accentSelected, accentFill, onAccentFill, onAccent, focusRing, menuPress
        case complement, third, offTrack, highlight
        case glazeFilm, glazeSelected
        case inkPrimary, inkSecondary, inkTertiary, inkQuaternary, inkQuinary
        case errorWords, tagWords, tagFill
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
        // A card on a light page is solid, or the shadows under it show through and gray it;
        // its top edge is the theme's edge color, since dark ink there reads as a crease.
        case .panelFill: light ? card : backgroundSecondary
        case .panelRim: light ? alphaArgb(edge, 320) : alphaArgb(text, 80)
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
        case .onAccent: accents.onAccent
        case .focusRing: accents.focusRing
        case .menuPress: accents.menuPress
        case .complement: complement
        case .highlight: theme == .octo ? text : complement
        case .third: third
        case .offTrack: offTrack
        case .glazeFilm: glazeFilm
        case .glazeSelected: light ? accents.selected : alphaArgb(0xFF00_0000, 720)
        case .inkPrimary: inkPrimary
        case .inkSecondary: inkSecondary
        case .inkTertiary: inkTertiary
        case .inkQuaternary: inkQuaternary
        case .inkQuinary: inkQuinary
        case .errorWords: errorWords
        case .tagWords: tagWords
        case .tagFill: tagFill
        }
    }

    /// Whether the glass carries an accent other than the theme's own.
    var glassTinted: Bool { accents.glassAccent != theme.base.accent }

    /// The tint laid into Octo's glass while the glass takes the accent: on a dark theme the accent
    /// pulled toward black, on a light one the accent's light form, at the same opacity. Untinted,
    /// a dark theme keeps Octo's faint dim and a light one is Apple's own glass with no film.
    var glazeFilm: UInt32 {
        guard glassTinted else { return light ? 0 : OctoPalette.glazeFilm.argb }
        let dark = glassFilm(accent: RGBA(argb: accents.glassAccent), tinted: true)
        guard light else { return dark.argb }
        return alphaArgb(accents.glassAccent, Int((dark.alpha * 1000).rounded()))
    }

    /// The tint for Octo's glass, or nil where the glass is Apple's own with no film.
    var glassTintColor: Color? { glazeFilm == 0 ? nil : Color(argb: glazeFilm) }

    /// A shadow at `alpha`, scaled by how strong this theme's shadows are: black on a dark theme,
    /// the theme's own ink on a light one.
    func shadowColor(_ alpha: Double) -> Color {
        (light ? Color(argb: ink) : Color.black).opacity(alpha * Double(shadow) / 1000)
    }

    /// A dark film's color in this theme: black on a dark theme; on a light one the sheet at half
    /// again the opacity, at most 0.92.
    func filmColor(_ alpha: Double) -> Color {
        light ? Color(argb: sheet).opacity(min(0.92, 0.5 + alpha)) : Color.black.opacity(alpha)
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
    /// An error note's words and glyph: one red for light themes and one for dark, each reading at
    /// 4.5:1 on every surface of its themes.
    static let errorWords = OctoTone(.errorWords)
    /// The quiet Optional tag after a field's name: the accent's words on the accent's tonal fill.
    static let tagWords = OctoTone(.tagWords)
    static let tagFill = OctoTone(.tagFill)
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
    /// What is liked or counted: the complement, or the words' white in Octo, as it always was.
    static let highlight = OctoTone(.highlight)
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

/// Black at `alpha` as a shadow or contour in the theme showing, resolved where it is drawn.
struct OctoShade: ShapeStyle, Sendable, Hashable {
    let alpha: Double

    init(_ alpha: Double) { self.alpha = alpha }

    func resolve(in environment: EnvironmentValues) -> Color { environment.octoPalette.shadowColor(alpha) }
}

/// A dark film at `alpha` laid behind words, in the theme showing, resolved where it is drawn.
struct OctoFilm: ShapeStyle, Sendable, Hashable {
    let alpha: Double

    init(_ alpha: Double) { self.alpha = alpha }

    func resolve(in environment: EnvironmentValues) -> Color { environment.octoPalette.filmColor(alpha) }
}

/// A shadow in the theme's strength.
private struct OctoShadow: ViewModifier {
    let alpha: Double
    let radius: CGFloat
    let x: CGFloat
    let y: CGFloat
    @Environment(\.octoPalette) private var palette

    func body(content: Content) -> some View {
        content.shadow(color: palette.shadowColor(alpha), radius: radius, x: x, y: y)
    }
}

/// Octo's glass: Apple's own, tinted only while the glass takes the accent.
private struct OctoGlassFace<S: Shape>: ViewModifier {
    let shape: S
    let film: Color?
    let interactive: Bool
    let lifted: Bool
    @Environment(\.octoPalette) private var palette

    func body(content: Content) -> some View {
        var glass = Glass.regular
        if lifted {
            glass = glass.tint(palette.color(.textPrimary).opacity(0.14))
        } else if let tint = film ?? palette.glassTintColor {
            glass = glass.tint(tint)
        }
        if interactive { glass = glass.interactive() }
        return content.glassEffect(glass, in: shape)
    }
}

extension View {
    /// Octo's glass in `shape`: Apple's untinted glass, or tinted with `film` (or with the accent
    /// while the glass takes it). `interactive` lets it answer the finger the way Liquid Glass
    /// controls do; `lifted` brightens it a little, for the main button of a pair.
    func octoGlass<S: Shape>(_ shape: S, film: Color? = nil, interactive: Bool = false, lifted: Bool = false) -> some View {
        modifier(OctoGlassFace(shape: shape, film: film, interactive: interactive, lifted: lifted))
    }

    /// A black shadow at `alpha`, scaled by the theme's shadow strength.
    func octoShadow(_ alpha: Double, radius: CGFloat, x: CGFloat = 0, y: CGFloat = 0) -> some View {
        modifier(OctoShadow(alpha: alpha, radius: radius, x: x, y: y))
    }

    /// Draws what is inside over a cover: Octo's own surfaces and words with the accent in its
    /// form for dark surfaces, and the system's own parts dark.
    func octoOverArt() -> some View { modifier(OverArt()) }

    /// A selected row's fill in `shape`, with the secondary, muted and accent words inside it in
    /// the color tested on that fill. Nothing changes while `on` is false.
    func octoSelectedFill<S: Shape>(_ shape: S = Rectangle(), on: Bool = true) -> some View {
        modifier(SelectedFill(shape: shape, on: on))
    }

    /// The words inside a selected row whose fill is drawn elsewhere: while `on`, the secondary,
    /// muted and accent words take the color tested on the selected fill.
    func octoOnSelectedFill(_ on: Bool = true) -> some View { modifier(OnSelectedFill(on: on)) }
}

private struct OverArt: ViewModifier {
    @Environment(\.octoPalette) private var palette

    func body(content: Content) -> some View {
        let dark = overArtPalette(palette)
        return content
            .environment(\.octoPalette, dark)
            .environment(\.colorScheme, .dark)
            .tint(dark.color(.accent))
    }
}

private struct OnSelectedFill: ViewModifier {
    let on: Bool
    @Environment(\.octoPalette) private var palette

    func body(content: Content) -> some View {
        content.environment(\.octoPalette, on ? palette.onSelectedRow : palette)
    }
}

private struct SelectedFill<S: Shape>: ViewModifier {
    let shape: S
    let on: Bool
    @Environment(\.octoPalette) private var palette

    func body(content: Content) -> some View {
        content
            .environment(\.octoPalette, on ? palette.onSelectedRow : palette)
            .background { if on { shape.fill(palette.color(.accentSelected)) } }
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
    /// The sign-in form's roles. A group's title, and Advanced's: 17 semibold, as the headline.
    static var octoFormGroup: Font { octo(17, .semibold, .headline) }
    /// A setting's name: 15 medium.
    static var octoFormName: Font { octo(15, .medium, .subheadline) }
    /// A value, and the words typed in a field: 15 regular.
    static var octoFormValue: Font { octo(15, .regular, .subheadline) }
    /// The line under a setting's name: 13 regular, as the footnote.
    static var octoFormDescription: Font { octo(13, .regular, .footnote) }
    /// A note or an error under a card: 14 regular.
    static var octoFormNote: Font { octo(14, .regular, .subheadline) }
    /// The Optional tag: 11 semibold, as caption 2.
    static var octoFormTag: Font { octo(11, .semibold, .caption2) }

    /// A size from the scale, grown or shrunk with the phone's text size.
    static func octo(_ size: CGFloat, _ weight: UIFont.Weight, _ style: UIFont.TextStyle = .body) -> Font {
        Font(UIFontMetrics(forTextStyle: style).scaledFont(for: .systemFont(ofSize: size, weight: weight)) as CTFont)
    }
}

/// The display title's letter spacing.
let octoDisplayTracking: CGFloat = -0.5

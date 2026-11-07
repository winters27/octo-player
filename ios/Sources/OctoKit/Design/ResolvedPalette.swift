import Foundation

private let whiteArgb: UInt32 = 0xFFFF_FFFF
private let blackArgb: UInt32 = 0xFF00_0000

@inline(__always) private func ch(_ argb: UInt32, _ shift: UInt32) -> Int { Int(argb >> shift & 0xFF) }

@inline(__always) private func packed(_ a: Int, _ r: Int, _ g: Int, _ b: Int) -> UInt32 {
    UInt32(a) << 24 | UInt32(r) << 16 | UInt32(g) << 8 | UInt32(b)
}

/// `from` moved `perMille` thousandths of the way to `to`, channel by channel, rounded half up.
/// Opaque.
public func mixArgb(_ from: UInt32, _ to: UInt32, _ perMille: Int) -> UInt32 {
    func one(_ shift: UInt32) -> Int {
        let f = ch(from, shift)
        let t = ch(to, shift)
        return (f * 1000 + (t - f) * perMille + 500) / 1000
    }
    return packed(0xFF, one(16), one(8), one(0))
}

/// `argb` at an opacity of `perMille` thousandths.
public func alphaArgb(_ argb: UInt32, _ perMille: Int) -> UInt32 {
    UInt32((255 * perMille + 500) / 1000) << 24 | argb & 0xFF_FFFF
}

/// A see-through color laid over an opaque one.
public func overArgb(_ fg: UInt32, _ bg: UInt32) -> UInt32 {
    let a = ch(fg, 24)
    func one(_ shift: UInt32) -> Int { (ch(fg, shift) * a + ch(bg, shift) * (255 - a) + 127) / 255 }
    return packed(0xFF, one(16), one(8), one(0))
}

/// The contrast of a color, see-through or not, over the layer under it.
public func contrastOn(_ fg: UInt32, _ layer: UInt32) -> Double { contrastRatio(overArgb(fg, layer), layer) }

/// A theme's own colors before the accent is chosen. Nil where Octo builds a color from its
/// accent, as it always has.
public struct ThemeBase: Sendable, Equatable {
    public let page: UInt32
    public let card: UInt32
    public let raised: UInt32?
    public let sheet: UInt32?
    public let sheetAlpha: Int
    public let edge: UInt32?
    public let text: UInt32
    public let secondary: UInt32
    public let muted: UInt32?
    public let accent: UInt32
    public let complement: UInt32
    public let third: UInt32
    public let glow: [UInt32]
    public let ink: UInt32
    public let inkSecondary: Int
    public let inkTertiary: Int
    public let offTrack: Int
    public let shadow: Int

    private static let octo = ThemeBase(
        page: 0xFF0C_0C0D, card: 0xFF1A_1A1B, raised: nil, sheet: nil, sheetAlpha: 970,
        edge: nil, text: whiteArgb, secondary: 0xFF98_B2BA, muted: nil,
        accent: octoAccentArgb, complement: octoAccentArgb, third: octoAccentArgb, glow: [],
        ink: whiteArgb, inkSecondary: 550, inkTertiary: 250, offTrack: 340, shadow: 1000
    )
    private static let moss = ThemeBase(
        page: 0xFF0E_1512, card: 0xFF17_211B, raised: 0xFF20_2D26, sheet: 0xFF21_3029, sheetAlpha: 1000,
        edge: 0xFF3A_4D42, text: 0xFFED_F2EA, secondary: 0xFFB5_C9AE, muted: 0xFF97_AC92,
        accent: 0xFF9A_CB86, complement: 0xFFE2_B062, third: 0xFF7F_B8A8,
        glow: [0xFF2E_6A36, 0xFF7A_6020, 0xFF0F_2A26],
        ink: 0xFFED_F2EA, inkSecondary: 560, inkTertiary: 250, offTrack: 390, shadow: 1000
    )
    private static let dusk = ThemeBase(
        page: 0xFF12_0E18, card: 0xFF1E_1726, raised: 0xFF29_2032, sheet: 0xFF2F_243B, sheetAlpha: 1000,
        edge: 0xFF47_395A, text: 0xFFF4_EEF8, secondary: 0xFFC9_B6DA, muted: 0xFFAB_9ABE,
        accent: 0xFFE7_A0C4, complement: 0xFFF2_BE85, third: 0xFF98_ADEB,
        glow: [0xFF74_308A, 0xFF9A_5040, 0xFF1A_2A5C],
        ink: 0xFFF4_EEF8, inkSecondary: 550, inkTertiary: 250, offTrack: 380, shadow: 1000
    )
    private static let daylight = ThemeBase(
        page: 0xFFE7_ECEF, card: whiteArgb, raised: 0xFFF2_F5F7, sheet: whiteArgb, sheetAlpha: 1000,
        edge: 0xFFC9_D2D8, text: 0xFF12_181C, secondary: 0xFF2F_4A55, muted: 0xFF50_6570,
        accent: 0xFF2E_6577, complement: 0xFF9A_4722, third: 0xFF5F_55A0,
        glow: [0xFFBF_D9E6, 0xFFF2_D3C0, 0xFFD8_D3F0],
        ink: 0xFF12_181C, inkSecondary: 610, inkTertiary: 250, offTrack: 470, shadow: 250
    )
    private static let paper = ThemeBase(
        page: 0xFFEB_E2D2, card: 0xFFFA_F6EE, raised: 0xFFF2_EBDF, sheet: 0xFFFF_FBF4, sheetAlpha: 1000,
        edge: 0xFFCD_BEA4, text: 0xFF2A_1F14, secondary: 0xFF55_412B, muted: 0xFF6A_5742,
        accent: 0xFF8F_3F1D, complement: 0xFF3C_6646, third: 0xFF6F_540E,
        glow: [0xFFE8_C9A0, 0xFFC9_D6B8, 0xFFE7_C1AE],
        ink: 0xFF2A_1F14, inkSecondary: 650, inkTertiary: 250, offTrack: 500, shadow: 250
    )

    /// Opacities and amounts are in thousandths.
    static func of(_ theme: ThemeChoice) -> ThemeBase {
        switch theme {
        case .octo: octo
        case .moss: moss
        case .dusk: dusk
        case .daylight: daylight
        case .paper: paper
        }
    }
}

extension ThemeChoice {
    /// The theme's own colors before the accent is chosen.
    public var base: ThemeBase { ThemeBase.of(self) }
}

/// The colors built from an accent, for one kind of surface.
public struct AccentFamily: Sendable, Equatable, Hashable {
    public var accent: UInt32
    public var hover: UInt32
    public var tonal: UInt32
    public var selected: UInt32
    public var selectedWords: UInt32
    public var menuPress: UInt32
    public var fill: UInt32
    public var onFill: UInt32
    public var onAccent: UInt32
    public var focusRing: UInt32
    public var glassAccent: UInt32

    public init(accent: UInt32, hover: UInt32, tonal: UInt32, selected: UInt32, selectedWords: UInt32, menuPress: UInt32,
                fill: UInt32, onFill: UInt32, onAccent: UInt32, focusRing: UInt32, glassAccent: UInt32) {
        self.accent = accent
        self.hover = hover
        self.tonal = tonal
        self.selected = selected
        self.selectedWords = selectedWords
        self.menuPress = menuPress
        self.fill = fill
        self.onFill = onFill
        self.onAccent = onAccent
        self.focusRing = focusRing
        self.glassAccent = glassAccent
    }
}

/// Every color one theme draws, with the accent chosen.
public struct ResolvedPalette: Sendable, Equatable, Hashable {
    public var theme: ThemeChoice
    public var light: Bool
    public var page: UInt32
    public var card: UInt32
    public var raised: UInt32
    public var sheet: UInt32
    public var edge: UInt32
    public var backgroundSecondary: UInt32
    public var text: UInt32
    public var secondary: UInt32
    public var muted: UInt32
    public var inkPrimary: UInt32
    public var inkSecondary: UInt32
    public var inkTertiary: UInt32
    public var inkQuaternary: UInt32
    public var inkQuinary: UInt32
    public var ink: UInt32
    public var offTrack: UInt32
    public var complement: UInt32
    public var third: UInt32
    public var glow: [UInt32]
    public var shadow: Int
    /// The accent family for this theme's own surfaces.
    public var accents: AccentFamily
    /// The accent family for surfaces over a cover, on Octo's dark surfaces.
    public var overArt: AccentFamily

    public init(theme: ThemeChoice, light: Bool, page: UInt32, card: UInt32, raised: UInt32, sheet: UInt32, edge: UInt32,
                backgroundSecondary: UInt32, text: UInt32, secondary: UInt32, muted: UInt32, inkPrimary: UInt32, inkSecondary: UInt32,
                inkTertiary: UInt32, inkQuaternary: UInt32, inkQuinary: UInt32, ink: UInt32, offTrack: UInt32, complement: UInt32,
                third: UInt32, glow: [UInt32], shadow: Int, accents: AccentFamily, overArt: AccentFamily) {
        self.theme = theme
        self.light = light
        self.page = page
        self.card = card
        self.raised = raised
        self.sheet = sheet
        self.edge = edge
        self.backgroundSecondary = backgroundSecondary
        self.text = text
        self.secondary = secondary
        self.muted = muted
        self.inkPrimary = inkPrimary
        self.inkSecondary = inkSecondary
        self.inkTertiary = inkTertiary
        self.inkQuaternary = inkQuaternary
        self.inkQuinary = inkQuinary
        self.ink = ink
        self.offTrack = offTrack
        self.complement = complement
        self.third = third
        self.glow = glow
        self.shadow = shadow
        self.accents = accents
        self.overArt = overArt
    }
}

/// An accent's form for light surfaces: its hue, held in chroma, darkened until it reads at 5:1
/// on every layer and white reads on it at 4.5:1.
public func onLightForm(_ argb: UInt32, _ base: ThemeBase) -> UInt32 {
    let lch = toLch(argb)
    let chroma = min(max(lch.c, 0.06), 0.14)
    let layers = layersOf(base)
    for k in 0...72 {
        let candidate = lchToArgb(0.56 - Double(k) * 0.005, chroma, lch.h)
        if layers.allSatisfy({ contrastRatio(candidate, $0) >= 5.0 }) && contrastRatio(whiteArgb, candidate) >= 4.5 { return candidate }
    }
    return lchToArgb(0.20, chroma, lch.h)
}

/// An accent's form for dark surfaces: the fixed accents as they are, any other color lifted to
/// their lightness the way a cover's is.
public func onDarkForm(_ argb: UInt32) -> UInt32 {
    AccentChoice.fixed.contains { $0.argb == argb } ? argb : accentFromArt(argb) ?? octoAccentArgb
}

private func layersOf(_ base: ThemeBase) -> [UInt32] {
    let raised = base.raised ?? mixArgb(base.card, octoAccentArgb, 60)
    let sheet = base.sheet ?? base.page
    return [base.page, base.card, raised, sheet]
}

/// The accent chosen, in the form `theme` draws it. A nil art seed means nothing with a cover is
/// playing.
public func themedAccent(_ theme: ThemeChoice, _ choice: AccentChoice, _ art: UInt32?) -> UInt32 {
    let base = theme.base
    if choice == .octo { return base.accent }
    if choice == .fromArt {
        guard let fromArt = art.flatMap({ accentFromArt($0, theme) }) else { return base.accent }
        return fromArt
    }
    if theme.light { return onLightForm(choice.argb!, base) }
    return choice.argb!
}

/// The accent family on a theme's layers.
private func family(light: Bool, accent: UInt32, glassAccent: UInt32, card: UInt32, page: UInt32, secondary: UInt32,
                    ink: UInt32) -> AccentFamily {
    let selected: UInt32
    if light {
        var k = 14
        var s = mixArgb(card, accent, k * 10)
        while k < 40 && (contrastRatio(s, page) < 1.12 || contrastRatio(s, card) < 1.12) {
            k += 1
            s = mixArgb(card, accent, k * 10)
        }
        selected = s
    } else {
        selected = mixArgb(blackArgb, accent, 400)
    }
    let selectedWords: UInt32
    if contrastRatio(secondary, selected) >= 7.0 {
        selectedWords = secondary
    } else {
        selectedWords = stride(from: 850, through: 1000, by: 10).map { alphaArgb(ink, $0) }.first { contrastOn($0, selected) >= 7.0 } ?? ink
    }
    let fill = light ? accent : mixArgb(accent, blackArgb, 450)
    let hover: UInt32
    if light {
        hover = mixArgb(accent, blackArgb, 180)
    } else if accent == octoAccentArgb {
        hover = 0xFFAD_C4CC
    } else {
        hover = mixArgb(accent, whiteArgb, 220)
    }
    return AccentFamily(
        accent: accent,
        hover: hover,
        tonal: light ? mixArgb(card, accent, 80) : mixArgb(0xFF1E_1E1E, accent, 100),
        selected: selected,
        selectedWords: selectedWords,
        menuPress: alphaArgb(selected, 550),
        fill: fill,
        onFill: light ? whiteArgb : contentArgbFor(fill),
        onAccent: light ? whiteArgb : page,
        focusRing: alphaArgb(accent, light ? 1000 : 700),
        glassAccent: glassAccent
    )
}

/// White words on a dark fill, black on a light one: by CIE lightness, the way the eye judges it.
public func contentArgbFor(_ fill: UInt32) -> UInt32 {
    let y = relativeLuminance(fill)
    let l = y > 216.0 / 24389.0 ? 116.0 * cbrt(y) - 16.0 : y * 24389.0 / 27.0
    return l / 100.0 > 0.58 ? blackArgb : whiteArgb
}

/// Every color `theme` draws with the accent `choice`, the playing cover's color `art` (nil with
/// none) and whether the glass takes the accent.
public func resolvePalette(_ theme: ThemeChoice, _ choice: AccentChoice, _ art: UInt32?, _ glassTint: Bool) -> ResolvedPalette {
    let base = theme.base
    let accent = themedAccent(theme, choice, art)
    let glassAccent = glassTint ? accent : base.accent
    let raised = base.raised ?? mixArgb(base.card, octoAccentArgb, 60)
    let sheet = alphaArgb(mixArgb(base.sheet ?? base.page, glassAccent, 50), base.sheetAlpha)
    let muted = base.muted ?? alphaArgb(base.secondary, 800)
    let octo = ThemeChoice.octo.base
    let darkAccent = theme.light ? onDarkForm(accent) : accent
    return ResolvedPalette(
        theme: theme,
        light: theme.light,
        page: base.page,
        card: base.card,
        raised: raised,
        sheet: sheet,
        edge: base.edge ?? alphaArgb(base.ink, 100),
        backgroundSecondary: alphaArgb(base.ink, 30),
        text: base.text,
        secondary: base.secondary,
        muted: muted,
        inkPrimary: alphaArgb(base.ink, 850),
        inkSecondary: alphaArgb(base.ink, base.inkSecondary),
        inkTertiary: alphaArgb(base.ink, base.inkTertiary),
        inkQuaternary: alphaArgb(base.ink, 100),
        inkQuinary: alphaArgb(base.ink, 50),
        ink: base.ink,
        offTrack: alphaArgb(base.ink, base.offTrack),
        complement: base.complement,
        third: base.third,
        glow: base.glow,
        shadow: base.shadow,
        accents: family(light: theme.light, accent: accent, glassAccent: glassAccent, card: base.card, page: base.page,
                        secondary: base.secondary, ink: base.ink),
        overArt: family(light: false, accent: darkAccent, glassAccent: glassTint ? darkAccent : octoAccentArgb, card: octo.card,
                        page: octo.page, secondary: octo.secondary, ink: octo.ink)
    )
}

/// What is drawn over a cover in any theme: Octo's own surfaces and words with `palette`'s accent
/// family in its form for dark surfaces, the sheet carrying that family's glass color.
public func overArtPalette(_ palette: ResolvedPalette) -> ResolvedPalette {
    var octo = resolvePalette(.octo, .octo, nil, false)
    octo.sheet = alphaArgb(mixArgb(octo.page, palette.overArt.glassAccent, 50), ThemeChoice.octo.base.sheetAlpha)
    octo.accents = palette.overArt
    octo.overArt = palette.overArt
    return octo
}

/// Whether every word role on the page of `p` reads with `glow` at `alphaPerMille` over it: main
/// and secondary words at 7:1, muted words, the theme's accent, complement and third, and `accent`
/// when given, at 4.5:1.
private func glowReads(_ p: ResolvedPalette, _ glow: UInt32, _ alphaPerMille: Int, _ accent: UInt32?) -> Bool {
    let under = overArgb(alphaArgb(glow, alphaPerMille), p.page)
    return contrastRatio(p.text, under) >= 7.0 && contrastRatio(p.secondary, under) >= 7.0 &&
        contrastOn(p.muted, under) >= 4.5 &&
        ([p.accents.accent, p.complement, p.third] + (accent.map { [$0] } ?? [])).allSatisfy { contrastRatio($0, under) >= 4.5 }
}

/// A color's lightness moved `k` hundredths: down on a dark theme, up on a light one.
private func stepped(_ lch: Lch, _ k: Int, _ light: Bool) -> UInt32 {
    let l = light ? lch.l + Double(k) * 0.01 : lch.l - Double(k) * 0.01
    return lchToArgb(min(max(l, 0.0), 1.0), lch.c, lch.h)
}

/// A cover's color for the page glow in `theme`, so every word role still reads with the glow at
/// `alphaPerMille` over the page: on a dark theme the color darkened in steps (no change for a
/// cover that already reads); on a light one mixed 70% toward the page, then lightened in steps.
/// `accent` is the accent drawn now, when it is not the theme's own; it must read too.
public func glowForCover(_ theme: ThemeChoice, _ art: UInt32, _ alphaPerMille: Int, _ accent: UInt32? = nil) -> UInt32 {
    let p = resolvePalette(theme, .octo, nil, false)
    let start = theme.light ? mixArgb(art, p.page, 700) : art
    let lch = toLch(start)
    for k in 0...60 {
        let glow = k == 0 ? start : stepped(lch, k, theme.light)
        if glowReads(p, glow, alphaPerMille, accent) { return glow }
    }
    return p.page
}

/// The colors of a glow mesh in `theme` (a cover's, or the theme's own at rest), each made readable
/// by `glowForCover`, then, where the glow blends two of them, the even blend of every pair read
/// too: while one does not, all of them darkened (a dark theme) or lightened (a light one) together
/// in steps, so the mesh keeps its look.
public func glowMesh(_ theme: ThemeChoice, _ colors: [UInt32], _ alphaPerMille: Int, _ accent: UInt32? = nil) -> [UInt32] {
    let p = resolvePalette(theme, .octo, nil, false)
    let each = colors.map { glowForCover(theme, $0, alphaPerMille, accent) }
    if each.count < 2 { return each }
    let lch = each.map(toLch)
    for k in 0...100 {
        let mesh = k == 0 ? each : lch.map { stepped($0, k, theme.light) }
        var blends: [UInt32] = []
        for i in mesh.indices {
            for j in (i + 1)..<mesh.count { blends.append(mixArgb(mesh[i], mesh[j], 500)) }
        }
        if (mesh + blends).allSatisfy({ glowReads(p, $0, alphaPerMille, accent) }) { return mesh }
    }
    return each.map { _ in p.page }
}

/// The key color (an icon tint taken from the cover) in `theme`: as it is on a dark page; on a
/// light one, its hue darkened until it reads at 3:1 on every layer.
public func keyForTheme(_ theme: ThemeChoice, _ key: UInt32) -> UInt32 {
    if !theme.light { return key }
    let base = theme.base
    let lch = toLch(key)
    let chroma = min(max(lch.c, 0.06), 0.14)
    for k in 0...72 {
        let candidate = lchToArgb(0.56 - Double(k) * 0.005, chroma, lch.h)
        if layersOf(base).allSatisfy({ contrastRatio(candidate, $0) >= 3.0 }) { return candidate }
    }
    return lchToArgb(0.20, chroma, lch.h)
}

import Foundation
import Testing
@testable import OctoKit

/// Every theme with every accent, a cover's included, and the glass tinted or not.
private let palettes: [(String, ResolvedPalette)] = ThemeChoice.allCases.flatMap { theme in
    AccentChoice.allCases.flatMap { choice in
        let seeds: [UInt32?] = choice == .fromArt ? [0xFFE0_1010, 0xFF10_30C0, 0xFFFF_E000, 0xFF0A_4010, 0xFF80_8080, nil] : [nil]
        return seeds.flatMap { art in
            [false, true].map { glass in ("\(theme) \(choice) \(art.map { String($0, radix: 16) } ?? "none") \(glass)",
                                          resolvePalette(theme, choice, art, glass)) }
        }
    }
}

@Suite struct ChoiceContrastTests {
    @Test func theSelectedSegmentStandsOutFromItsTrackInEveryThemeAndAccent() {
        for (name, p) in palettes {
            #expect(contrastRatio(p.accents.accent, p.card) >= 3.0, "\(name)")
            #expect(contrastRatio(p.accents.accent, p.raised) >= 3.0, "\(name)")
            #expect(contrastRatio(p.overArt.accent, OctoPalette.octo.card) >= 3.0, "\(name)")
        }
    }

    @Test func wordsReadOnEveryAccentAndAccentFill() {
        for (name, p) in palettes {
            for family in [p.accents, p.overArt] {
                #expect(contrastRatio(family.onAccent, family.accent) >= 4.5, "\(name)")
                #expect(contrastRatio(family.onFill, family.fill) >= 4.5, "\(name)")
            }
        }
    }

    @Test func wordsReadOnTheSelectedRowOfEveryThemeAndAccent() {
        for (name, p) in palettes {
            #expect(contrastRatio(p.text, p.accents.selected) >= 7.0, "\(name)")
            #expect(contrastOn(p.accents.selectedWords, p.accents.selected) >= 7.0, "\(name)")
            #expect(contrastRatio(0xFFFF_FFFF, p.overArt.selected) >= 7.0, "\(name)")
            #expect(contrastOn(p.overArt.selectedWords, p.overArt.selected) >= 7.0, "\(name)")
        }
    }
}

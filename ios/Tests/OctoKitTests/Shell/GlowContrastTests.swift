import Foundation
import Testing
@testable import OctoKit

@Suite struct GlowContrastTests {
    /// Covers from black to white and the bright primaries, the colors that push the glow hardest.
    private let covers: [UInt32] = [0xFF00_0000, 0xFFFF_FFFF, 0xFFE0_1010, 0xFF00_A000, 0xFF00_00FF, 0xFFFF_E000]

    /// The Subtle and Rich strengths, in thousandths.
    private let strengths = [140, 240]

    @Test func everyAccentReadsOverTheGlowInEveryTheme() {
        for theme in ThemeChoice.allCases {
            let page = resolvePalette(theme, .octo, nil, false)
            for choice in AccentChoice.fixed {
                let accent = themedAccent(theme, choice, nil)
                for cover in covers {
                    for alpha in strengths {
                        let glow = glowForCover(theme, cover, alpha, accent)
                        let under = overArgb(alphaArgb(glow, alpha), page.page)
                        #expect(contrastRatio(accent, under) >= 4.5, "\(theme) \(choice) over \(String(cover, radix: 16)) at \(alpha)")
                        #expect(contrastRatio(page.secondary, under) >= 7.0, "\(theme) secondary over \(String(cover, radix: 16)) at \(alpha)")
                    }
                }
            }
        }
    }
}

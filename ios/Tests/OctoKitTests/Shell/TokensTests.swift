import Foundation
import Testing
@testable import OctoKit

/// Colors keep each channel to 1/255.
private let byte = 1.0 / 255.0 + 1e-4

@Suite struct TokensTests {
    @Test func theDurationsAreTheSpecsSet() {
        for d in [80, 140, 160, 180, 200, 300] { #expect(OctoDuration.all.contains(d)) }
    }

    @Test func reducedMotionScalesEveryDuration() {
        #expect(OctoDuration.all.count >= 6)
        for base in OctoDuration.all {
            #expect(MotionScale.full.ms(base) == base)
            #expect(MotionScale.reduced.ms(base) == Int((Double(base) * 0.5).rounded(.toNearestOrAwayFromZero)))
            #expect(MotionScale.reduced.ms(base) < base)
        }
    }

    @Test func aDurationMultiplierScalesEveryDurationByItself() {
        let slow = MotionScale(duration: 2)
        for base in OctoDuration.all { #expect(slow.ms(base) == base * 2) }
    }

    @Test func reducedMotionStopsEveryTravelAndGrowth() {
        let reduced = MotionScale.reduced
        #expect(reduced.still)
        #expect(reduced.travel(OctoPress.liftMedium) == 0)
        #expect(reduced.scale(OctoPress.pop) == 1)
        #expect(reduced.scale(OctoPress.scale) == 1)
        #expect(abs(MotionScale.full.scale(OctoPress.pop) - OctoPress.pop) < 1e-9)
        #expect(MotionScale.full.travel(OctoPress.liftSmall) == 1)
    }

    @Test func theSystemAnimatorScaleOnlyCountsWhenOff() {
        #expect(MotionScale.of(systemAnimatorScale: 1, reduce: false) == .full)
        #expect(MotionScale.of(systemAnimatorScale: 1.5, reduce: false) == .full)
        #expect(MotionScale.of(systemAnimatorScale: 0, reduce: false) == .reduced)
        #expect(MotionScale.of(systemAnimatorScale: 1, reduce: true) == .reduced)
        #expect(MotionScale.of(systemReduced: true, reduce: false) == .reduced)
        #expect(MotionScale.of(systemReduced: false, reduce: false) == .full)
    }

    @Test func theCurvesStartAtZeroAndEndAtOne() {
        for curve in OctoEasing.all {
            #expect(abs(curve.at(0)) < 1e-3)
            #expect(abs(curve.at(1) - 1) < 1e-3)
        }
        #expect((1...99).contains { OctoEasing.overshoot.at(Double($0) / 100) > 1 })
    }

    @Test func wordsAreBlackOnALightFillAndWhiteOnADarkOne() {
        #expect(contentColorFor(.white) == .black)
        #expect(contentColorFor(.black) == .white)
        #expect(contentColorFor(OctoPalette.accent) == .black)
        #expect(contentColorFor(OctoPalette.accentFill) == .white)
        #expect(contentColorFor(OctoPalette.accentTonal) == .white)
    }

    @Test func theSwitchFromWhiteToBlackWordsIsAtAbout58Percent() {
        let grays = (0...255).map { RGBA($0, $0, $0) }
        let below = grays.last { perceivedLightness($0) <= lightFillThreshold }!
        let above = grays.first { perceivedLightness($0) > lightFillThreshold }!
        #expect(contentColorFor(below) == .white)
        #expect(contentColorFor(above) == .black)
        #expect(abs(perceivedLightness(above) - 0.58) < 0.01)
        #expect(abs(perceivedLightness(RGBA(128, 128, 128)) - 0.536) < 0.005)
    }

    @Test func theAccentFamilyIsDerivedFromTheAccentInEveryTheme() {
        for theme in ThemeChoice.allCases {
            for choice in AccentChoice.allCases {
                for glass in [false, true] {
                    let p = resolvePalette(theme, choice, choice == .fromArt ? 0xFFE0_1010 : nil, glass)
                    let f = p.accents
                    let name = "\(theme) \(choice) \(glass)"
                    if theme.light {
                        #expect((14...40).contains { mixArgb(p.card, f.accent, $0 * 10) == f.selected }, "\(name)")
                        #expect(f.tonal == mixArgb(p.card, f.accent, 80), "\(name)")
                        #expect(f.focusRing == alphaArgb(f.accent, 1000), "\(name)")
                        #expect(f.fill == f.accent, "\(name)")
                    } else {
                        #expect(f.selected == mixArgb(0xFF00_0000, f.accent, 400), "\(name)")
                        #expect(f.tonal == mixArgb(0xFF1E_1E1E, f.accent, 100), "\(name)")
                        #expect(f.focusRing == alphaArgb(f.accent, 700), "\(name)")
                        #expect(f.fill == mixArgb(f.accent, 0xFF00_0000, 450), "\(name)")
                    }
                    #expect(f.menuPress == alphaArgb(f.selected, 550), "\(name)")
                    #expect(f.glassAccent == (glass ? f.accent : theme.base.accent), "\(name)")
                }
            }
        }
        #expect(OctoPalette.destructive == OctoPalette.signalRed.opacity(0.85))
    }

    @Test func theDerivedColorsAreTheNamedOnes() {
        let p = resolvePalette(.octo, .octo, nil, false)
        #expect(p.page == 0xFF0C_0C0D)
        #expect(p.card == 0xFF1A_1A1B)
        #expect(p.raised == 0xFF22_2324)
        #expect(p.accents.tonal == 0xFF2A_2D2E)
        #expect(p.accents.selected == 0xFF3C_474A)
        #expect(p.accents.hover == 0xFFAD_C4CC)
        #expect(p.accents.fill == 0xFF53_6166)
        #expect(p.sheet == 0xF713_1416)
        #expect(p.secondary == 0xFF98_B2BA)
        // The shared tokens are Octo's resolved colors.
        #expect(OctoPalette.surfaceRaised.hex == 0x222324)
        #expect(OctoPalette.accentTonal.hex == 0x2A2D2E)
        #expect(OctoPalette.accentSelected.hex == 0x3C474A)
        #expect(OctoPalette.accentFill.hex == 0x536166)
        #expect(OctoPalette.sheetFill.hex == 0x131416)
        #expect(OctoPalette.textSecondary.hex == 0x98B2BA)
        #expect(abs(OctoPalette.sheetFill.alpha - 247.0 / 255.0) < 1e-9)
    }

    @Test func theTextHierarchyIsTheInkAtFallingOpacitiesInEveryTheme() {
        for theme in ThemeChoice.allCases {
            let p = resolvePalette(theme, .octo, nil, false)
            let levels = [p.inkPrimary, p.inkSecondary, p.inkTertiary, p.inkQuaternary, p.inkQuinary]
            let alphas = [850, theme.base.inkSecondary, 250, 100, 50].map { (255 * $0 + 500) / 1000 }
            for (want, color) in zip(alphas, levels) {
                #expect(Int(color >> 24) == want, "\(theme)")
                #expect(color & 0xFF_FFFF == p.ink & 0xFF_FFFF, "\(theme)")
            }
            // Secondary ink reads at 4.5:1 on the page and the card.
            #expect(contrastOn(p.inkSecondary, p.page) >= 4.5 && contrastOn(p.inkSecondary, p.card) >= 4.5, "\(theme)")
        }
        #expect([217, 140, 64, 26, 13] == [OctoInk.primary, OctoInk.secondary, OctoInk.tertiary, OctoInk.quaternary, OctoInk.quinary]
            .map { Int(($0.alpha * 255).rounded()) })
    }

    @Test func theRadiiAreTheSpecs() {
        #expect([OctoRadii.chrome, OctoRadii.chromeSmall, OctoRadii.row] == [10, 6, 12])
        #expect([OctoRadii.artXs, OctoRadii.artS, OctoRadii.artM, OctoRadii.artL] == [3, 6, 8, 12])
        #expect(OctoRadii.dialog == 12)
    }

    @Test func anIconGesturePeaksAt45PercentAndRestsAtEitherEnd() {
        #expect(accentStrength(0) == 0)
        #expect(accentStrength(1) == 0)
        #expect(abs(accentStrength(accentPeak) - 1) < 1e-3)
        #expect(accentStrength(0.2) < 1 && accentStrength(0.8) < 1)
        #expect(abs(accentPose(.pulse, accentPeak).scale - 1.2) < 1e-3)
        #expect(abs(accentPose(.slideForward, accentPeak).dx - 4) < 1e-3)
        #expect(abs(accentPose(.slideBack, accentPeak).dx + 4) < 1e-3)
        for accent in IconAccent.allCases { #expect(accentPose(accent, accentPeak, distance: 0) == .rest) }
    }

    @Test func theTabsThePillPassesFlashAndTheOneItLandsOnDoesNot() {
        #expect(abs(gearShiftTint(1, pill: 1, selected: 3) - gearShiftTintMax) < 1e-9)
        #expect(abs(gearShiftTint(2, pill: 1.5, selected: 3) - gearShiftTintMax / 2) < 1e-9)
        #expect(gearShiftTint(3, pill: 3, selected: 3) == 0)
        #expect(gearShiftTint(0, pill: 2, selected: 3) == 0)
    }

    @Test func aSliderSnapsToItsSteps() {
        #expect(abs(snapToSteps(0.3, 4) - 0.25) < 1e-9)
        #expect(abs(snapToSteps(0.4, 4) - 0.5) < 1e-9)
        #expect(abs(snapToSteps(0.3, 0) - 0.3) < 1e-9)
        #expect(snapToSteps(1.4, 0) == 1)
        #expect(!(snapToSteps(-1, 3) < 0))
    }

    private func thumb(_ position: Double, _ stretch: Double) -> SwitchThumb {
        switchThumb(track: 36, thumb: 18, padding: 2, position: position, stretch: stretch)
    }

    @Test func theSwitchThumbSitsAtEitherEndAtRest() {
        #expect(thumb(0, 0) == SwitchThumb(2, 18))
        #expect(thumb(1, 0) == SwitchThumb(16, 18))
    }

    @Test func aHeldThumbStretches18PercentFromTheSideItIsLeaving() {
        let off = thumb(0, 1)
        #expect(abs(off.width - 18 * switchSquish) < 1e-4)
        #expect(abs(off.x - 2) < 1e-4)
        let on = thumb(1, 1)
        #expect(abs(on.width - 18 * switchSquish) < 1e-4)
        #expect(abs(on.x + on.width - 34) < 1e-4)
    }

    @Test func theSwitchSizesAreTheSpecs() {
        #expect([SwitchSize.small.width, SwitchSize.small.height, SwitchSize.small.thumb] == [30, 18, 14])
        #expect([SwitchSize.standard.width, SwitchSize.standard.height, SwitchSize.standard.thumb] == [36, 22, 18])
        #expect([SwitchSize.large.width, SwitchSize.large.height, SwitchSize.large.thumb] == [44, 26, 22])
    }

    @Test func theButtonSizesAreTheSpecs() {
        let sizes: [ButtonSize] = [.extraSmall, .small, .medium, .large]
        #expect(sizes.map(\.height) == [24, 30, 36, 50])
        #expect(sizes.map(\.dense) == [20, 24, 28, 40])
    }

    @Test func aSpringsResponseComesFromItsStiffness() {
        #expect(abs(SpringSpec(0.75, 200).response - 0.4443) < 1e-3)
        #expect(OctoSprings.calm == SpringSpec(0.9, 500))
    }
}

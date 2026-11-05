import Foundation
import Testing
@testable import OctoKit

@Suite struct ThemeTests {
    @Test func matchUsesTheSlotForTheDeviceMode() {
        let on = ThemeSettings(theme: .paper, light: .daylight, dark: .moss, match: true)
        #expect(activeTheme(on, deviceDark: true) == .moss)
        #expect(activeTheme(on, deviceDark: false) == .daylight)
        var off = on
        off.match = false
        #expect(activeTheme(off, deviceDark: true) == .paper)
        #expect(activeTheme(off, deviceDark: false) == .paper)
    }

    @Test func pickingFillsTheSlot() {
        let start = ThemeSettings(theme: .octo, light: .daylight, dark: .octo)
        for choice in ThemeChoice.allCases {
            let picked = pickTheme(start, choice)
            #expect(picked.theme == choice)
            if choice.light {
                #expect(picked.light == choice)
                #expect(picked.dark == start.dark)
            } else {
                #expect(picked.dark == choice)
                #expect(picked.light == start.light)
            }
        }
    }

    @Test func turningMatchOffKeepsWhatShows() {
        let on = ThemeSettings(theme: .paper, light: .daylight, dark: .moss, match: true)
        #expect(setMatch(on, on: false, deviceDark: true).theme == .moss)
        #expect(setMatch(on, on: false, deviceDark: false).theme == .daylight)
        #expect(setMatch(on, on: false, deviceDark: true).match == false)
        var off = on
        off.match = false
        #expect(setMatch(off, on: true, deviceDark: true).match == true)
    }

    @Test func unknownAndWrongKindNamesCoerce() {
        let s = themeSettings("Neon", "Moss", "Paper", false)
        #expect(s.theme == .octo)
        #expect(s.light == .daylight)
        #expect(s.dark == .octo)
    }

    @Test func storedNamesNeverChange() {
        #expect(ThemeChoice.allCases.map(\.storedName) == ["Octo", "Moss", "Dusk", "Daylight", "Paper"])
    }

    @Test func theMatchLineNamesBothSlots() {
        #expect(matchHelp(ThemeSettings()) == "By day: Daylight. By night: Octo.")
    }
}

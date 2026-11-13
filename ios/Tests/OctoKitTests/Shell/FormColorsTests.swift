import Foundation
import Testing
@testable import OctoKit

/// The sign-in form's word colors against every theme, accent, cover seed and glass setting in the
/// shared resolved.txt.
@Suite struct FormColorsTests {
    /// One resolved palette: its key ("<theme>/<accent>[/<art>]/glass-<on|off>") and its roles.
    struct Row {
        let head: String
        let roles: [String: String]

        var light: Bool { roles["light"] == "true" }

        func color(_ role: String) throws -> UInt32 {
            let text = try #require(roles[role], "\(head) has no \(role)")
            return try #require(UInt32(text.dropFirst(), radix: 16), "\(head)/\(role) is not a color")
        }
    }

    /// The shared fixture, found by walking up from this file, one row per palette.
    static func rows() throws -> [Row] {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        var found: URL?
        while found == nil && dir.path != "/" && !dir.path.isEmpty {
            let candidate = dir.appendingPathComponent("shared/core/src/commonTest/resources/themes/resolved.txt")
            if FileManager.default.fileExists(atPath: candidate.path) { found = candidate }
            dir = dir.deletingLastPathComponent()
        }
        let url = try #require(found, "resolved.txt not found above \(#filePath)")
        let text = try String(contentsOf: url, encoding: .utf8).replacingOccurrences(of: "\r", with: "")
        var roles: [String: [String: String]] = [:]
        for line in text.split(separator: "\n") {
            guard let eq = line.firstIndex(of: "="), let slash = line[..<eq].lastIndex(of: "/") else { continue }
            roles[String(line[..<slash]), default: [:]][String(line[line.index(after: slash)..<eq])] = String(line[line.index(after: eq)...])
        }
        return roles.keys.sorted().map { Row(head: $0, roles: roles[$0] ?? [:]) }
    }

    /// The palette a row was resolved from.
    static func palette(_ head: String) throws -> ResolvedPalette {
        let parts = head.split(separator: "/").map(String.init)
        let theme = try #require(ThemeChoice.allCases.first { $0.storedName == parts[0] })
        let choice = try #require(AccentChoice.allCases.first { $0.storedName == parts[1] })
        let art: UInt32? = choice == .fromArt && parts[2] != "none" ? UInt32(parts[2], radix: 16) : nil
        return resolvePalette(theme, choice, art, parts.last == "glass-on")
    }

    /// The scrim behind a sheet: black at 45%, over black or over white.
    static let scrims: [UInt32] = [overArgb(alphaArgb(0xFF00_0000, 450), 0xFF00_0000), overArgb(alphaArgb(0xFF00_0000, 450), 0xFFFF_FFFF)]

    @Test func theFixtureHoldsEveryThemeAccentCoverAndGlass() throws {
        let rows = try Self.rows()
        #expect(rows.count == 150)
        #expect(Set(rows.compactMap { $0.roles["theme"] }).count == 5)
    }

    @Test func theRedsAreTheChosenOnes() {
        #expect(errorWordsLight == 0xFFB4_2318)
        #expect(errorWordsDark == 0xFFF9_7C7C)
    }

    /// Red when an error red falls under 4.5:1 on any page, card, raised or sheet surface of any
    /// theme, accent, cover or glass setting.
    @Test func errorWordsReadOnEveryPageCardRaisedAndSheet() throws {
        for row in try Self.rows() {
            let words = errorWordsFor(light: row.light)
            let resolved = try Self.palette(row.head).errorWords
            #expect(resolved == words, "\(row.head)")
            let page = try row.color("page")
            let card = try row.color("card")
            let raised = try row.color("raised")
            let sheet = try row.color("sheet")
            var surfaces: [(String, UInt32)] = [("page", page), ("card", card), ("raised", raised), ("sheet over page", overArgb(sheet, page))]
            for (i, scrim) in Self.scrims.enumerated() { surfaces.append(("sheet over scrim \(i)", overArgb(sheet, scrim))) }
            for (name, surface) in surfaces {
                #expect(contrastRatio(words, surface) >= 4.5, "\(row.head) error words on \(name)")
            }
        }
    }

    /// The darkest a glow may leave a light theme's page, or the lightest it may leave a dark one's,
    /// as luminance: where text or secondary words would fall to 7:1, or the theme's accent,
    /// complement or third to 4.5:1, whichever comes first.
    static func glowBound(_ row: Row) throws -> Double {
        let needs: [(String, Double)] = [("text", 7), ("secondary", 7), ("accents.accent", 4.5), ("complement", 4.5), ("third", 4.5)]
        var bounds: [Double] = []
        for need in needs {
            let luminance = relativeLuminance(try row.color(need.0))
            bounds.append(row.light ? (luminance + 0.05) * need.1 : (luminance + 0.05) / need.1)
        }
        return (row.light ? bounds.max() : bounds.min()).map { $0 - 0.05 } ?? 0
    }

    /// Red when an error red falls under 4.5:1 over the light glow floor or the dark glow ceiling of
    /// any theme.
    @Test func errorWordsReadOverTheLightGlowFloorAndTheDarkGlowCeiling() throws {
        let themes = try Self.rows().filter { $0.head.hasSuffix("/Octo/glass-off") }
        #expect(themes.count == 5)
        for row in themes {
            let bound = try Self.glowBound(row)
            let words = relativeLuminance(errorWordsFor(light: row.light))
            let ratio = row.light ? (bound + 0.05) / (words + 0.05) : (words + 0.05) / (bound + 0.05)
            #expect(ratio >= 4.5, "\(row.head) error words over the glow bound, \(ratio)")
        }
    }

    /// Red when the Optional tag's words fall under 4.5:1 on its fill, laid on a raised row, for any
    /// theme, accent, cover or glass setting.
    @Test func theOptionalTagReadsOverRaisedEverywhere() throws {
        for row in try Self.rows() {
            let palette = try Self.palette(row.head)
            let accent = try row.color("accents.accent")
            let tonal = try row.color("accents.tonal")
            let raised = try row.color("raised")
            #expect(palette.tagWords == accent && palette.tagFill == tonal, "\(row.head)")
            let fill = overArgb(tonal, raised)
            #expect(contrastRatio(overArgb(accent, fill), fill) >= 4.5, "\(row.head) tag")
        }
    }

    /// Red when an entry's words or placeholder fall under the themes table on the page color, the
    /// entry well's fill.
    @Test func entryWordsReadInTheWell() throws {
        for row in try Self.rows() {
            let page = try row.color("page")
            let text = try row.color("text")
            let muted = try row.color("muted")
            #expect(contrastRatio(text, page) >= 7, "\(row.head) entry words")
            #expect(contrastOn(muted, page) >= 4.5, "\(row.head) placeholder")
        }
    }
}

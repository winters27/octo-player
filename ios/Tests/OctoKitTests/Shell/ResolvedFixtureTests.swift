import Foundation
import Testing
@testable import OctoKit

/// The resolver gives the same bytes as the other apps': every role of every theme, accent, cover
/// seed and glass setting, against the fixture the shared code writes.
@Suite struct ResolvedFixtureTests {
    private let artSeeds: [UInt32?] = [0xFFE0_1010, 0xFF10_30C0, 0xFFFF_E000, 0xFF0A_4010, 0xFF80_8080, nil]

    private func hex(_ argb: UInt32) -> String { "#" + String(format: "%08X", argb) }

    private func familyEntries(_ prefix: String, _ f: AccentFamily) -> [(String, String)] {
        [
            ("\(prefix).accent", hex(f.accent)),
            ("\(prefix).hover", hex(f.hover)),
            ("\(prefix).tonal", hex(f.tonal)),
            ("\(prefix).selected", hex(f.selected)),
            ("\(prefix).selectedWords", hex(f.selectedWords)),
            ("\(prefix).menuPress", hex(f.menuPress)),
            ("\(prefix).fill", hex(f.fill)),
            ("\(prefix).onFill", hex(f.onFill)),
            ("\(prefix).onAccent", hex(f.onAccent)),
            ("\(prefix).focusRing", hex(f.focusRing)),
            ("\(prefix).glassAccent", hex(f.glassAccent)),
        ]
    }

    /// Every resolved role keyed "<theme>/<accent>[/<art>]/glass-<on|off>/<role>".
    private func fixtureOf() -> [String: String] {
        var out: [String: String] = [:]
        for theme in ThemeChoice.allCases {
            for choice in AccentChoice.allCases {
                let seeds: [UInt32?] = choice == .fromArt ? artSeeds : [nil]
                for art in seeds {
                    for glass in [false, true] {
                        let p = resolvePalette(theme, choice, art, glass)
                        let artPart = choice == .fromArt ? "/" + (art.map { String(format: "%08X", $0) } ?? "none") : ""
                        let head = "\(theme.storedName)/\(choice.storedName)\(artPart)/glass-\(glass ? "on" : "off")"
                        var roles: [(String, String)] = [
                            ("theme", p.theme.storedName),
                            ("light", p.light ? "true" : "false"),
                            ("page", hex(p.page)),
                            ("card", hex(p.card)),
                            ("raised", hex(p.raised)),
                            ("sheet", hex(p.sheet)),
                            ("edge", hex(p.edge)),
                            ("backgroundSecondary", hex(p.backgroundSecondary)),
                            ("text", hex(p.text)),
                            ("secondary", hex(p.secondary)),
                            ("muted", hex(p.muted)),
                            ("inkPrimary", hex(p.inkPrimary)),
                            ("inkSecondary", hex(p.inkSecondary)),
                            ("inkTertiary", hex(p.inkTertiary)),
                            ("inkQuaternary", hex(p.inkQuaternary)),
                            ("inkQuinary", hex(p.inkQuinary)),
                            ("ink", hex(p.ink)),
                            ("offTrack", hex(p.offTrack)),
                            ("complement", hex(p.complement)),
                            ("third", hex(p.third)),
                            ("shadow", String(p.shadow)),
                        ]
                        for (i, c) in p.glow.enumerated() { roles.append(("glow.\(i)", hex(c))) }
                        roles += familyEntries("accents", p.accents)
                        roles += familyEntries("overArt", p.overArt)
                        for (role, value) in roles { out["\(head)/\(role)"] = value }
                    }
                }
            }
        }
        return out
    }

    /// The shared fixture, found by walking up from this file to the repository root.
    private func fixtureFile() -> URL? {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while dir.path != "/" && !dir.path.isEmpty {
            let candidate = dir.appendingPathComponent("shared/core/src/commonTest/resources/themes/resolved.txt")
            if FileManager.default.fileExists(atPath: candidate.path) { return candidate }
            dir = dir.deletingLastPathComponent()
        }
        return nil
    }

    @Test func resolvedColorsMatchTheFixture() throws {
        guard let url = fixtureFile() else {
            Issue.record("shared/core/src/commonTest/resources/themes/resolved.txt not found above \(#filePath)")
            return
        }
        let text = try String(contentsOf: url, encoding: .utf8).replacingOccurrences(of: "\r", with: "")
        var expected: [String: String] = [:]
        var keys: [String] = []
        for line in text.split(separator: "\n", omittingEmptySubsequences: true) {
            guard let eq = line.firstIndex(of: "=") else {
                Issue.record("line without a value: \(line)")
                continue
            }
            let key = String(line[..<eq])
            keys.append(key)
            expected[key] = String(line[line.index(after: eq)...])
        }
        #expect(keys.count == 6810)
        #expect(keys == keys.sorted { $0.utf8.lexicographicallyPrecedes($1.utf8) })
        let actual = fixtureOf()
        #expect(actual.count == expected.count)
        let differing = Set(actual.keys).union(expected.keys).sorted().filter { actual[$0] != expected[$0] }
        for key in differing.prefix(40) {
            Issue.record("\(key): fixture \(expected[key] ?? "missing"), Swift \(actual[key] ?? "missing")")
        }
        #expect(differing.isEmpty, "\(differing.count) roles differ")
    }
}

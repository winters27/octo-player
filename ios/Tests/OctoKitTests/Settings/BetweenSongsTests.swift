import Foundation
import Testing
@testable import OctoKit

/// A phone's player settings store in memory: values by key, as the phone's store gives them
/// back, with the writes the planners give applied in order.
struct MemoryStore {
    var values: [String: Any]

    init(_ values: [String: Any] = [:]) { self.values = values }

    func value(_ key: String) -> Any? { values[key] }

    mutating func apply(_ writes: [StoredKeyWrite]) {
        for write in writes {
            switch write {
            case .set(let key, let value): values[key] = value.plain
            case .remove(let key): values.removeValue(forKey: key)
            }
        }
    }

    /// The Between songs settings the device shows.
    var shown: PlayerPrefs { PlayerPrefs().withStoredBetweenSongs { values[$0] } }

    var keys: Set<String> { Set(values.keys) }
}

@Suite struct BetweenSongsTests {
    // Mapping

    @Test func eachChoiceGivesItsLongestBlend() {
        #expect(crossfadeMsOf(.gapless, crossfadeSeconds: 8) == 0)
        #expect(crossfadeMsOf(.gapless, crossfadeSeconds: 16) == 0)
        #expect(crossfadeMsOf(.crossfade, crossfadeSeconds: 8) == 8_000)
        #expect(crossfadeMsOf(.crossfade, crossfadeSeconds: 2) == 2_000)
        #expect(crossfadeMsOf(.crossfade, crossfadeSeconds: 16) == 16_000)
        // A length outside Crossfade's range is its nearest end.
        #expect(crossfadeMsOf(.crossfade, crossfadeSeconds: 0) == 2_000)
        #expect(crossfadeMsOf(.crossfade, crossfadeSeconds: 30) == 16_000)
        // Automix picks its own lengths, whatever Crossfade's is.
        for seconds in [2, 8, 16] { #expect(crossfadeMsOf(.automix, crossfadeSeconds: seconds) == automixLongestBlendMs) }
        #expect(automixLongestBlendMs == 8_000)
        #expect(automixLongestIntroMs == 16_000)
    }

    @Test func eachChoiceGivesThePlannerItsSettings() {
        for choice in BetweenSongs.allCases {
            for curve in FadeCurve.allCases {
                for sweeps in [true, false] {
                    for tempo in [true, false] {
                        let settings = automixSettingsOf(choice, crossfadeSeconds: 12, crossfadeCurve: curve, filterSweeps: sweeps, matchTempo: tempo)
                        let expected = AutomixSettings(maxOverlapMs: crossfadeMsOf(choice, crossfadeSeconds: 12), longestIntroMs: automixLongestIntroMs,
                                                       smart: choice == .automix, filterSweeps: sweeps, beatMatch: tempo, fadeCurve: curve)
                        #expect(settings == expected, "\(choice) \(curve) sweeps \(sweeps) tempo \(tempo)")
                    }
                }
            }
        }
        #expect(automixSettingsOf(.crossfade, crossfadeSeconds: 12, crossfadeCurve: .linear, filterSweeps: true, matchTempo: true).maxOverlapMs == 12_000)
        #expect(automixSettingsOf(.gapless, crossfadeSeconds: 8, crossfadeCurve: .equalPower, filterSweeps: true, matchTempo: true).maxOverlapMs == 0)
        #expect(automixSettingsOf(.automix, crossfadeSeconds: 3, crossfadeCurve: .equalPower, filterSweeps: true, matchTempo: true).maxOverlapMs == 8_000)
        // The player's settings give the same.
        var prefs = PlayerPrefs()
        prefs.betweenSongs = .crossfade
        prefs.crossfadeSeconds = 5
        prefs.crossfadeCurve = .sCurve
        #expect(prefs.automix == automixSettingsOf(.crossfade, crossfadeSeconds: 5, crossfadeCurve: .sCurve, filterSweeps: true, matchTempo: true))
        #expect(prefs.crossfadeMs == 5_000)
        // Automix's longest blend whatever Crossfade's length is, and none for Gapless.
        prefs.betweenSongs = .automix
        #expect(prefs.crossfadeMs == automixLongestBlendMs)
        prefs.betweenSongs = .gapless
        #expect(prefs.crossfadeMs == 0)
    }

    @Test func theChoiceIsStoredByName() {
        #expect(BetweenSongs.allCases.map(\.storedName) == ["Gapless", "Crossfade", "Automix"])
        for choice in BetweenSongs.allCases { #expect(BetweenSongs.ofStored(choice.storedName) == choice) }
        #expect(BetweenSongs.ofStored(nil) == nil)
        #expect(BetweenSongs.ofStored("automix") == nil)
        #expect(BetweenSongs.ofStored("Smart") == nil)
    }

    // Untouched rule

    /// Every value each key can be stored with that the rule tells apart, nil for not stored.
    static let everyCombination: [StoredBetweenSongs] = {
        var all: [StoredBetweenSongs] = []
        for b in [nil, "Gapless", "Crossfade", "Automix", "Later"] {
            for c in [nil, false, true] {
                for s in [nil, 8, 2, 5, 16] {
                    for smart in [nil, true, false] {
                        for sweeps in [nil, true, false] {
                            for tempo in [nil, true, false] {
                                for curve in [nil, "EqualPower", "Linear", "MixOnly", "Unknown"] {
                                    all.append(StoredBetweenSongs(betweenSongs: b, crossfade: c, crossfadeSeconds: s, smartTransitions: smart,
                                                                  filterSweeps: sweeps, matchTempo: tempo, crossfadeCurve: curve))
                                }
                            }
                        }
                    }
                }
            }
        }
        return all
    }()

    /// The rule in its own words: untouched when the choice is missing and either crossfade is
    /// missing, or crossfade is off with the length missing or 8, smart transitions missing or on,
    /// Filter sweeps missing or on, Match tempo missing or off and the curve missing or equal power
    /// (an unknown name reads as equal power).
    func expected(_ k: StoredBetweenSongs) -> BetweenSongsReading {
        let stored: BetweenSongs? = switch k.betweenSongs ?? "" {
        case "Gapless": .gapless
        case "Crossfade": .crossfade
        case "Automix": .automix
        default: nil
        }
        if let stored { return BetweenSongsReading(stored, matchTempo: k.matchTempo ?? true, stored: true, untouched: false) }
        let curveAtDefault = k.crossfadeCurve == nil || k.crossfadeCurve == "EqualPower" || k.crossfadeCurve == "Unknown"
        let oldDefaults = k.crossfade == false && [nil, 8].contains(k.crossfadeSeconds) && [nil, true].contains(k.smartTransitions)
            && [nil, true].contains(k.filterSweeps) && [nil, false].contains(k.matchTempo) && curveAtDefault
        if k.crossfade == nil || oldDefaults { return BetweenSongsReading(.automix, matchTempo: true, stored: false, untouched: true) }
        let choice: BetweenSongs = k.crossfade == false ? .gapless : k.smartTransitions == false ? .crossfade : .automix
        let tempo = choice == .automix ? true : k.matchTempo ?? true
        return BetweenSongsReading(choice, matchTempo: tempo, stored: false, untouched: false)
    }

    @Test func everyCombinationOfStoredKeysGivesTheRulesChoice() {
        for keys in Self.everyCombination where readBetweenSongs(keys) != expected(keys) {
            Issue.record("\(keys): \(readBetweenSongs(keys)), expected \(expected(keys))")
        }
        #expect(Self.everyCombination.count == 5 * 3 * 5 * 3 * 3 * 3 * 5)
    }

    @Test func aNewInstallAndOneThatNeverTouchedCrossfadeAreUntouched() {
        let untouched = BetweenSongsReading(.automix, matchTempo: true, stored: false, untouched: true)
        #expect(readBetweenSongs(StoredBetweenSongs()) == untouched)
        // Other settings changed, crossfade itself never stored.
        #expect(readBetweenSongs(StoredBetweenSongs(crossfadeSeconds: 12, smartTransitions: false, matchTempo: false)) == untouched)
    }

    /// Every key at the default the apps had before the choice, as a restore of their backup writes them.
    static let oldDefaults = StoredBetweenSongs(crossfade: false, crossfadeSeconds: 8, smartTransitions: true, filterSweeps: true,
                                                matchTempo: false, crossfadeCurve: "EqualPower")

    /// The same keys as a phone stores them.
    static var oldDefaultKeys: [String: Any] {
        ["crossfade": false, "crossfade_seconds": 8, "smart_transitions": true, "filter_sweeps": true, "match_tempo": false,
         "crossfade_curve": "EqualPower"]
    }

    @Test func aRestoredPhoneAtTheOldDefaultsIsUntouched() {
        let restored = Self.oldDefaults
        #expect(readBetweenSongs(restored).untouched)
        // Turned crossfade on and off again: still untouched.
        #expect(readBetweenSongs(StoredBetweenSongs(crossfade: false)).untouched)
        // Any setting away from its old default is a choice of Gapless.
        var changes: [StoredBetweenSongs] = []
        for change in 0..<5 {
            var changed = restored
            switch change {
            case 0: changed.crossfadeSeconds = 9
            case 1: changed.smartTransitions = false
            case 2: changed.filterSweeps = false
            case 3: changed.matchTempo = true
            default: changed.crossfadeCurve = "SCurve"
            }
            changes.append(changed)
        }
        for changed in changes {
            #expect(readBetweenSongs(changed) == BetweenSongsReading(.gapless, matchTempo: changed.matchTempo ?? true, stored: false, untouched: false),
                    "\(changed)")
        }
    }

    @Test func crossfadeOnIsAutomixWithMatchTempoOrCrossfadeWithSmartOff() {
        #expect(readBetweenSongs(StoredBetweenSongs(crossfade: true)) == BetweenSongsReading(.automix, matchTempo: true, stored: false, untouched: false))
        #expect(readBetweenSongs(StoredBetweenSongs(crossfade: true, smartTransitions: true, matchTempo: false))
            == BetweenSongsReading(.automix, matchTempo: true, stored: false, untouched: false))
        #expect(readBetweenSongs(StoredBetweenSongs(crossfade: true, smartTransitions: false, matchTempo: false))
            == BetweenSongsReading(.crossfade, matchTempo: false, stored: false, untouched: false))
        #expect(readBetweenSongs(StoredBetweenSongs(crossfade: true, smartTransitions: false))
            == BetweenSongsReading(.crossfade, matchTempo: true, stored: false, untouched: false))
    }

    @Test func aStoredChoiceIsTheChoice() {
        for choice in BetweenSongs.allCases {
            let keys = StoredBetweenSongs(betweenSongs: choice.storedName, crossfade: false, matchTempo: false)
            #expect(readBetweenSongs(keys) == BetweenSongsReading(choice, matchTempo: false, stored: true, untouched: false))
        }
        // An unknown stored choice, as from a newer app, falls back to the mirrors.
        #expect(readBetweenSongs(StoredBetweenSongs(betweenSongs: "Later", crossfade: true, smartTransitions: false)).choice == .crossfade)
    }

    @Test func theKeysReadByName() {
        let prefs: [String: Any] = [
            "between_songs": "Crossfade", "crossfade": true, "crossfade_seconds": Int64(12), "smart_transitions": false,
            "filter_sweeps": true, "match_tempo": false, "crossfade_curve": "Linear", "unrelated": 3,
        ]
        #expect(StoredBetweenSongs.read { prefs[$0] } == StoredBetweenSongs(betweenSongs: "Crossfade", crossfade: true, crossfadeSeconds: 12,
                                                                           smartTransitions: false, filterSweeps: true, matchTempo: false,
                                                                           crossfadeCurve: "Linear"))
        #expect(StoredBetweenSongs.read { _ in nil } == StoredBetweenSongs())
        // A value of the wrong type is not a stored key.
        #expect(StoredBetweenSongs.read { $0 == "crossfade" ? "yes" : nil } == StoredBetweenSongs())
        #expect(StoredBetweenSongs.read { $0 == "crossfade_seconds" ? "12" : nil } == StoredBetweenSongs())
    }

    // Writes

    @Test func aWriteCarriesTheMirrorsAnOlderAppReads() {
        #expect(BetweenSongsWrite(.gapless).crossfade == false && BetweenSongsWrite(.gapless).smartTransitions == false)
        #expect(BetweenSongsWrite(.crossfade).crossfade == true && BetweenSongsWrite(.crossfade).smartTransitions == false)
        #expect(BetweenSongsWrite(.automix).crossfade == true && BetweenSongsWrite(.automix).smartTransitions == true)
        // An older app reading the mirrors back gets the same choice.
        for choice in BetweenSongs.allCases {
            let w = BetweenSongsWrite(choice)
            let older = StoredBetweenSongs(crossfade: w.crossfade, crossfadeSeconds: 12, smartTransitions: w.smartTransitions)
            #expect(readBetweenSongs(older).choice == choice)
        }
        // As stored keys: the choice, its mirrors, then Match tempo when it turns on.
        #expect(BetweenSongsWrite(.crossfade).keyWrites == [
            .set("between_songs", .string("Crossfade")), .set("crossfade", .bool(true)), .set("smart_transitions", .bool(false)),
        ])
        #expect(BetweenSongsWrite(.automix, matchTempo: true).keyWrites == [
            .set("between_songs", .string("Automix")), .set("crossfade", .bool(true)), .set("smart_transitions", .bool(true)),
            .set("match_tempo", .bool(true)),
        ])
    }

    @Test func theDerivedWriteStoresWhatTheDeviceShows() {
        for keys in Self.everyCombination {
            let reading = readBetweenSongs(keys)
            let write = derivedBetweenSongsWrite(keys)
            guard !reading.stored else {
                #expect(write == nil, "\(keys)")
                continue
            }
            guard let write else {
                Issue.record("no write for \(keys)")
                continue
            }
            #expect(write.choice == reading.choice, "\(keys)")
            // Match tempo is written only when Automix turned it on.
            let turned = reading.choice == .automix && keys.matchTempo != true
            #expect(write.matchTempo == (turned ? true : nil), "\(keys)")
            // Once written, the device reads the same.
            var after = keys
            after.betweenSongs = write.choice.storedName
            after.crossfade = write.crossfade
            after.smartTransitions = write.smartTransitions
            after.matchTempo = write.matchTempo ?? keys.matchTempo
            var same = reading
            same.stored = true
            same.untouched = false
            #expect(readBetweenSongs(after) == same, "\(keys)")
        }
    }

    @Test func theFirstReadStoresAChoiceButLeavesAnUntouchedDeviceAlone() {
        #expect(firstReadBetweenSongsWrite(StoredBetweenSongs()) == nil)
        #expect(firstReadBetweenSongsWrite(StoredBetweenSongs(crossfade: false, crossfadeSeconds: 8)) == nil)
        #expect(firstReadBetweenSongsWrite(StoredBetweenSongs(betweenSongs: "Gapless", crossfade: true)) == nil)
        #expect(firstReadBetweenSongsWrite(StoredBetweenSongs(crossfade: true, smartTransitions: false)) == BetweenSongsWrite(.crossfade))
        #expect(firstReadBetweenSongsWrite(StoredBetweenSongs(crossfade: true)) == BetweenSongsWrite(.automix, matchTempo: true))
        #expect(firstReadBetweenSongsWrite(StoredBetweenSongs(crossfade: true, matchTempo: true)) == BetweenSongsWrite(.automix))
        #expect(firstReadBetweenSongsWrite(StoredBetweenSongs(crossfade: false, crossfadeSeconds: 4)) == BetweenSongsWrite(.gapless))
        for keys in Self.everyCombination {
            let reading = readBetweenSongs(keys)
            let expected = reading.stored || reading.untouched ? nil : derivedBetweenSongsWrite(keys)
            #expect(firstReadBetweenSongsWrite(keys) == expected, "\(keys)")
        }
    }

    @Test func theFirstEditOnAnUntouchedDeviceKeepsAutomixAndMatchTempoThenMakesTheChange() {
        let untouched = StoredBetweenSongs(crossfade: false, crossfadeSeconds: 8, matchTempo: false)
        let derived = BetweenSongsWrite(.automix, matchTempo: true)
        // A change to another row: the derived write alone, the row's key after it.
        #expect(betweenSongsEdit(untouched) == [derived])
        // A change of the choice: the derived write, then the choice.
        #expect(betweenSongsEdit(untouched, choice: .crossfade) == [derived, BetweenSongsWrite(.crossfade)])
        // Once the choice is stored, only the change.
        var chosen = untouched
        chosen.betweenSongs = "Automix"
        chosen.crossfade = true
        chosen.smartTransitions = true
        chosen.matchTempo = true
        #expect(betweenSongsEdit(chosen).isEmpty)
        #expect(betweenSongsEdit(chosen, choice: .gapless) == [BetweenSongsWrite(.gapless)])
        // A Crossfade user who never had the choice stored keeps Crossfade while changing its length.
        #expect(betweenSongsEdit(StoredBetweenSongs(crossfade: true, smartTransitions: false)) == [BetweenSongsWrite(.crossfade)])
    }

    // Restores

    @Test func anOlderBackupGoesThroughTheUntouchedRule() {
        let old = Self.oldDefaults
        #expect(restoredBetweenSongs(settingsVersion: nil, backup: old) == .untouched)
        #expect(restoredBetweenSongs(settingsVersion: nil, backup: StoredBetweenSongs()) == .untouched)
        var crossfade = old
        crossfade.crossfade = true
        crossfade.smartTransitions = false
        #expect(restoredBetweenSongs(settingsVersion: nil, backup: crossfade) == .chosen(BetweenSongsWrite(.crossfade)))
        var gapless = old
        gapless.crossfadeSeconds = 4
        #expect(restoredBetweenSongs(settingsVersion: nil, backup: gapless) == .chosen(BetweenSongsWrite(.gapless)))
        // An Automix backup with Match tempo off restores with it on.
        var automix = old
        automix.crossfade = true
        #expect(restoredBetweenSongs(settingsVersion: nil, backup: automix) == .chosen(BetweenSongsWrite(.automix, matchTempo: true)))
        for keys in Self.everyCombination where keys.betweenSongs == nil {
            let restored = restoredBetweenSongs(settingsVersion: nil, backup: keys)
            if readBetweenSongs(keys).untouched {
                #expect(restored == .untouched, "\(keys)")
            } else {
                #expect(restored == derivedBetweenSongsWrite(keys).map { .chosen($0) }, "\(keys)")
            }
        }
    }

    @Test func aNewerBackupWithoutAChoiceIsUntouched() {
        let mirrors = StoredBetweenSongs(crossfade: true, crossfadeSeconds: 12, smartTransitions: false, matchTempo: false)
        #expect(restoredBetweenSongs(settingsVersion: betweenSongsSettingsVersion, backup: mirrors) == .untouched)
        var later = mirrors
        later.betweenSongs = "Later"
        #expect(restoredBetweenSongs(settingsVersion: betweenSongsSettingsVersion, backup: later) == .untouched)
        for choice in BetweenSongs.allCases {
            var named = mirrors
            named.betweenSongs = choice.storedName
            #expect(restoredBetweenSongs(settingsVersion: betweenSongsSettingsVersion, backup: named) == .chosen(BetweenSongsWrite(choice)))
            // A later version reads the same way.
            #expect(restoredBetweenSongs(settingsVersion: 3, backup: StoredBetweenSongs(betweenSongs: choice.storedName)) == .chosen(BetweenSongsWrite(choice)))
        }
        #expect(betweenSongsSettingsVersion == 2)
    }

    @Test func anUntouchedRestoreTakesFourKeysOffAndReadsUntouched() {
        #expect(BetweenSongsKeys.untouchedRestoreRemoves == ["between_songs", "crossfade", "smart_transitions", "match_tempo"])
        #expect(readBetweenSongs(StoredBetweenSongs(crossfadeSeconds: 8, filterSweeps: true, crossfadeCurve: "EqualPower")).untouched)
    }

    // The edit planner, against a store

    /// Every change a Between songs row can make.
    static let changes: [BetweenSongsChange] = [
        .length(12), .curve(.linear), .filterSweeps(false), .matchTempo(false), .choose(.gapless), .choose(.crossfade), .choose(.automix),
    ]

    @Test func eachRowsFirstWriteOnAnUntouchedDeviceKeepsWhatItShowedAndStoresTheChoice() {
        for device in [[String: Any](), Self.oldDefaultKeys, ["crossfade": false]] {
            for change in Self.changes {
                var store = MemoryStore(device)
                let before = store.shown
                #expect(before.betweenSongs == .automix && !before.betweenSongsStored && before.matchTempo, "\(device)")
                let writes = betweenSongsEditWrites(store.value, change)
                // The choice the device showed comes first, with Match tempo on.
                #expect(Array(writes.prefix(4)) == BetweenSongsWrite(.automix, matchTempo: true).keyWrites, "\(device) \(change)")
                store.apply(writes)
                var expected = before
                expected.betweenSongsStored = true
                switch change {
                case .choose(let choice): expected.betweenSongs = choice
                case .length(let seconds): expected.crossfadeSeconds = seconds
                case .curve(let curve): expected.crossfadeCurve = curve
                case .filterSweeps(let on): expected.filterSweeps = on
                case .matchTempo(let on): expected.matchTempo = on
                }
                #expect(store.shown == expected, "\(device) \(change)")
                // A later edit keeps what this one stored.
                store.apply(betweenSongsEditWrites(store.value, .length(9)))
                expected.crossfadeSeconds = 9
                #expect(store.shown == expected, "\(device) \(change), then the length")
            }
        }
        // The writes of one edit, exactly.
        #expect(betweenSongsEditWrites({ Self.oldDefaultKeys[$0] }, .length(12)) == [
            .set("between_songs", .string("Automix")), .set("crossfade", .bool(true)), .set("smart_transitions", .bool(true)),
            .set("match_tempo", .bool(true)), .set("crossfade_seconds", .int(12)),
        ])
    }

    @Test func onceChosenAnEditWritesOnlyItsChange() {
        let chosen: [String: Any] = ["between_songs": "Crossfade", "crossfade": true, "smart_transitions": false, "match_tempo": false,
                                     "crossfade_seconds": 5]
        func edit(_ change: BetweenSongsChange) -> [StoredKeyWrite] { betweenSongsEditWrites({ chosen[$0] }, change) }
        #expect(edit(.length(30)) == [.set("crossfade_seconds", .int(16))])
        #expect(edit(.length(0)) == [.set("crossfade_seconds", .int(2))])
        #expect(edit(.curve(.sCurve)) == [.set("crossfade_curve", .string("SCurve"))])
        #expect(edit(.filterSweeps(false)) == [.set("filter_sweeps", .bool(false))])
        #expect(edit(.matchTempo(true)) == [.set("match_tempo", .bool(true))])
        #expect(edit(.choose(.gapless)) == BetweenSongsWrite(.gapless).keyWrites)
        // A Crossfade user from before the choice keeps Crossfade while changing its length.
        var older = MemoryStore(["crossfade": true, "smart_transitions": false, "crossfade_seconds": 6])
        older.apply(betweenSongsEditWrites(older.value, .length(10)))
        #expect(older.shown.betweenSongs == .crossfade && older.shown.betweenSongsStored && older.shown.crossfadeSeconds == 10)
    }

    // The first read, against a store

    @Test func theFirstReadStoresWhatTheDeviceShows() {
        // Untouched devices and stored choices: nothing.
        for device in [[String: Any](), Self.oldDefaultKeys, ["crossfade": false], ["between_songs": "Gapless", "crossfade": true]] {
            #expect(betweenSongsFirstReadWrites { device[$0] }.isEmpty, "\(device)")
        }
        #expect(betweenSongsFirstReadWrites { ["crossfade": true, "smart_transitions": false][$0] } == BetweenSongsWrite(.crossfade).keyWrites)
        #expect(betweenSongsFirstReadWrites { ["crossfade": true, "match_tempo": false][$0] }
            == BetweenSongsWrite(.automix, matchTempo: true).keyWrites)
        for keys in Self.everyCombination {
            var device: [String: Any] = [:]
            device["between_songs"] = keys.betweenSongs
            device["crossfade"] = keys.crossfade
            device["crossfade_seconds"] = keys.crossfadeSeconds
            device["smart_transitions"] = keys.smartTransitions
            device["filter_sweeps"] = keys.filterSweeps
            device["match_tempo"] = keys.matchTempo
            device["crossfade_curve"] = keys.crossfadeCurve
            var store = MemoryStore(device)
            let before = store.shown
            let writes = betweenSongsFirstReadWrites(store.value)
            store.apply(writes)
            let reading = readBetweenSongs(keys)
            if reading.stored || reading.untouched {
                #expect(writes.isEmpty, "\(keys)")
            } else {
                // Stored once, and the device shows what it showed.
                var stored = before
                stored.betweenSongsStored = true
                #expect(store.shown == stored, "\(keys)")
                #expect(betweenSongsFirstReadWrites(store.value).isEmpty, "\(keys)")
            }
        }
    }

    // The restore planner, against a store

    /// A phone that chose Crossfade, with Match tempo off.
    static var chosenPhone: [String: Any] {
        ["between_songs": "Crossfade", "crossfade": true, "smart_transitions": false, "match_tempo": false, "crossfade_seconds": 5]
    }

    func backupText(_ player: String) -> String { #"{"kind": "octo-settings", "version": 1, "player": "# + player + "}" }

    /// Restores a backup file's player settings onto `store` as a restore does: the backup's own
    /// keys as the file has them, then the planner's writes; gives the planner's settings.
    func restore(_ text: String, onto store: inout MemoryStore) throws -> PlayerPrefs {
        guard case .read(let backup) = BackupFile.decode(text), let player = backup.player else {
            Issue.record("not read: \(text)")
            return PlayerPrefs()
        }
        let plan = restoredPlayer(player)
        store.apply([
            .set(BetweenSongsKeys.crossfadeSeconds, .int(player.prefs.crossfadeSeconds)),
            .set(BetweenSongsKeys.crossfadeCurve, .string(player.prefs.crossfadeCurve.storedName)),
            .set(BetweenSongsKeys.filterSweeps, .bool(player.prefs.filterSweeps)),
            .set(BetweenSongsKeys.matchTempo, .bool(player.prefs.matchTempo)),
        ])
        store.apply(plan.writes)
        // The settings the restore leaves in memory are the ones the store gives back.
        let after = store
        #expect(plan.player.withStoredBetweenSongs(after.value) == plan.player, "\(text)")
        return plan.player
    }

    /// The backup file a device showing `prefs` writes.
    func backupFrom(_ prefs: PlayerPrefs) -> String {
        var backup = Backup()
        backup.player = PlayerBackup(prefs)
        return BackupFile.encode(backup)
    }

    @Test func anUntouchedBackupRestoresUntouched() throws {
        let legacy = backupText(#"{"crossfade": false, "crossfadeSeconds": 8, "smartTransitions": true, "filterSweeps": true, "matchTempo": false, "crossfadeCurve": "EqualPower"}"#)
        let newer = backupText(#"{"settingsVersion": 2, "crossfade": true, "smartTransitions": true, "crossfadeSeconds": 8, "matchTempo": true}"#)
        for text in [legacy, newer] {
            var phone = MemoryStore(Self.chosenPhone)
            let restored = try restore(text, onto: &phone)
            for key in BetweenSongsKeys.untouchedRestoreRemoves { #expect(!phone.keys.contains(key), "\(key) after \(text)") }
            #expect(phone.shown.betweenSongs == .automix && !phone.shown.betweenSongsStored && phone.shown.matchTempo)
            #expect(restored.betweenSongs == .automix && !restored.betweenSongsStored && restored.matchTempo)
        }
    }

    @Test func aCrossfadeBackupRestoresCrossfade() throws {
        let legacy = backupText(#"{"crossfade": true, "crossfadeSeconds": 6, "smartTransitions": false, "matchTempo": false}"#)
        let newer = backupText(#"{"settingsVersion": 2, "betweenSongs": "Crossfade", "crossfade": true, "smartTransitions": false, "crossfadeSeconds": 6, "matchTempo": false}"#)
        for text in [legacy, newer] {
            var phone = MemoryStore()
            let restored = try restore(text, onto: &phone)
            #expect(phone.shown.betweenSongs == .crossfade && phone.shown.betweenSongsStored && phone.shown.crossfadeSeconds == 6 && !phone.shown.matchTempo)
            #expect(restored.betweenSongs == .crossfade && restored.betweenSongsStored)
            #expect(phone.values["between_songs"] as? String == "Crossfade")
            #expect(phone.values["crossfade"] as? Bool == true)
            #expect(phone.values["smart_transitions"] as? Bool == false)
        }
    }

    @Test func aBackupCarriesTheVersionTheStoredChoiceAndTheMirrors() throws {
        func player(_ text: String) throws -> [String: Any] {
            let root = try #require(try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any])
            return try #require(root["player"] as? [String: Any])
        }
        let untouched = try player(backupFrom(MemoryStore().shown))
        #expect((untouched["settingsVersion"] as? NSNumber)?.intValue == 2)
        #expect(untouched["betweenSongs"] == nil)
        #expect((untouched["crossfade"] as? NSNumber)?.boolValue == true && (untouched["smartTransitions"] as? NSNumber)?.boolValue == true)
        let mirrors: [BetweenSongs: (Bool, Bool)] = [.gapless: (false, false), .crossfade: (true, false), .automix: (true, true)]
        for (choice, mirror) in mirrors {
            let written = try player(backupFrom(MemoryStore(["between_songs": choice.storedName]).shown))
            #expect((written["settingsVersion"] as? NSNumber)?.intValue == 2, "\(choice)")
            #expect(written["betweenSongs"] as? String == choice.storedName)
            #expect((written["crossfade"] as? NSNumber)?.boolValue == mirror.0, "\(choice)")
            #expect((written["smartTransitions"] as? NSNumber)?.boolValue == mirror.1, "\(choice)")
        }
    }

    @Test func aBackupRestoresWhatThePhoneShowed() throws {
        var phones: [[String: Any]] = [[:], Self.oldDefaultKeys]
        for choice in BetweenSongs.allCases {
            for tempo in [true, false] {
                phones.append(["between_songs": choice.storedName, "match_tempo": tempo, "crossfade_seconds": 11, "filter_sweeps": false])
            }
        }
        for keys in phones {
            let before = MemoryStore(keys).shown
            let text = backupFrom(before)
            guard case .read(let read) = BackupFile.decode(text), let player = read.player else {
                Issue.record("not read")
                continue
            }
            #expect(restoredPlayer(player).player == before, "\(keys)")
            var phone = MemoryStore(Self.chosenPhone)
            _ = try restore(text, onto: &phone)
            #expect(phone.shown == before, "\(keys)")
        }
    }

    @Test func aLegacyGaplessBackupStaysGaplessThroughTheNextBackup() throws {
        let legacy = backupText(#"{"crossfade": false, "crossfadeSeconds": 4, "smartTransitions": true, "filterSweeps": true, "matchTempo": false, "crossfadeCurve": "EqualPower"}"#)
        var first = MemoryStore()
        _ = try restore(legacy, onto: &first)
        #expect(first.shown.betweenSongs == .gapless)
        let next = backupFrom(first.shown)
        #expect(next.contains(#""betweenSongs": "Gapless""#), "\(next)")
        var second = MemoryStore()
        _ = try restore(next, onto: &second)
        #expect(second.shown.betweenSongs == .gapless && second.shown.betweenSongsStored)
    }

    @Test func aLegacyAutomixBackupTurnsMatchTempoOn() throws {
        let legacy = backupText(#"{"crossfade": true, "crossfadeSeconds": 8, "smartTransitions": true, "filterSweeps": true, "matchTempo": false}"#)
        var phone = MemoryStore(Self.chosenPhone)
        let restored = try restore(legacy, onto: &phone)
        // In storage, over the backup's own Match tempo off, and in memory.
        #expect(phone.values["match_tempo"] as? Bool == true)
        #expect(phone.values["between_songs"] as? String == "Automix")
        #expect(phone.shown.betweenSongs == .automix && phone.shown.betweenSongsStored && phone.shown.matchTempo)
        #expect(restored.betweenSongs == .automix && restored.betweenSongsStored && restored.matchTempo)
    }

    @Test func aChoiceFromBeforeTheChoiceExistedIsInTheNextBackup() throws {
        // A phone that had crossfade on before the choice existed: its first read stores the choice.
        var phone = MemoryStore(["crossfade": true, "smart_transitions": false])
        phone.apply(betweenSongsFirstReadWrites(phone.value))
        let text = backupFrom(phone.shown)
        #expect(text.contains(#""betweenSongs": "Crossfade""#), "\(text)")
        var restored = MemoryStore()
        _ = try restore(text, onto: &restored)
        #expect(restored.shown.betweenSongs == .crossfade && restored.shown.betweenSongsStored)
        // Without the first read the same phone's backup would restore as untouched.
        let unread = backupFrom(MemoryStore(["crossfade": true, "smart_transitions": false]).shown)
        var lost = MemoryStore()
        _ = try restore(unread, onto: &lost)
        #expect(!lost.shown.betweenSongsStored)
    }

    /// The player sections every app writes, restored as every app restores them: the shared
    /// file Android's and the desktop's tests read too.
    @Test func theSharedBackupsRestoreAsEveryAppRestoresThem() throws {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        var found: URL?
        while dir.path != "/" && !dir.path.isEmpty {
            let candidate = dir.appendingPathComponent("shared/core/src/commonTest/resources/backup/between-songs-player.json")
            if FileManager.default.fileExists(atPath: candidate.path) { found = candidate; break }
            dir = dir.deletingLastPathComponent()
        }
        guard let url = found else {
            Issue.record("shared/core/src/commonTest/resources/backup/between-songs-player.json not found above \(#filePath)")
            return
        }
        let root = try #require(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let cases = try #require(root["cases"] as? [[String: Any]])
        #expect(cases.count >= 10)
        for c in cases {
            let name = try #require(c["name"] as? String)
            let player = String(decoding: try JSONSerialization.data(withJSONObject: try #require(c["player"])), as: UTF8.self)
            var phone = MemoryStore(Self.chosenPhone)
            let restored = try restore(backupText(player), onto: &phone)
            let shown = phone.shown
            #expect(shown.betweenSongs.storedName == c["choice"] as? String, "\(name)")
            #expect(shown.betweenSongsStored == (c["stored"] as? NSNumber)?.boolValue, "\(name)")
            #expect(shown.matchTempo == (c["matchTempo"] as? NSNumber)?.boolValue, "\(name)")
            #expect(shown.crossfadeSeconds == (c["crossfadeSeconds"] as? NSNumber)?.intValue, "\(name)")
            #expect(restored.betweenSongs == shown.betweenSongs && restored.betweenSongsStored == shown.betweenSongsStored, "\(name)")
            if !shown.betweenSongsStored {
                for key in BetweenSongsKeys.untouchedRestoreRemoves { #expect(!phone.keys.contains(key), "\(name): \(key)") }
            }
        }
    }

    // The Playback page

    @Test func eachChoiceShowsItsOwnRows() {
        #expect(rowsUnder(.gapless).isEmpty)
        #expect(rowsUnder(.crossfade).map(\.entry.id) == ["crossfade_length", "crossfade_curve"])
        #expect(rowsUnder(.automix).map(\.entry.id) == ["filter_sweeps", "match_tempo"])
    }

    @Test func aSearchHitOnAHiddenRowLightsBetweenSongs() {
        #expect(playbackHighlight("crossfade_length", .crossfade) == "crossfade_length")
        #expect(playbackHighlight("crossfade_length", .automix) == "crossfade")
        #expect(playbackHighlight("match_tempo", .automix) == "match_tempo")
        #expect(playbackHighlight("filter_sweeps", .gapless) == "crossfade")
        #expect(playbackHighlight("speed", .gapless) == "speed")
        #expect(playbackHighlight(nil, .automix) == nil)
    }

    @Test func whatsNewNamesAutomixAndTheOtherChoices() {
        let notes = WhatsNew.notes
        #expect(notes.contains("- **Automix** blends each song into the next where they meet best, or pick Gapless or Crossfade under Between songs."))
        #expect(!notes.contains("Smart transitions"))
    }

    @Test func eachChoiceSaysWhatItDoes() {
        #expect(BetweenSongs.allCases.map(\.label) == ["Gapless", "Crossfade", "Automix"])
        for choice in BetweenSongs.allCases {
            #expect(!choice.detail.isEmpty && choice.detail.hasSuffix("."), "\(choice)")
            #expect(!choice.detail.contains { ["\u{2014}", "\u{2013}"].contains($0) }, "\(choice)")
        }
    }
}

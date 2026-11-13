import Foundation

/// What plays between two songs that do not belong together (an album played in order stays
/// gapless whatever is chosen): Gapless, Crossfade with its own length and curve, or Automix,
/// which picks each blend and has Filter sweeps and Match tempo. Stored by name: "Gapless",
/// "Crossfade", "Automix".
public enum BetweenSongs: String, StoredChoice, Codable {
    case gapless = "Gapless"
    case crossfade = "Crossfade"
    case automix = "Automix"

    public var label: String { rawValue }

    /// Each choice as its row and its list say it.
    public var detail: String {
        switch self {
        case .gapless: "Each song plays straight into the next."
        case .crossfade: "Each song fades into the next over the length you set."
        case .automix: "Songs blend where they meet best, on the beat when they can."
        }
    }

    /// The choice stored under `name`, or nil when nothing, or something unknown, is stored.
    public static func ofStored(_ name: String?) -> BetweenSongs? { allCases.first { $0.storedName == name } }
}

/// Crossfade's length in seconds, and the length before it is chosen.
public let shortestCrossfadeSeconds = 2
public let longestCrossfadeSeconds = 16
public let defaultCrossfadeSeconds = 8

/// The version of the player settings a backup carries as settingsVersion once it has the
/// Between songs choice. A backup without one is older.
public let betweenSongsSettingsVersion = 2

/// The longest blend the player plans, in milliseconds: none for Gapless, Crossfade's own length,
/// `automixLongestBlendMs` for Automix.
public func crossfadeMsOf(_ choice: BetweenSongs, crossfadeSeconds: Int) -> Int {
    switch choice {
    case .gapless: 0
    case .crossfade: min(max(crossfadeSeconds, shortestCrossfadeSeconds), longestCrossfadeSeconds) * 1_000
    case .automix: automixLongestBlendMs
    }
}

/// How the player plans the move between songs for a choice: the longest blend from
/// `crossfadeMsOf`, Automix's longest intro, planned blends only for Automix, and the rest of the
/// settings as they are.
public func automixSettingsOf(_ choice: BetweenSongs, crossfadeSeconds: Int, crossfadeCurve: FadeCurve, filterSweeps: Bool,
                              matchTempo: Bool) -> AutomixSettings {
    AutomixSettings(maxOverlapMs: crossfadeMsOf(choice, crossfadeSeconds: crossfadeSeconds), longestIntroMs: automixLongestIntroMs,
                    smart: choice == .automix, filterSweeps: filterSweeps, beatMatch: matchTempo, fadeCurve: crossfadeCurve)
}

/// The keys the phones store the Between songs settings under.
public enum BetweenSongsKeys {
    public static let betweenSongs = "between_songs"
    public static let crossfade = "crossfade"
    public static let crossfadeSeconds = "crossfade_seconds"
    public static let smartTransitions = "smart_transitions"
    public static let filterSweeps = "filter_sweeps"
    public static let matchTempo = "match_tempo"
    public static let crossfadeCurve = "crossfade_curve"

    /// What an untouched restore takes off the device: with them gone it reads as untouched
    /// again, Match tempo at its default.
    public static let untouchedRestoreRemoves = [betweenSongs, crossfade, smartTransitions, matchTempo]
}

/// The Between songs settings as they are stored, each nil when its key is not stored.
/// `crossfade` and `smartTransitions` are the settings older apps kept, which newer ones still
/// write as mirrors; `crossfadeCurve` is a curve's stored name.
public struct StoredBetweenSongs: Sendable, Equatable {
    public var betweenSongs: String?
    public var crossfade: Bool?
    public var crossfadeSeconds: Int?
    public var smartTransitions: Bool?
    public var filterSweeps: Bool?
    public var matchTempo: Bool?
    public var crossfadeCurve: String?

    public init(betweenSongs: String? = nil, crossfade: Bool? = nil, crossfadeSeconds: Int? = nil, smartTransitions: Bool? = nil,
                filterSweeps: Bool? = nil, matchTempo: Bool? = nil, crossfadeCurve: String? = nil) {
        self.betweenSongs = betweenSongs
        self.crossfade = crossfade
        self.crossfadeSeconds = crossfadeSeconds
        self.smartTransitions = smartTransitions
        self.filterSweeps = filterSweeps
        self.matchTempo = matchTempo
        self.crossfadeCurve = crossfadeCurve
    }

    /// The settings through a reader that gives a key's stored value, or nil when it is not
    /// stored. A value of the wrong type counts as not stored.
    public static func read(_ value: (String) -> Any?) -> StoredBetweenSongs {
        StoredBetweenSongs(
            betweenSongs: value(BetweenSongsKeys.betweenSongs) as? String,
            crossfade: value(BetweenSongsKeys.crossfade) as? Bool,
            crossfadeSeconds: whole(value(BetweenSongsKeys.crossfadeSeconds)),
            smartTransitions: value(BetweenSongsKeys.smartTransitions) as? Bool,
            filterSweeps: value(BetweenSongsKeys.filterSweeps) as? Bool,
            matchTempo: value(BetweenSongsKeys.matchTempo) as? Bool,
            crossfadeCurve: value(BetweenSongsKeys.crossfadeCurve) as? String)
    }

    /// A stored whole number of any width.
    private static func whole(_ value: Any?) -> Int? {
        if let n = value as? Int { return n }
        if let n = value as? Int64 { return Int(exactly: n) }
        if let n = value as? Int32 { return Int(n) }
        return nil
    }
}

/// What the stored settings come to: the choice, whether Match tempo is on, whether the choice
/// itself is stored, and whether the device counts as untouched (it never chose: Automix with
/// Match tempo on, nothing written).
public struct BetweenSongsReading: Sendable, Equatable {
    public var choice: BetweenSongs
    public var matchTempo: Bool
    public var stored: Bool
    public var untouched: Bool

    public init(_ choice: BetweenSongs, matchTempo: Bool, stored: Bool, untouched: Bool) {
        self.choice = choice
        self.matchTempo = matchTempo
        self.stored = stored
        self.untouched = untouched
    }
}

/// The length older apps had before anyone changed it: crossfade was off at 8 s, with smart
/// transitions and Filter sweeps on, Match tempo off and the equal-power curve.
private let oldCrossfadeSeconds = 8

/// The choice and Match tempo the stored settings give. A stored choice is the choice. Without
/// one, the device is untouched when it has no stored crossfade, or crossfade is off with every
/// other setting still at the old defaults (a missing key counts as its old default). Otherwise
/// crossfade on is Automix, or Crossfade when smart transitions are off, and crossfade off is
/// Gapless; Automix turns Match tempo on.
public func readBetweenSongs(_ keys: StoredBetweenSongs) -> BetweenSongsReading {
    if let choice = BetweenSongs.ofStored(keys.betweenSongs) {
        return BetweenSongsReading(choice, matchTempo: keys.matchTempo ?? true, stored: true, untouched: false)
    }
    if untouched(keys) { return BetweenSongsReading(.automix, matchTempo: true, stored: false, untouched: true) }
    let choice: BetweenSongs = if keys.crossfade != true {
        .gapless
    } else if keys.smartTransitions == false {
        .crossfade
    } else {
        .automix
    }
    return BetweenSongsReading(choice, matchTempo: choice == .automix || (keys.matchTempo ?? true), stored: false, untouched: false)
}

private func untouched(_ keys: StoredBetweenSongs) -> Bool {
    guard let crossfade = keys.crossfade else { return true }
    return !crossfade
        && (keys.crossfadeSeconds ?? oldCrossfadeSeconds) == oldCrossfadeSeconds
        && keys.smartTransitions != false
        && keys.filterSweeps != false
        && keys.matchTempo != true
        && FadeCurve.stored(keys.crossfadeCurve, or: .equalPower) == .equalPower
}

/// One write of the choice: between_songs, the two mirrors an older app reads in its place
/// (crossfade on unless Gapless, smart transitions on only for Automix), and match_tempo when
/// this write turns it on (nil leaves it as it is).
public struct BetweenSongsWrite: Sendable, Equatable {
    public var choice: BetweenSongs
    public var matchTempo: Bool?

    public init(_ choice: BetweenSongs, matchTempo: Bool? = nil) {
        self.choice = choice
        self.matchTempo = matchTempo
    }

    public var crossfade: Bool { choice != .gapless }
    public var smartTransitions: Bool { choice == .automix }

    /// The write as stored keys: the choice, its mirrors, then Match tempo when it turns on.
    public var keyWrites: [StoredKeyWrite] {
        var writes: [StoredKeyWrite] = [
            .set(BetweenSongsKeys.betweenSongs, .string(choice.storedName)),
            .set(BetweenSongsKeys.crossfade, .bool(crossfade)),
            .set(BetweenSongsKeys.smartTransitions, .bool(smartTransitions)),
        ]
        if let matchTempo { writes.append(.set(BetweenSongsKeys.matchTempo, .bool(matchTempo))) }
        return writes
    }
}

/// The write a device whose choice is not stored yet needs, so what it shows becomes what it
/// stores: the choice its settings come to, Match tempo when that turned it on, and the mirrors.
/// Nil when the choice is stored already.
public func derivedBetweenSongsWrite(_ keys: StoredBetweenSongs) -> BetweenSongsWrite? {
    let reading = readBetweenSongs(keys)
    if reading.stored { return nil }
    let turnedOn = reading.choice == .automix && keys.matchTempo != true
    return BetweenSongsWrite(reading.choice, matchTempo: turnedOn ? true : nil)
}

/// The write at a device's first read: a device that is not untouched stores the choice its
/// settings come to, once, so its backups carry it. None when the choice is stored or the device
/// is untouched.
public func firstReadBetweenSongsWrite(_ keys: StoredBetweenSongs) -> BetweenSongsWrite? {
    readBetweenSongs(keys).untouched ? nil : derivedBetweenSongsWrite(keys)
}

/// The writes one change to a Between songs setting (the choice, Crossfade's length or curve,
/// Filter sweeps, Match tempo) begins with, in order, in the same edit: the derived write while
/// the choice is not stored, so the change keeps everything else the device shows, then `choice`
/// when the change is to the choice. A change to another setting writes its own key after these.
public func betweenSongsEdit(_ keys: StoredBetweenSongs, choice: BetweenSongs? = nil) -> [BetweenSongsWrite] {
    [derivedBetweenSongsWrite(keys), choice.map { BetweenSongsWrite($0) }].compactMap { $0 }
}

/// What restoring a backup's player section does to the Between songs keys, after the backup's
/// own keys are written.
public enum RestoredBetweenSongs: Sendable, Equatable {
    /// The backup never chose: take `BetweenSongsKeys.untouchedRestoreRemoves` off the device,
    /// which then reads as Automix with Match tempo on.
    case untouched
    /// The backup chose: apply the write.
    case chosen(BetweenSongsWrite)
}

/// What a backup gives: `settingsVersion` as its player section has it (nil in a backup from
/// before the choice) and `backup` its fields. An older backup goes through the untouched rule on
/// its old fields; a newer one without a choice is untouched.
public func restoredBetweenSongs(settingsVersion: Int?, backup: StoredBetweenSongs) -> RestoredBetweenSongs {
    guard let settingsVersion, settingsVersion >= betweenSongsSettingsVersion else {
        let reading = readBetweenSongs(backup)
        if reading.untouched { return .untouched }
        return .chosen(derivedBetweenSongsWrite(backup) ?? BetweenSongsWrite(reading.choice))
    }
    guard let choice = BetweenSongs.ofStored(backup.betweenSongs) else { return .untouched }
    return .chosen(BetweenSongsWrite(choice))
}

// MARK: Stored keys

/// A value as the player's settings store keeps it.
public enum StoredKeyValue: Sendable, Equatable {
    case bool(Bool)
    case int(Int)
    case string(String)

    /// The value as a store takes it.
    public var plain: Any {
        switch self {
        case .bool(let value): value
        case .int(let value): value
        case .string(let value): value
        }
    }
}

/// One write to the player's settings store: a key set to a value, or a key taken off. A list of
/// them is applied in order, as one edit.
public enum StoredKeyWrite: Sendable, Equatable {
    case set(String, StoredKeyValue)
    case remove(String)
}

/// A change to one Between songs setting. Every write of these settings is one of them, planned
/// by `betweenSongsEditWrites`.
public enum BetweenSongsChange: Sendable, Equatable {
    case choose(BetweenSongs)
    /// Crossfade's length in seconds, held to its range.
    case length(Int)
    case curve(FadeCurve)
    case filterSweeps(Bool)
    case matchTempo(Bool)
}

/// The key writes one change makes, in order, read against the stored keys through `value`
/// (nil for a key not stored): while the choice is not stored, first the choice the device shows
/// (with its mirrors, and Match tempo when that turned it on), so the change keeps everything
/// else as it was shown; then the change itself.
public func betweenSongsEditWrites(_ value: (String) -> Any?, _ change: BetweenSongsChange) -> [StoredKeyWrite] {
    var chosen: BetweenSongs?
    if case .choose(let choice) = change { chosen = choice }
    var writes = betweenSongsEdit(StoredBetweenSongs.read(value), choice: chosen).flatMap(\.keyWrites)
    switch change {
    case .choose:
        break
    case .length(let seconds):
        writes.append(.set(BetweenSongsKeys.crossfadeSeconds, .int(min(max(seconds, shortestCrossfadeSeconds), longestCrossfadeSeconds))))
    case .curve(let curve):
        writes.append(.set(BetweenSongsKeys.crossfadeCurve, .string(curve.storedName)))
    case .filterSweeps(let on):
        writes.append(.set(BetweenSongsKeys.filterSweeps, .bool(on)))
    case .matchTempo(let on):
        writes.append(.set(BetweenSongsKeys.matchTempo, .bool(on)))
    }
    return writes
}

/// The key writes at the first read of the player's settings, read through `value`: a device that
/// chose before the Between songs choice existed stores the choice its settings come to, once;
/// an untouched device and one whose choice is stored get none.
public func betweenSongsFirstReadWrites(_ value: (String) -> Any?) -> [StoredKeyWrite] {
    firstReadBetweenSongsWrite(StoredBetweenSongs.read(value))?.keyWrites ?? []
}

extension PlayerPrefs {
    /// These settings with the Between songs part as the stored keys give it, read through
    /// `value`: the choice, whether it is stored and Match tempo by the untouched rule, Crossfade's
    /// length held to its range, its curve and Filter sweeps, each at its default when not stored.
    public func withStoredBetweenSongs(_ value: (String) -> Any?) -> PlayerPrefs {
        let keys = StoredBetweenSongs.read(value)
        let reading = readBetweenSongs(keys)
        let defaults = PlayerPrefs()
        var read = self
        read.betweenSongs = reading.choice
        read.betweenSongsStored = reading.stored
        read.matchTempo = reading.matchTempo
        read.crossfadeSeconds = min(max(keys.crossfadeSeconds ?? defaults.crossfadeSeconds, shortestCrossfadeSeconds), longestCrossfadeSeconds)
        read.crossfadeCurve = FadeCurve.stored(keys.crossfadeCurve, or: defaults.crossfadeCurve)
        read.filterSweeps = keys.filterSweeps ?? defaults.filterSweeps
        return read
    }
}

/// What restoring a backup's player settings comes to: the settings the device shows afterwards,
/// and the Between songs key writes to apply after the backup's own keys are written, in the same
/// edit. An untouched backup takes `BetweenSongsKeys.untouchedRestoreRemoves` off; any other
/// writes the choice, its mirrors, and Match tempo when the choice turned it on.
public struct PlayerRestore: Sendable, Equatable {
    public var player: PlayerPrefs
    public var writes: [StoredKeyWrite]
}

/// The restore of a backup's player section, from the fields the file holds.
public func restoredPlayer(_ backup: PlayerBackup) -> PlayerRestore {
    var player = backup.prefs
    switch restoredBetweenSongs(settingsVersion: backup.settingsVersion, backup: backup.saved) {
    case .untouched:
        player.betweenSongs = .automix
        player.betweenSongsStored = false
        player.matchTempo = true
        return PlayerRestore(player: player.sane(), writes: BetweenSongsKeys.untouchedRestoreRemoves.map { .remove($0) })
    case .chosen(let write):
        player.betweenSongs = write.choice
        player.betweenSongsStored = true
        player.matchTempo = write.matchTempo ?? player.matchTempo
        return PlayerRestore(player: player.sane(), writes: write.keyWrites)
    }
}

// MARK: The Playback page

/// The rows under Between songs, each shown only for the choice it belongs to: Crossfade's length
/// and curve, Automix's Filter sweeps and Match tempo.
public enum UnderBetweenSongs: CaseIterable, Sendable {
    case length, curve, filterSweeps, matchTempo

    public var entry: SettingEntry {
        switch self {
        case .length: SettingsIndex.crossfadeLength
        case .curve: SettingsIndex.crossfadeCurve
        case .filterSweeps: SettingsIndex.filterSweeps
        case .matchTempo: SettingsIndex.matchTempo
        }
    }

    public var choice: BetweenSongs {
        switch self {
        case .length, .curve: .crossfade
        case .filterSweeps, .matchTempo: .automix
        }
    }
}

/// The rows a choice shows under Between songs, in order. Gapless has none.
public func rowsUnder(_ choice: BetweenSongs) -> [UnderBetweenSongs] { UnderBetweenSongs.allCases.filter { $0.choice == choice } }

/// The row a search result lights on the Playback page: a row under Between songs that the
/// current choice hides lights Between songs itself.
public func playbackHighlight(_ id: String?, _ choice: BetweenSongs) -> String? {
    guard let under = UnderBetweenSongs.allCases.first(where: { $0.entry.id == id }) else { return id }
    return under.choice == choice ? id : SettingsIndex.betweenSongs.id
}

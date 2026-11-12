package app.winters.octo.playback

// What plays between two songs that do not belong together (an album played
// in order stays gapless whatever is chosen): Gapless, Crossfade with its
// own length and curve, or Automix, which picks each blend and has Filter
// sweeps and Match tempo. Stored by name: "Gapless", "Crossfade", "Automix".
enum class BetweenSongs {
    Gapless,
    Crossfade,
    Automix,
    ;

    // The name it is stored under.
    val stored: String get() = name

    companion object {
        // The choice stored under `name`, or null when nothing, or something
        // unknown, is stored.
        fun ofStored(name: String?): BetweenSongs? = entries.firstOrNull { it.name == name }
    }
}

// Crossfade's length in seconds, and the length before it is chosen.
const val SHORTEST_CROSSFADE_SECONDS = 2
const val LONGEST_CROSSFADE_SECONDS = 16
const val DEFAULT_CROSSFADE_SECONDS = 8

// The version of the player settings a backup carries as settingsVersion
// once it has the Between songs choice. A backup without one is older.
const val BETWEEN_SONGS_SETTINGS_VERSION = 2

// The longest blend the player plans, in milliseconds: none for Gapless,
// Crossfade's own length, AUTOMIX_LONGEST_BLEND_MS for Automix.
fun crossfadeMsOf(choice: BetweenSongs, crossfadeSeconds: Int): Long = when (choice) {
    BetweenSongs.Gapless -> 0L
    BetweenSongs.Crossfade -> crossfadeSeconds.coerceIn(SHORTEST_CROSSFADE_SECONDS, LONGEST_CROSSFADE_SECONDS) * 1_000L
    BetweenSongs.Automix -> AUTOMIX_LONGEST_BLEND_MS
}

// How the player plans the move between songs for a choice: the longest
// blend from crossfadeMsOf, Automix's longest intro, planned blends only
// for Automix, and the rest of the settings as they are.
fun automixSettingsOf(
    choice: BetweenSongs,
    crossfadeSeconds: Int,
    crossfadeCurve: FadeCurve,
    filterSweeps: Boolean,
    matchTempo: Boolean,
): AutomixSettings = AutomixSettings(
    maxOverlapMs = crossfadeMsOf(choice, crossfadeSeconds),
    longestIntroMs = AUTOMIX_LONGEST_INTRO_MS,
    smart = choice == BetweenSongs.Automix,
    filterSweeps = filterSweeps,
    beatMatch = matchTempo,
    fadeCurve = crossfadeCurve,
)

// The keys the phones store the Between songs settings under.
object BetweenSongsKeys {
    const val BETWEEN_SONGS = "between_songs"
    const val CROSSFADE = "crossfade"
    const val CROSSFADE_SECONDS = "crossfade_seconds"
    const val SMART_TRANSITIONS = "smart_transitions"
    const val FILTER_SWEEPS = "filter_sweeps"
    const val MATCH_TEMPO = "match_tempo"
    const val CROSSFADE_CURVE = "crossfade_curve"

    // What an untouched restore takes off the device: with them gone it
    // reads as untouched again, Match tempo at its default.
    val UNTOUCHED_RESTORE_REMOVES: List<String> = listOf(BETWEEN_SONGS, CROSSFADE, SMART_TRANSITIONS, MATCH_TEMPO)
}

// The Between songs settings as they are stored, each null when its key is
// not stored. `crossfade` and `smartTransitions` are the settings older
// apps kept, which newer ones still write as mirrors; `crossfadeCurve` is a
// curve's stored name.
data class StoredBetweenSongs(
    val betweenSongs: String? = null,
    val crossfade: Boolean? = null,
    val crossfadeSeconds: Int? = null,
    val smartTransitions: Boolean? = null,
    val filterSweeps: Boolean? = null,
    val matchTempo: Boolean? = null,
    val crossfadeCurve: String? = null,
) {
    companion object {
        // The settings through a reader that gives a key's stored value, or
        // null when it is not stored. A value of the wrong type counts as
        // not stored.
        fun read(value: (String) -> Any?): StoredBetweenSongs = StoredBetweenSongs(
            betweenSongs = value(BetweenSongsKeys.BETWEEN_SONGS) as? String,
            crossfade = value(BetweenSongsKeys.CROSSFADE) as? Boolean,
            crossfadeSeconds = (value(BetweenSongsKeys.CROSSFADE_SECONDS) as? Number)?.toInt(),
            smartTransitions = value(BetweenSongsKeys.SMART_TRANSITIONS) as? Boolean,
            filterSweeps = value(BetweenSongsKeys.FILTER_SWEEPS) as? Boolean,
            matchTempo = value(BetweenSongsKeys.MATCH_TEMPO) as? Boolean,
            crossfadeCurve = value(BetweenSongsKeys.CROSSFADE_CURVE) as? String,
        )
    }
}

// What the stored settings come to: the choice, whether Match tempo is on,
// whether the choice itself is stored, and whether the device counts as
// untouched (it never chose: Automix with Match tempo on, nothing written).
data class BetweenSongsReading(
    val choice: BetweenSongs,
    val matchTempo: Boolean,
    val stored: Boolean,
    val untouched: Boolean,
)

// The defaults older apps had: crossfade off at 8 s, smart transitions and
// Filter sweeps on, Match tempo off, the equal-power curve.
private const val OLD_CROSSFADE_SECONDS = 8

// The choice and Match tempo the stored settings give. A stored choice is
// the choice. Without one, the device is untouched when it has no stored
// crossfade, or crossfade is off with every other setting still at the old
// defaults (a missing key counts as its old default). Otherwise crossfade
// on is Automix, or Crossfade when smart transitions are off, and crossfade
// off is Gapless; Automix turns Match tempo on.
fun readBetweenSongs(keys: StoredBetweenSongs): BetweenSongsReading {
    BetweenSongs.ofStored(keys.betweenSongs)?.let { choice ->
        return BetweenSongsReading(choice, keys.matchTempo ?: true, stored = true, untouched = false)
    }
    if (untouched(keys)) return BetweenSongsReading(BetweenSongs.Automix, matchTempo = true, stored = false, untouched = true)
    val choice = when {
        keys.crossfade != true -> BetweenSongs.Gapless
        keys.smartTransitions == false -> BetweenSongs.Crossfade
        else -> BetweenSongs.Automix
    }
    val matchTempo = choice == BetweenSongs.Automix || (keys.matchTempo ?: true)
    return BetweenSongsReading(choice, matchTempo, stored = false, untouched = false)
}

private fun untouched(keys: StoredBetweenSongs): Boolean {
    val crossfade = keys.crossfade ?: return true
    return !crossfade &&
        (keys.crossfadeSeconds ?: OLD_CROSSFADE_SECONDS) == OLD_CROSSFADE_SECONDS &&
        keys.smartTransitions != false &&
        keys.filterSweeps != false &&
        keys.matchTempo != true &&
        storedCurve(keys.crossfadeCurve) == FadeCurve.EqualPower
}

// A stored curve as the apps read it: an unknown name is the default.
private fun storedCurve(name: String?): FadeCurve = FadeCurve.entries.firstOrNull { it.name == name } ?: FadeCurve.EqualPower

// One write of the choice: between_songs, the two mirrors an older app
// reads in its place (crossfade on unless Gapless, smart transitions on
// only for Automix), and match_tempo when this write turns it on (null
// leaves it as it is).
data class BetweenSongsWrite(val choice: BetweenSongs, val matchTempo: Boolean? = null) {
    val crossfade: Boolean get() = choice != BetweenSongs.Gapless
    val smartTransitions: Boolean get() = choice == BetweenSongs.Automix
}

// The write a device whose choice is not stored yet needs, so what it
// shows becomes what it stores: the choice its settings come to, Match
// tempo when that turned it on, and the mirrors. Null when the choice is
// stored already.
fun derivedBetweenSongsWrite(keys: StoredBetweenSongs): BetweenSongsWrite? {
    val reading = readBetweenSongs(keys)
    if (reading.stored) return null
    val turnedOn = reading.choice == BetweenSongs.Automix && keys.matchTempo != true
    return BetweenSongsWrite(reading.choice, matchTempo = if (turnedOn) true else null)
}

// The writes at a device's first read: a device that is not untouched
// stores the choice its settings come to, once, so its backups carry it.
// None when the choice is stored or the device is untouched.
fun firstReadBetweenSongsWrite(keys: StoredBetweenSongs): BetweenSongsWrite? =
    if (readBetweenSongs(keys).untouched) null else derivedBetweenSongsWrite(keys)

// The writes one change to a Between songs setting (the choice, Crossfade's
// length or curve, Filter sweeps, Match tempo) begins with, in order, in
// the same edit: the derived write while the choice is not stored, so the
// change keeps everything else the device shows, then `choice` when the
// change is to the choice. A change to another setting writes its own key
// after these.
fun betweenSongsEdit(keys: StoredBetweenSongs, choice: BetweenSongs? = null): List<BetweenSongsWrite> =
    listOfNotNull(derivedBetweenSongsWrite(keys), choice?.let { BetweenSongsWrite(it) })

// What restoring a backup's player section does to the Between songs keys,
// after the backup's own keys are written.
sealed interface RestoredBetweenSongs {
    // The backup never chose: take BetweenSongsKeys.UNTOUCHED_RESTORE_REMOVES
    // off the device, which then reads as Automix with Match tempo on.
    data object Untouched : RestoredBetweenSongs

    // The backup chose: apply the write.
    data class Chosen(val write: BetweenSongsWrite) : RestoredBetweenSongs
}

// What a backup gives: `settingsVersion` as its player section has it (null
// in a backup from before the choice) and `backup` its fields. An older
// backup goes through the untouched rule on its old fields; a newer one
// without a choice is untouched.
fun restoredBetweenSongs(settingsVersion: Int?, backup: StoredBetweenSongs): RestoredBetweenSongs {
    if (settingsVersion == null || settingsVersion < BETWEEN_SONGS_SETTINGS_VERSION) {
        val reading = readBetweenSongs(backup)
        if (reading.untouched) return RestoredBetweenSongs.Untouched
        return RestoredBetweenSongs.Chosen(derivedBetweenSongsWrite(backup) ?: BetweenSongsWrite(reading.choice))
    }
    val choice = BetweenSongs.ofStored(backup.betweenSongs) ?: return RestoredBetweenSongs.Untouched
    return RestoredBetweenSongs.Chosen(BetweenSongsWrite(choice))
}

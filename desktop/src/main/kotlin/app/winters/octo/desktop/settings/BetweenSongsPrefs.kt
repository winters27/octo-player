package app.winters.octo.desktop.settings

import app.winters.octo.playback.BETWEEN_SONGS_SETTINGS_VERSION
import app.winters.octo.playback.BetweenSongs
import app.winters.octo.playback.DEFAULT_CROSSFADE_SECONDS
import app.winters.octo.playback.FadeCurve
import app.winters.octo.playback.LONGEST_CROSSFADE_SECONDS
import app.winters.octo.playback.SHORTEST_CROSSFADE_SECONDS
import app.winters.octo.playback.StoredBetweenSongs
import app.winters.octo.playback.readBetweenSongs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

// One change to a Between songs setting, made through
// SettingsStore.editBetweenSongs.
sealed interface BetweenSongsChange {
    data class Choose(val choice: BetweenSongs) : BetweenSongsChange

    // Crossfade's length in seconds, kept to 2 to 16.
    data class Length(val seconds: Int) : BetweenSongsChange

    data class Curve(val curve: FadeCurve) : BetweenSongsChange

    data class FilterSweeps(val on: Boolean) : BetweenSongsChange

    data class MatchTempo(val on: Boolean) : BetweenSongsChange
}

// The playback settings with one Between songs change made.
internal fun PlaybackPrefs.edited(change: BetweenSongsChange): PlaybackPrefs = when (change) {
    is BetweenSongsChange.Choose -> copy(betweenSongs = change.choice)
    is BetweenSongsChange.Length -> copy(crossfadeLength = change.seconds.coerceIn(SHORTEST_CROSSFADE_SECONDS, LONGEST_CROSSFADE_SECONDS))
    is BetweenSongsChange.Curve -> copy(crossfadeCurve = change.curve)
    is BetweenSongsChange.FilterSweeps -> copy(filterSweeps = change.on)
    is BetweenSongsChange.MatchTempo -> copy(matchTempo = change.on)
}

// The playback settings as they are written: the two fields an older Octo
// reads in place of the choice set from it, the longest blend (0 for
// Gapless, else Crossfade's length) and smart transitions (on only for
// Automix). Unchanged when they already agree.
internal fun PlaybackPrefs.mirrored(): PlaybackPrefs {
    val seconds = if (betweenSongs == BetweenSongs.Gapless) 0 else crossfadeLength.coerceIn(SHORTEST_CROSSFADE_SECONDS, LONGEST_CROSSFADE_SECONDS)
    val smart = betweenSongs == BetweenSongs.Automix
    return if (legacyCrossfadeSeconds == seconds && legacySmartTransitions == smart) this else copy(legacyCrossfadeSeconds = seconds, legacySmartTransitions = smart)
}

// The settings file's version once it has the Between songs choice, and
// the key it is written under. A file without it was written by an older
// Octo, or by one opened over this one since.
internal const val SETTINGS_VERSION = BETWEEN_SONGS_SETTINGS_VERSION
internal const val SETTINGS_VERSION_KEY = "settingsVersion"

// What reading an older file's crossfade settings came to: the file with
// the choice, Crossfade's length and Match tempo put into its playback
// settings, and whether it counts as untouched (it never chose, so nothing
// of it needs writing).
internal class MigratedFile(val json: JsonObject, val untouched: Boolean)

// The Between songs settings as Octo last wrote them to the settings file,
// kept beside it: the choice, Crossfade's length and Match tempo, and the
// two fields written in their place for an older Octo. An older Octo opened
// over this one writes the settings file without the choice; while those
// two fields still hold what was written, the choice was never changed
// there, so it is read back from here.
@Serializable
internal data class BetweenSongsFile(
    val betweenSongs: BetweenSongs,
    val crossfadeLength: Int,
    val matchTempo: Boolean,
    val crossfadeSeconds: Int,
    val smartTransitions: Boolean,
) {
    companion object {
        fun of(playback: PlaybackPrefs) = BetweenSongsFile(
            playback.betweenSongs,
            playback.crossfadeLength,
            playback.matchTempo,
            playback.legacyCrossfadeSeconds,
            playback.legacySmartTransitions,
        )
    }
}

// An older file's playback settings brought to the Between songs choice,
// or null for a file that has its version. `written` gives the Between
// songs file, asked for only here.
//
// A file this Octo wrote and an older Octo wrote over since: while its
// crossfade field and smart transitions are still as written, the choice
// and Crossfade's length are the ones written, and Match tempo is the
// file's own (the written one when the older Octo dropped it).
//
// Otherwise the file's one crossfade field is read as the switch and the
// length older Octos kept in it: 0 is off, 1 is on at 2 s and over 16 on at
// 16 s, as they played them. Settings still at the old defaults (crossfade
// off, smart transitions and Filter sweeps on, Match tempo off, the
// equal-power curve) count as untouched and give Automix with Match tempo
// on; otherwise crossfade on is Automix, or Crossfade with smart
// transitions off, and off is Gapless; Automix turns Match tempo on.
// Crossfade's length is the old length, or 8 s when off. When this Octo had
// written the file before, a length the older Octo did not keep (crossfade
// off) is the written one, and a chosen file's Match tempo is its own,
// never turned on, as this Octo's own setter never turns it on.
internal fun migratedBetweenSongs(file: JsonObject, written: () -> BetweenSongsFile? = { null }): MigratedFile? {
    val version = file[SETTINGS_VERSION_KEY]
    if (version != null && version !is JsonNull) return null
    val playback = file[PLAYBACK_KEY] as? JsonObject ?: JsonObject(emptyMap())
    val seconds = playback.number("crossfadeSeconds")
    val smart = playback.flag("smartTransitions")
    val tempo = playback.flag("matchTempo")
    val last = written()
    if (last != null && seconds == last.crossfadeSeconds && smart == last.smartTransitions) {
        val length = last.crossfadeLength.coerceIn(SHORTEST_CROSSFADE_SECONDS, LONGEST_CROSSFADE_SECONDS)
        return MigratedFile(file.chosen(playback, last.betweenSongs, length, tempo ?: last.matchTempo), untouched = false)
    }
    val length = seconds?.takeIf { it > 0 }?.coerceIn(SHORTEST_CROSSFADE_SECONDS, LONGEST_CROSSFADE_SECONDS)
    val reading = readBetweenSongs(
        StoredBetweenSongs(
            crossfade = seconds?.let { it > 0 },
            crossfadeSeconds = length,
            smartTransitions = smart,
            filterSweeps = playback.flag("filterSweeps"),
            matchTempo = tempo,
            crossfadeCurve = playback.word("crossfadeCurve"),
        ),
    )
    val kept = length ?: last?.crossfadeLength?.coerceIn(SHORTEST_CROSSFADE_SECONDS, LONGEST_CROSSFADE_SECONDS) ?: DEFAULT_CROSSFADE_SECONDS
    val matchTempo = if (last != null && !reading.untouched && tempo != null) tempo else reading.matchTempo
    return MigratedFile(file.chosen(playback, reading.choice, kept, matchTempo), reading.untouched)
}

// The file with the choice, Crossfade's length and Match tempo put into its
// playback settings.
private fun JsonObject.chosen(playback: JsonObject, choice: BetweenSongs, length: Int, matchTempo: Boolean): JsonObject {
    val settings = JsonObject(
        playback + mapOf(
            "betweenSongs" to JsonPrimitive(choice.stored),
            "crossfadeLength" to JsonPrimitive(length),
            "matchTempo" to JsonPrimitive(matchTempo),
        ),
    )
    return JsonObject(this + (PLAYBACK_KEY to settings))
}

private const val PLAYBACK_KEY = "playback"

// A field of the raw file, each null when missing or of another type.
private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }

private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun JsonObject.word(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

package app.winters.octo.lyrics

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

// How synced lyrics are shown.
@Serializable
enum class LyricsStyle(val label: String, val about: String) {
    Flowing("Flowing", "Lines ripple into place, words fill with light and long notes bloom."),
    Classic("Classic", "The line being sung grows and its words brighten in place."),
}

// One of the look's sliders: its percent range and where it starts.
enum class LookDial(val key: String, val range: IntRange, val default: Int) {
    Emphasis("lyrics_fx_emphasis", 0..200, 100),
    Glow("lyrics_fx_glow", 0..200, 100),
    Lift("lyrics_fx_lift", 0..200, 100),
    Speed("lyrics_fx_speed", 50..200, 100),
    InactiveScale("lyrics_fx_inactive_scale", 60..100, 80),
    Fade("lyrics_fx_fade", 0..200, 100),
    Cascade("lyrics_fx_cascade", 0..200, 100),
}

// How the flowing lyrics look, in percent where it is a slider.
@Serializable
data class LyricsLook(
    val style: LyricsStyle = LyricsStyle.Flowing,
    // How much long notes swell, spread and rise.
    val emphasis: Int = LookDial.Emphasis.default,
    // How strongly long notes glow.
    val glow: Int = LookDial.Glow.default,
    // How far sung words rise and bob.
    val lift: Int = LookDial.Lift.default,
    // How quickly lines move into place.
    val speed: Int = LookDial.Speed.default,
    // The size of the lines not being sung.
    val inactiveScale: Int = LookDial.InactiveScale.default,
    // How soft the edge of the word fill is.
    val fade: Int = LookDial.Fade.default,
    // How far apart the lines start moving when the focus moves.
    val cascade: Int = LookDial.Cascade.default,
    // Lines already sung stay faintly on screen.
    // Sung lines stay above the current one, dimmed, so the lyrics fill the
    // screen instead of starting halfway down.
    val keepCompleted: Boolean = true,
    // The lines bend around a drum.
    val arc: Boolean = false,
) {
    fun percent(dial: LookDial): Int = when (dial) {
        LookDial.Emphasis -> emphasis
        LookDial.Glow -> glow
        LookDial.Lift -> lift
        LookDial.Speed -> speed
        LookDial.InactiveScale -> inactiveScale
        LookDial.Fade -> fade
        LookDial.Cascade -> cascade
    }

    fun with(dial: LookDial, percent: Int): LyricsLook {
        val value = percent.coerceIn(dial.range)
        return when (dial) {
            LookDial.Emphasis -> copy(emphasis = value)
            LookDial.Glow -> copy(glow = value)
            LookDial.Lift -> copy(lift = value)
            LookDial.Speed -> copy(speed = value)
            LookDial.InactiveScale -> copy(inactiveScale = value)
            LookDial.Fade -> copy(fade = value)
            LookDial.Cascade -> copy(cascade = value)
        }
    }

    // As fractions, for the drawing.
    fun fraction(dial: LookDial): Double = percent(dial).coerceIn(dial.range) / 100.0
}

private val STYLE = stringPreferencesKey("lyrics_style")
private val KEEP_COMPLETED = booleanPreferencesKey("lyrics_keep_completed")
private val ARC = booleanPreferencesKey("lyrics_arc")

private fun LookDial.preference(): Preferences.Key<Int> = intPreferencesKey(key)

// The look of the lyrics, saved beside their timing.
@Singleton
class LyricsLookSettings @Inject constructor(@ApplicationContext private val context: Context) {
    val look: Flow<LyricsLook> = context.lyricsData.data.map { stored ->
        LookDial.entries.fold(
            LyricsLook(
                style = LyricsStyle.entries.firstOrNull { it.name == stored[STYLE] } ?: LyricsStyle.Flowing,
                keepCompleted = stored[KEEP_COMPLETED] ?: true,
                arc = stored[ARC] ?: false,
            ),
        ) { look, dial -> look.with(dial, stored[dial.preference()] ?: dial.default) }
    }.distinctUntilChanged()

    suspend fun setStyle(style: LyricsStyle) {
        context.lyricsData.edit { it[STYLE] = style.name }
    }

    suspend fun setDial(dial: LookDial, percent: Int) {
        context.lyricsData.edit { it[dial.preference()] = percent.coerceIn(dial.range) }
    }

    suspend fun setKeepCompleted(on: Boolean) {
        context.lyricsData.edit { it[KEEP_COMPLETED] = on }
    }

    suspend fun setArc(on: Boolean) {
        context.lyricsData.edit { it[ARC] = on }
    }

    suspend fun current(): LyricsLook = look.first()

    // Puts the whole look back, from a backup.
    suspend fun restore(look: LyricsLook) {
        context.lyricsData.edit { prefs -> write(prefs, look) }
    }

    private fun write(prefs: MutablePreferences, look: LyricsLook) {
        prefs[STYLE] = look.style.name
        prefs[KEEP_COMPLETED] = look.keepCompleted
        prefs[ARC] = look.arc
        LookDial.entries.forEach { prefs[it.preference()] = look.percent(it).coerceIn(it.range) }
    }
}

package app.winters.octo.theme

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

// The accent inputs the last run drew with, kept so the first frame of the
// next run draws the right accent before the player's settings load: the
// accent chosen, the cover's color it grew from (null with none), and
// whether the glass took it. Never a source; the player's settings win as
// soon as they load.
data class AccentSeen(
    val choice: AccentChoice = AccentChoice.Octo,
    val art: Int? = null,
    val glass: Boolean = false,
)

// The theme, its two Match slots and Match itself, kept in a file of their
// own that is read synchronously, so the very first frame is drawn in the
// right theme. The four values are written in one edit.
@Singleton
class ThemeStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(readSettings())

    // Held in a field: the preferences keep only a weak reference to it.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state.value = readSettings() }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    val settings: StateFlow<ThemeSettings> = state.asStateFlow()

    // The four values at once, so nothing ever sees them disagree.
    fun write(next: ThemeSettings) {
        prefs.edit()
            .putString(THEME, next.theme.name)
            .putString(THEME_LIGHT, next.light.name)
            .putString(THEME_DARK, next.dark.name)
            .putBoolean(THEME_MATCH, next.match)
            .apply()
        state.value = next
    }

    fun pick(choice: ThemeChoice) = write(pickTheme(settings.value, choice))

    fun setMatch(on: Boolean, deviceDark: Boolean) = write(setMatch(settings.value, on, deviceDark))

    val seen: AccentSeen
        get() = AccentSeen(
            choice = AccentChoice.entries.firstOrNull { it.name == prefs.getString(ACCENT_SEEN, null) } ?: AccentChoice.Octo,
            art = if (prefs.contains(ART_SEEN)) prefs.getInt(ART_SEEN, 0) else null,
            glass = prefs.getBoolean(GLASS_SEEN, false),
        )

    fun see(seen: AccentSeen) {
        prefs.edit().apply {
            putString(ACCENT_SEEN, seen.choice.name)
            if (seen.art != null) putInt(ART_SEEN, seen.art) else remove(ART_SEEN)
            putBoolean(GLASS_SEEN, seen.glass)
        }.apply()
    }

    private fun readSettings() = themeSettings(
        prefs.getString(THEME, null),
        prefs.getString(THEME_LIGHT, null),
        prefs.getString(THEME_DARK, null),
        prefs.getBoolean(THEME_MATCH, false),
    )

    private companion object {
        const val FILE = "octo_theme"
        const val THEME = "theme"
        const val THEME_LIGHT = "theme_light"
        const val THEME_DARK = "theme_dark"
        const val THEME_MATCH = "theme_match"
        const val ACCENT_SEEN = "accent_seen"
        const val ART_SEEN = "art_seen"
        const val GLASS_SEEN = "glass_seen"
    }
}

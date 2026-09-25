package app.winters.octo.player

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// How the full player looks. The defaults are what a new install gets.
data class PlayerPrefs(
    // The moving colour background, on phones that can draw it.
    val liveBackground: Boolean = true,
    // Songs blending into each other, and over how many seconds.
    val crossfade: Boolean = false,
    val crossfadeSeconds: Int = 6,
) {
    // What the player uses: the blend length, or 0 for none.
    val crossfadeMs: Long get() = if (crossfade) crossfadeSeconds * 1_000L else 0
}

// The range the crossfade length can be set to.
val CrossfadeSecondsRange = 1..12

private val Context.playerPrefs by preferencesDataStore("player")
private val LIVE_BACKGROUND = booleanPreferencesKey("live_background")
private val CROSSFADE = booleanPreferencesKey("crossfade")
private val CROSSFADE_SECONDS = intPreferencesKey("crossfade_seconds")

@Singleton
class PlayerSettings @Inject constructor(@ApplicationContext private val context: Context) {
    val prefs: Flow<PlayerPrefs> = context.playerPrefs.data.map { stored ->
        val defaults = PlayerPrefs()
        PlayerPrefs(
            liveBackground = stored[LIVE_BACKGROUND] ?: defaults.liveBackground,
            crossfade = stored[CROSSFADE] ?: defaults.crossfade,
            crossfadeSeconds = (stored[CROSSFADE_SECONDS] ?: defaults.crossfadeSeconds).coerceIn(CrossfadeSecondsRange),
        )
    }

    suspend fun setLiveBackground(on: Boolean) {
        context.playerPrefs.edit { it[LIVE_BACKGROUND] = on }
    }

    suspend fun setCrossfade(on: Boolean) {
        context.playerPrefs.edit { it[CROSSFADE] = on }
    }

    suspend fun setCrossfadeSeconds(seconds: Int) {
        context.playerPrefs.edit { it[CROSSFADE_SECONDS] = seconds.coerceIn(CrossfadeSecondsRange) }
    }
}

package app.winters.octo.player

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
)

private val Context.playerPrefs by preferencesDataStore("player")
private val LIVE_BACKGROUND = booleanPreferencesKey("live_background")

@Singleton
class PlayerSettings @Inject constructor(@ApplicationContext private val context: Context) {
    val prefs: Flow<PlayerPrefs> = context.playerPrefs.data.map { stored ->
        val defaults = PlayerPrefs()
        PlayerPrefs(
            liveBackground = stored[LIVE_BACKGROUND] ?: defaults.liveBackground,
        )
    }

    suspend fun setLiveBackground(on: Boolean) {
        context.playerPrefs.edit { it[LIVE_BACKGROUND] = on }
    }
}

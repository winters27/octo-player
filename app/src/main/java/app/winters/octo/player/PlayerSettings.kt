package app.winters.octo.player

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.playback.CopyPreference
import app.winters.octo.playback.StreamQuality
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
    // Looking lyrics up online when the server and the song's files have none.
    val lyricsOnline: Boolean = true,
) {
    // What the player uses: the blend length, or 0 for none.
    val crossfadeMs: Long get() = if (crossfade) crossfadeSeconds * 1_000L else 0
}

// Where songs play from when a server is connected. On mobile data a 192
// kbps MP3 by default: it sounds close to the original on phone headphones
// and uses about 86 MB an hour, where a lossless file can use five times
// that.
data class StreamPrefs(
    val copies: CopyPreference = CopyPreference.PhoneFirst,
    val wifi: StreamQuality = StreamQuality.Original,
    val mobile: StreamQuality = StreamQuality.Kbps192,
)

// The range the crossfade length can be set to.
val CrossfadeSecondsRange = 1..12

private val Context.playerPrefs by preferencesDataStore("player")
private val LIVE_BACKGROUND = booleanPreferencesKey("live_background")
private val CROSSFADE = booleanPreferencesKey("crossfade")
private val CROSSFADE_SECONDS = intPreferencesKey("crossfade_seconds")
private val LYRICS_ONLINE = booleanPreferencesKey("lyrics_online")
private val COPIES = stringPreferencesKey("copies")
private val STREAM_WIFI = stringPreferencesKey("stream_wifi")
private val STREAM_MOBILE = stringPreferencesKey("stream_mobile")

// A saved choice, or the default when nothing (or something unknown) is saved.
private inline fun <reified T : Enum<T>> choice(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default

@Singleton
class PlayerSettings @Inject constructor(@ApplicationContext private val context: Context) {
    val prefs: Flow<PlayerPrefs> = context.playerPrefs.data.map { stored ->
        val defaults = PlayerPrefs()
        PlayerPrefs(
            liveBackground = stored[LIVE_BACKGROUND] ?: defaults.liveBackground,
            crossfade = stored[CROSSFADE] ?: defaults.crossfade,
            crossfadeSeconds = (stored[CROSSFADE_SECONDS] ?: defaults.crossfadeSeconds).coerceIn(CrossfadeSecondsRange),
            lyricsOnline = stored[LYRICS_ONLINE] ?: defaults.lyricsOnline,
        )
    }

    val streamPrefs: Flow<StreamPrefs> = context.playerPrefs.data.map { stored ->
        val defaults = StreamPrefs()
        StreamPrefs(
            copies = choice(stored[COPIES], defaults.copies),
            wifi = choice(stored[STREAM_WIFI], defaults.wifi),
            mobile = choice(stored[STREAM_MOBILE], defaults.mobile),
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

    suspend fun setLyricsOnline(on: Boolean) {
        context.playerPrefs.edit { it[LYRICS_ONLINE] = on }
    }

    suspend fun setCopies(preference: CopyPreference) {
        context.playerPrefs.edit { it[COPIES] = preference.name }
    }

    suspend fun setStreamWifi(quality: StreamQuality) {
        context.playerPrefs.edit { it[STREAM_WIFI] = quality.name }
    }

    suspend fun setStreamMobile(quality: StreamQuality) {
        context.playerPrefs.edit { it[STREAM_MOBILE] = quality.name }
    }
}

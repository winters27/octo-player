package app.winters.octo.offline

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.playback.StreamQuality
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// How much room recently played server songs may take on the phone, so they
// play again without the network. Off keeps nothing.
enum class CacheSize(val bytes: Long) {
    Off(0),
    Gb1(1L shl 30),
    Gb2(2L shl 30),
    Gb5(5L shl 30),
    Gb10(10L shl 30),
}

// The range each "songs ahead" count can be set to.
val PrefetchRange = 0..10

// What to keep on the phone for playing without a connection. The defaults
// are what a new install gets.
data class OfflinePrefs(
    val cacheSize: CacheSize = CacheSize.Gb2,
    // How many songs ahead in the queue to fetch while one plays.
    val prefetchWifi: Int = 3,
    val prefetchMobile: Int = 1,
    // Downloads come as the server's file unless a smaller MP3 is chosen.
    val downloadQuality: StreamQuality = StreamQuality.Original,
    val wifiOnly: Boolean = true,
    // Stream on Wi-Fi even when a downloaded copy is on the phone.
    val streamOnWifi: Boolean = false,
    val keepLiked: Boolean = false,
    // Playlists whose songs are kept downloaded, by id.
    val keptPlaylists: Set<String> = emptySet(),
)

private val Context.offlinePrefs by preferencesDataStore("offline")
private val CACHE_SIZE = stringPreferencesKey("cache_size")
private val PREFETCH_WIFI = intPreferencesKey("prefetch_wifi")
private val PREFETCH_MOBILE = intPreferencesKey("prefetch_mobile")
private val DOWNLOAD_QUALITY = stringPreferencesKey("download_quality")
private val WIFI_ONLY = booleanPreferencesKey("wifi_only")
private val STREAM_ON_WIFI = booleanPreferencesKey("stream_on_wifi")
private val KEEP_LIKED = booleanPreferencesKey("keep_liked")
private val KEPT_PLAYLISTS = stringSetPreferencesKey("kept_playlists")

private inline fun <reified T : Enum<T>> choice(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default

@Singleton
class OfflineSettings @Inject constructor(@ApplicationContext private val context: Context) {
    val prefs: Flow<OfflinePrefs> = context.offlinePrefs.data.map { stored ->
        val defaults = OfflinePrefs()
        OfflinePrefs(
            cacheSize = choice(stored[CACHE_SIZE], defaults.cacheSize),
            prefetchWifi = (stored[PREFETCH_WIFI] ?: defaults.prefetchWifi).coerceIn(PrefetchRange),
            prefetchMobile = (stored[PREFETCH_MOBILE] ?: defaults.prefetchMobile).coerceIn(PrefetchRange),
            downloadQuality = choice(stored[DOWNLOAD_QUALITY], defaults.downloadQuality),
            wifiOnly = stored[WIFI_ONLY] ?: defaults.wifiOnly,
            streamOnWifi = stored[STREAM_ON_WIFI] ?: defaults.streamOnWifi,
            keepLiked = stored[KEEP_LIKED] ?: defaults.keepLiked,
            keptPlaylists = stored[KEPT_PLAYLISTS] ?: defaults.keptPlaylists,
        )
    }.distinctUntilChanged()

    suspend fun setCacheSize(size: CacheSize) {
        context.offlinePrefs.edit { it[CACHE_SIZE] = size.name }
    }

    suspend fun setPrefetchWifi(count: Int) {
        context.offlinePrefs.edit { it[PREFETCH_WIFI] = count.coerceIn(PrefetchRange) }
    }

    suspend fun setPrefetchMobile(count: Int) {
        context.offlinePrefs.edit { it[PREFETCH_MOBILE] = count.coerceIn(PrefetchRange) }
    }

    suspend fun setDownloadQuality(quality: StreamQuality) {
        context.offlinePrefs.edit { it[DOWNLOAD_QUALITY] = quality.name }
    }

    suspend fun setWifiOnly(on: Boolean) {
        context.offlinePrefs.edit { it[WIFI_ONLY] = on }
    }

    suspend fun setStreamOnWifi(on: Boolean) {
        context.offlinePrefs.edit { it[STREAM_ON_WIFI] = on }
    }

    suspend fun setKeepLiked(on: Boolean) {
        context.offlinePrefs.edit { it[KEEP_LIKED] = on }
    }

    suspend fun setPlaylistKept(id: String, kept: Boolean) {
        context.offlinePrefs.edit { p ->
            val now = p[KEPT_PLAYLISTS].orEmpty()
            p[KEPT_PLAYLISTS] = if (kept) now + id else now - id
        }
    }

    // Stops every rule at once, for "Remove all".
    suspend fun clearRules() {
        context.offlinePrefs.edit { p ->
            p[KEEP_LIKED] = false
            p[KEPT_PLAYLISTS] = emptySet()
        }
    }
}

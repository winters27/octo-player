package app.winters.octo.player

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.playback.CopyPreference
import app.winters.octo.playback.PITCH_RANGE_SEMITONES
import app.winters.octo.playback.Pace
import app.winters.octo.playback.StreamQuality
import app.winters.octo.playback.paceOf
import app.winters.octo.playback.snapSpeed
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

// How the full player looks and plays. The defaults are what a new install
// gets. A backup saves all of it.
@Serializable
data class PlayerPrefs(
    // The moving colour background, on phones that can draw it.
    val liveBackground: Boolean = true,
    // Songs blending into each other, and over how many seconds.
    val crossfade: Boolean = false,
    val crossfadeSeconds: Int = 6,
    // Looking lyrics up online when the server and the song's files have none.
    val lyricsOnline: Boolean = true,
    // How fast music plays, whether the voice keeps its pitch at other
    // speeds, and a pitch shift in semitones on top.
    val speed: Float = 1f,
    val keepPitch: Boolean = true,
    val pitchSemitones: Int = 0,
    // Quiet stretches inside songs are skipped.
    val skipSilence: Boolean = false,
    // Similar songs keep playing once the queue runs out.
    val autoplay: Boolean = true,
    // Carrying on when headphones come back: over a cable, over Bluetooth,
    // and whether to play on connect even when the music was not paused by
    // the headphones going.
    val resumeWired: Boolean = false,
    val resumeBluetooth: Boolean = false,
    val resumeAlways: Boolean = false,
) {
    // What the player uses: the blend length, or 0 for none.
    val crossfadeMs: Long get() = if (crossfade) crossfadeSeconds * 1_000L else 0

    // The speed and pitch the player is set to.
    val pace: Pace get() = paceOf(speed, keepPitch, pitchSemitones)

    // Whether speed and pitch are as they came.
    val paceIsDefault: Boolean get() = speed == 1f && keepPitch && pitchSemitones == 0
}

// Where songs play from when a server is connected. On mobile data a 192
// kbps MP3 by default: it sounds close to the original on phone headphones
// and uses about 86 MB an hour, where a lossless file can use five times
// that.
@Serializable
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
private val SPEED = floatPreferencesKey("speed")
private val KEEP_PITCH = booleanPreferencesKey("keep_pitch")
private val PITCH_SEMITONES = intPreferencesKey("pitch_semitones")
private val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
private val AUTOPLAY = booleanPreferencesKey("autoplay")
private val RESUME_WIRED = booleanPreferencesKey("resume_wired")
private val RESUME_BLUETOOTH = booleanPreferencesKey("resume_bluetooth")
private val RESUME_ALWAYS = booleanPreferencesKey("resume_always")
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
            speed = snapSpeed(stored[SPEED] ?: defaults.speed),
            keepPitch = stored[KEEP_PITCH] ?: defaults.keepPitch,
            pitchSemitones = (stored[PITCH_SEMITONES] ?: defaults.pitchSemitones)
                .coerceIn(-PITCH_RANGE_SEMITONES, PITCH_RANGE_SEMITONES),
            skipSilence = stored[SKIP_SILENCE] ?: defaults.skipSilence,
            autoplay = stored[AUTOPLAY] ?: defaults.autoplay,
            resumeWired = stored[RESUME_WIRED] ?: defaults.resumeWired,
            resumeBluetooth = stored[RESUME_BLUETOOTH] ?: defaults.resumeBluetooth,
            resumeAlways = stored[RESUME_ALWAYS] ?: defaults.resumeAlways,
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

    suspend fun setSpeed(speed: Float) {
        context.playerPrefs.edit { it[SPEED] = snapSpeed(speed) }
    }

    suspend fun setKeepPitch(on: Boolean) {
        context.playerPrefs.edit { it[KEEP_PITCH] = on }
    }

    suspend fun setPitchSemitones(semitones: Int) {
        context.playerPrefs.edit { it[PITCH_SEMITONES] = semitones.coerceIn(-PITCH_RANGE_SEMITONES, PITCH_RANGE_SEMITONES) }
    }

    // Normal speed and pitch again.
    suspend fun resetPace() {
        context.playerPrefs.edit {
            it.remove(SPEED)
            it.remove(KEEP_PITCH)
            it.remove(PITCH_SEMITONES)
        }
    }

    suspend fun setSkipSilence(on: Boolean) {
        context.playerPrefs.edit { it[SKIP_SILENCE] = on }
    }

    suspend fun setAutoplay(on: Boolean) {
        context.playerPrefs.edit { it[AUTOPLAY] = on }
    }

    suspend fun setResumeWired(on: Boolean) {
        context.playerPrefs.edit { it[RESUME_WIRED] = on }
    }

    suspend fun setResumeBluetooth(on: Boolean) {
        context.playerPrefs.edit { it[RESUME_BLUETOOTH] = on }
    }

    suspend fun setResumeAlways(on: Boolean) {
        context.playerPrefs.edit { it[RESUME_ALWAYS] = on }
    }

    // Everything at once, for a backup.
    suspend fun snapshot(): Pair<PlayerPrefs, StreamPrefs> = prefs.first() to streamPrefs.first()

    // Puts back every setting from a backup in one go.
    suspend fun restore(player: PlayerPrefs, stream: StreamPrefs) {
        context.playerPrefs.edit {
            it[LIVE_BACKGROUND] = player.liveBackground
            it[CROSSFADE] = player.crossfade
            it[CROSSFADE_SECONDS] = player.crossfadeSeconds.coerceIn(CrossfadeSecondsRange)
            it[LYRICS_ONLINE] = player.lyricsOnline
            it[SPEED] = snapSpeed(player.speed)
            it[KEEP_PITCH] = player.keepPitch
            it[PITCH_SEMITONES] = player.pitchSemitones.coerceIn(-PITCH_RANGE_SEMITONES, PITCH_RANGE_SEMITONES)
            it[SKIP_SILENCE] = player.skipSilence
            it[AUTOPLAY] = player.autoplay
            it[RESUME_WIRED] = player.resumeWired
            it[RESUME_BLUETOOTH] = player.resumeBluetooth
            it[RESUME_ALWAYS] = player.resumeAlways
            it[COPIES] = stream.copies.name
            it[STREAM_WIFI] = stream.wifi.name
            it[STREAM_MOBILE] = stream.mobile.name
        }
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

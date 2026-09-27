package app.winters.octo.lyrics

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

// Lyrics whose timing is off for one song can be moved earlier or later by
// this much a step, up to this far either way.
const val TIMING_STEP_MS = 250L
const val TIMING_LIMIT_MS = 10_000L

// A timing offset says how much later than written the words are heard: at
// +500 a line written at 1:00.00 is sung at 1:00.50. So the lyrics follow a
// clock that runs that far behind the song, and a tap on a line plays from
// where it is heard.

// Where in the lyrics the song is, at this point in the song.
fun lyricsClock(positionMs: Long, offsetMs: Long): Long = positionMs - offsetMs

// Where in the song a line written at this time is heard.
fun heardAt(lyricsMs: Long, offsetMs: Long): Long = (lyricsMs + offsetMs).coerceAtLeast(0)

// The offset after moving it some steps: later is up, earlier is down.
fun stepTiming(offsetMs: Long, steps: Int): Long =
    (offsetMs + steps * TIMING_STEP_MS).coerceIn(-TIMING_LIMIT_MS, TIMING_LIMIT_MS)

// The offset in words: "In time", "0.25 s later", "1.5 s earlier".
fun timingLabel(offsetMs: Long): String {
    if (offsetMs == 0L) return "In time"
    val seconds = BigDecimal.valueOf(abs(offsetMs), 3).stripTrailingZeros().toPlainString()
    return if (offsetMs > 0) "$seconds s later" else "$seconds s earlier"
}

// The offset as a signed number of seconds, for the timing control:
// "0 s", "+0.75 s", "-1.5 s".
fun signedTiming(offsetMs: Long): String {
    if (offsetMs == 0L) return "0 s"
    val seconds = BigDecimal.valueOf(abs(offsetMs), 3).stripTrailingZeros().toPlainString()
    return if (offsetMs > 0) "+$seconds s" else "-$seconds s"
}

// Lyrics settings: timing, the screen, and how the lyrics look.
internal val Context.lyricsData by preferencesDataStore("lyrics")

private val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")

private const val OFFSET_PREFIX = "offset:"

private fun offsetKey(trackId: String) = longPreferencesKey("$OFFSET_PREFIX$trackId")

// Each song's lyrics timing, kept only for songs that were moved, and
// whether the screen stays on while lyrics show.
@Singleton
class LyricsTiming @Inject constructor(@ApplicationContext private val context: Context) {
    fun offsetFor(trackId: String): Flow<Long> =
        context.lyricsData.data.map { it[offsetKey(trackId)] ?: 0L }.distinctUntilChanged()

    suspend fun step(trackId: String, steps: Int) {
        context.lyricsData.edit { prefs ->
            val moved = stepTiming(prefs[offsetKey(trackId)] ?: 0L, steps)
            if (moved == 0L) prefs.remove(offsetKey(trackId)) else prefs[offsetKey(trackId)] = moved
        }
    }

    suspend fun reset(trackId: String) {
        context.lyricsData.edit { it.remove(offsetKey(trackId)) }
    }

    // Every song whose timing was moved, by track id, for a backup.
    suspend fun offsets(): Map<String, Long> = context.lyricsData.data.first().asMap().entries
        .mapNotNull { (key, value) ->
            val id = key.name.takeIf { it.startsWith(OFFSET_PREFIX) }?.removePrefix(OFFSET_PREFIX) ?: return@mapNotNull null
            (value as? Long)?.let { id to it }
        }
        .toMap()

    // Puts back one song's timing from a backup.
    suspend fun setOffset(trackId: String, offsetMs: Long) {
        val moved = offsetMs.coerceIn(-TIMING_LIMIT_MS, TIMING_LIMIT_MS)
        context.lyricsData.edit { if (moved == 0L) it.remove(offsetKey(trackId)) else it[offsetKey(trackId)] = moved }
    }

    // On unless switched off.
    val keepScreenOn: Flow<Boolean> = context.lyricsData.data.map { it[KEEP_SCREEN_ON] ?: true }.distinctUntilChanged()

    suspend fun setKeepScreenOn(on: Boolean) {
        context.lyricsData.edit { it[KEEP_SCREEN_ON] = on }
    }
}

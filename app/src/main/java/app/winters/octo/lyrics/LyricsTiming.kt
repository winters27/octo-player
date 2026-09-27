package app.winters.octo.lyrics

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import app.winters.octo.sound.AudioOutput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

// Lyrics whose timing is off can be moved earlier or later by this much a
// step: for one song up to this far either way, for an output (the speaker,
// a pair of earbuds) up to the second limit, since a sound output is never
// seconds late.
const val TIMING_STEP_MS = 50L
const val TIMING_LIMIT_MS = 10_000L
const val OUTPUT_TIMING_LIMIT_MS = 2_000L

// A screen shows a frame about this many frames after the time it was drawn
// for: one to draw it and one for the display to take it up.
const val SCREEN_FRAMES = 2

// A timing offset says how much later than written the words are heard: at
// +500 a line written at 1:00.00 is sung at 1:00.50. So the lyrics follow a
// clock that runs that far behind the song, and a tap on a line plays from
// where it is heard.

//
// The song's own offset and the output's add up: the words follow the song
// less both, and a tap plays from the line plus both. Everything below takes
// the sum.

// Where in the lyrics the song is, at this point in the song.
fun lyricsClock(positionMs: Long, offsetMs: Long): Long = positionMs - offsetMs

// Where in the song a line written at this time is heard.
fun heardAt(lyricsMs: Long, offsetMs: Long): Long = (lyricsMs + offsetMs).coerceAtLeast(0)

// The whole offset: the song's, and the output's on top.
fun totalOffset(songMs: Long, outputMs: Long): Long = songMs + outputMs

// The offset after moving it some steps: later is up, earlier is down.
fun stepTiming(offsetMs: Long, steps: Int, limitMs: Long = TIMING_LIMIT_MS): Long =
    (offsetMs + steps * TIMING_STEP_MS).coerceIn(-limitMs, limitMs)

// How far ahead of the song the words are shown so they are on the screen
// when the song is heard: the frames the screen takes, at its refresh rate.
// Every output starts from this, and its own setting moves it from there.
fun screenLeadMs(refreshHz: Float): Long {
    val hz = if (refreshHz.isFinite() && refreshHz >= 30f) refreshHz else 60f
    return -Math.round(SCREEN_FRAMES * 1000.0 / hz)
}

// An output's offset from what is kept, 0 for an output never moved.
fun outputOffsetIn(offsets: Map<String, Long>, outputKey: String): Long = offsets[outputKey] ?: 0L

// Every output's offset out of the stored settings, by output key.
fun outputOffsetsIn(stored: Map<String, Any?>): Map<String, Long> = stored.entries
    .mapNotNull { (name, value) ->
        val key = name.takeIf { it.startsWith(OUTPUT_OFFSET_PREFIX) }?.removePrefix(OUTPUT_OFFSET_PREFIX) ?: return@mapNotNull null
        (value as? Long)?.let { key to it }
    }
    .toMap()

// The offsets in words, song and output, for a menu line; null when
// neither was moved.
fun timingSummary(songMs: Long, outputMs: Long, outputLabel: String): String? = listOfNotNull(
    songMs.takeIf { it != 0L }?.let { "This song ${signedTiming(it)}" },
    outputMs.takeIf { it != 0L }?.let { "$outputLabel ${signedTiming(it)}" },
).joinToString(" · ").ifEmpty { null }

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

private const val OUTPUT_OFFSET_PREFIX = "lyrics_output_offset:"

private fun offsetKey(trackId: String) = longPreferencesKey("$OFFSET_PREFIX$trackId")

private fun outputOffsetKey(outputKey: String) = longPreferencesKey("$OUTPUT_OFFSET_PREFIX$outputKey")

// The stored settings by their names.
private fun Preferences.byName(): Map<String, Any?> = asMap().mapKeys { (key, _) -> key.name }

// Each song's lyrics timing and each output's, kept only for those that
// were moved, and whether the screen stays on while lyrics show.
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

    // The timing of whichever output is playing now, following it as it
    // changes, in the same milliseconds and direction as a song's: minus is
    // sooner.
    fun outputOffsetFor(output: Flow<AudioOutput>): Flow<Long> =
        combine(output, context.lyricsData.data) { out, prefs -> outputOffsetIn(outputOffsetsIn(prefs.byName()), out.key) }
            .distinctUntilChanged()

    suspend fun stepOutput(outputKey: String, steps: Int) {
        context.lyricsData.edit { prefs ->
            val moved = stepTiming(prefs[outputOffsetKey(outputKey)] ?: 0L, steps, OUTPUT_TIMING_LIMIT_MS)
            if (moved == 0L) prefs.remove(outputOffsetKey(outputKey)) else prefs[outputOffsetKey(outputKey)] = moved
        }
    }

    suspend fun resetOutput(outputKey: String) {
        context.lyricsData.edit { it.remove(outputOffsetKey(outputKey)) }
    }

    // Every output whose timing was moved, by output key, for a backup.
    suspend fun outputOffsets(): Map<String, Long> = outputOffsetsIn(context.lyricsData.data.first().byName())

    // Puts back one output's timing from a backup.
    suspend fun setOutputOffset(outputKey: String, offsetMs: Long) {
        val moved = offsetMs.coerceIn(-OUTPUT_TIMING_LIMIT_MS, OUTPUT_TIMING_LIMIT_MS)
        context.lyricsData.edit { if (moved == 0L) it.remove(outputOffsetKey(outputKey)) else it[outputOffsetKey(outputKey)] = moved }
    }

    // On unless switched off.
    val keepScreenOn: Flow<Boolean> = context.lyricsData.data.map { it[KEEP_SCREEN_ON] ?: true }.distinctUntilChanged()

    suspend fun setKeepScreenOn(on: Boolean) {
        context.lyricsData.edit { it[KEEP_SCREEN_ON] = on }
    }
}

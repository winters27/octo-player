package app.winters.octo.desktop.lyrics

import app.winters.octo.desktop.player.DesktopPlayer
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.roundToLong

// Lyrics timing, as on the phone. An offset says how much later than
// written the words are heard: at +500 a line written at 1:00.00 is sung
// at 1:00.50. A song's own offset and the output's add up.
const val TIMING_STEP_MS = 50L
const val TIMING_LIMIT_MS = 10_000L
const val OUTPUT_TIMING_LIMIT_MS = 2_000L

// A screen shows a frame about this many frames after it is drawn for.
const val SCREEN_FRAMES = 2

// Where in the song a line written at this time is heard.
fun heardAt(lyricsMs: Long, offsetMs: Long): Long = (lyricsMs + offsetMs).coerceAtLeast(0)

fun stepTiming(offsetMs: Long, steps: Int, limitMs: Long = TIMING_LIMIT_MS): Long =
    (offsetMs + steps * TIMING_STEP_MS).coerceIn(-limitMs, limitMs)

// How far ahead of the song the words are shown, so they are on the glass
// when the song is heard: the frames the screen takes at its refresh rate.
// Every output starts from this; its own setting moves it from there.
fun screenLeadMs(refreshHz: Float): Long {
    val hz = if (refreshHz.isFinite() && refreshHz >= 30f) refreshHz else 60f
    return -(SCREEN_FRAMES * 1000.0 / hz).roundToLong()
}

// The offset as a signed number of seconds: "0 s", "+0.75 s", "-1.5 s".
fun signedTiming(offsetMs: Long): String {
    if (offsetMs == 0L) return "0 s"
    val seconds = BigDecimal.valueOf(abs(offsetMs), 3).stripTrailingZeros().toPlainString()
    return if (offsetMs > 0) "+$seconds s" else "-$seconds s"
}

// The refresh rate of the main screen, for the screen lead.
fun screenRefreshHz(): Float = runCatching {
    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode.refreshRate.toFloat()
}.getOrDefault(60f)

// The lyrics of the song playing now, fetched once per song however many
// views show them (the side panel and the full player), and again when
// the listener chose other lyrics, hid them or showed them again.
class LyricsModel(private val player: DesktopPlayer, val sources: LyricsSources, private val settings: SettingsStore, scope: CoroutineScope) {
    // The song, and its lyrics once found (null while looking).
    data class Shown(val song: Song?, val answer: LyricsAnswer?)

    private val _state = MutableStateFlow(Shown(null, null))
    val state: StateFlow<Shown> = _state

    init {
        @OptIn(ExperimentalCoroutinesApi::class)
        scope.launch {
            combine(player.state.map { it.current?.song }.distinctUntilChanged(), sources.revisions) { song, revisions ->
                song to (song?.let { revisions[it.id] } ?: 0)
            }
                .distinctUntilChanged()
                .mapLatest { (song, _) ->
                    if (song == null) return@mapLatest Shown(null, LyricsAnswer.None)
                    _state.value = Shown(song, null)
                    Shown(song, sources.answerFor(song))
                }
                .collect { _state.value = it }
        }
    }

    // ---- Timing ----

    fun songOffset(songId: String): Long = settings.current.lyrics.offsets[songId] ?: 0L

    fun outputOffset(device: String?): Long = device?.let { settings.current.lyrics.outputOffsets[it] } ?: 0L

    fun stepSong(songId: String, steps: Int) = settings.update { s ->
        val moved = stepTiming(s.lyrics.offsets[songId] ?: 0L, steps)
        val offsets = if (moved == 0L) s.lyrics.offsets - songId else s.lyrics.offsets + (songId to moved)
        s.copy(lyrics = s.lyrics.copy(offsets = offsets))
    }

    fun stepOutput(device: String, steps: Int) = settings.update { s ->
        val moved = stepTiming(s.lyrics.outputOffsets[device] ?: 0L, steps, OUTPUT_TIMING_LIMIT_MS)
        val offsets = if (moved == 0L) s.lyrics.outputOffsets - device else s.lyrics.outputOffsets + (device to moved)
        s.copy(lyrics = s.lyrics.copy(outputOffsets = offsets))
    }
}

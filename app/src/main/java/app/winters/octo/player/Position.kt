package app.winters.octo.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import app.winters.octo.playback.NowPlaying
import kotlinx.coroutines.delay

// How far into the song playback is, read again every so often while it
// plays. It is writable so a seek shows at once, before the next read.
@Composable
fun rememberPositionMs(now: NowPlaying, positionMs: () -> Long, everyMs: Long = 250): MutableLongState {
    val position = remember { mutableLongStateOf(positionMs()) }
    LaunchedEffect(now.trackId, now.isPlaying, now.durationMs) {
        do {
            position.longValue = positionMs()
            delay(everyMs)
        } while (now.isPlaying)
    }
    return position
}

fun NowPlaying.fractionAt(positionMs: Long): Float =
    if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

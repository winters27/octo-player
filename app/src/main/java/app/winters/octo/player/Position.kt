package app.winters.octo.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import app.winters.octo.playback.NowPlaying

// How far into the song playback is. While music plays it is read on every
// frame, so progress glides instead of stepping, even on a short song.
// Reading it is cheap: the player works the position out locally. It is
// writable so a seek shows at once. Read it while drawing, not composing,
// so a moving line only redraws.
@Composable
fun rememberPositionMs(now: NowPlaying, positionMs: () -> Long): MutableLongState {
    val position = remember { mutableLongStateOf(positionMs()) }
    LaunchedEffect(now.trackId, now.isPlaying, now.durationMs) {
        position.longValue = positionMs()
        while (now.isPlaying) {
            withFrameMillis { position.longValue = positionMs() }
        }
    }
    return position
}

fun NowPlaying.fractionAt(positionMs: Long): Float =
    if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

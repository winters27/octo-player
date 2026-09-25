package app.winters.octo.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// What the crossfade needs to know about a song.
data class FadeSong(val albumId: String?, val albumOrder: Int?, val durationMs: Long)

// The shortest blend worth doing; anything shorter just sounds like a cut.
internal const val SHORTEST_FADE_MS = 500L

// How long the next song should fade in over the end of this one, or 0 for
// no crossfade. The fade shrinks to half of either song, so a short song is
// never mostly fade.
internal fun crossfadeLength(
    current: FadeSong,
    next: FadeSong?,
    fadeMs: Long,
    repeatOne: Boolean,
    stopAtEndOfSong: Boolean,
): Long {
    if (fadeMs <= 0 || next == null || repeatOne || stopAtEndOfSong) return 0
    if (current.durationMs <= 0 || next.durationMs <= 0) return 0
    // An album played in order stays gapless: songs meant to run into each
    // other should not be blended.
    if (current.albumId != null && current.albumId == next.albumId &&
        current.albumOrder != null && next.albumOrder == current.albumOrder + 1
    ) {
        return 0
    }
    val length = min(fadeMs, min(current.durationMs, next.durationMs) / 2)
    return if (length < SHORTEST_FADE_MS) 0 else length
}

// Equal-power fade: the two volumes' squares always add up to one, so the
// blend never dips or swells in loudness. `progress` runs from 0 to 1.
internal fun fadeOutVolume(progress: Float): Float = cos(progress.coerceIn(0f, 1f) * PI.toFloat() / 2)

internal fun fadeInVolume(progress: Float): Float = sin(progress.coerceIn(0f, 1f) * PI.toFloat() / 2)

package app.winters.octo.playback

// Start loading the next song this long before the blend, so it is ready.
internal const val SPARE_LOAD_AHEAD_MS = 5_000L

// A next song with no known length is loaded this long before the blend
// instead: songs from outside the library are slow to start, and their
// length is only known once they have opened.
internal const val SLOW_SPARE_LOAD_AHEAD_MS = 15_000L

// The next song's length as far as the crossfade knows it: its listed
// length, or, when the list does not know it (0), what the spare deck
// measured once it opened the song. 0 while nothing knows it.
internal fun nextSongLengthMs(listedMs: Long, spareMs: Long?): Long =
    if (listedMs > 0) listedMs else spareMs?.takeIf { it > 0 } ?: 0

// Whether a blend into the next song waits only on its length: with any
// length it would blend, but its length is not known yet.
internal fun waitsOnLength(current: FadeSong, next: FadeSong?, fadeMs: Long, repeatOne: Boolean, stopAtEndOfSong: Boolean): Boolean {
    if (next == null || next.durationMs > 0) return false
    val long = next.copy(durationMs = Long.MAX_VALUE / 4)
    return crossfadeLength(current, long, fadeMs, repeatOne, stopAtEndOfSong) > 0
}

// How long before the end of the song (in real time) the spare deck loads
// the next song: early for a song whose length is not known yet, so it has
// opened and said its length before the blend would start.
internal fun loadLeadMs(blendMs: Long, fadeMs: Long, lengthKnown: Boolean): Long =
    if (lengthKnown) blendMs + SPARE_LOAD_AHEAD_MS else fadeMs + SLOW_SPARE_LOAD_AHEAD_MS

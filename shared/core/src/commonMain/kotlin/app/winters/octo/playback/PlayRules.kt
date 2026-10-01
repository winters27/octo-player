package app.winters.octo.playback

// A play only counts once enough of the song was heard: half of it or four
// minutes, whichever comes first, and never for clips under 30 seconds.
fun countsAsPlay(playedMs: Long, durationMs: Long): Boolean =
    durationMs >= 30_000 && playedMs >= minOf(durationMs / 2, 240_000)

// How much more of a song must be heard before a play counts, or null
// when it never can (a clip under 30 seconds, or no known length).
fun msLeftToCount(playedMs: Long, durationMs: Long): Long? =
    if (durationMs < 30_000) null else maxOf(0L, minOf(durationMs / 2, 240_000) - playedMs)

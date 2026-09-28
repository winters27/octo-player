package app.winters.octo.playback

// The sleep timer's choices and timing, the same on the phone and the
// desktop. Each app runs its own timer on its own player.

// Where the sleep timer is: off, counting down, or waiting for songs to end.
sealed interface SleepState {
    data object Off : SleepState
    data class Counting(val remainingMs: Long) : SleepState
    data object EndOfSong : SleepState

    // Waiting for this many songs to end, the one playing included. Always
    // 2 or more; the last one is EndOfSong.
    data class Songs(val left: Int) : SleepState

    // Waiting for one song further down the queue to end: `key` is its
    // queue entry, `title` what to call it.
    data class AfterSong(val key: String, val title: String) : SleepState
}

// The lengths offered, in minutes.
val SLEEP_MINUTES = listOf(5, 15, 30, 45, 60)

// What a running countdown can be lengthened by, in minutes.
val SLEEP_EXTENSIONS = listOf(5, 10)

// How many songs the music can stop after, the one playing included.
val SLEEP_SONG_COUNTS = listOf(1, 2, 3, 5)

// The longest custom timer: twelve hours.
const val MAX_SLEEP_MINUTES = 720

// The last stretch of a timer, over which the music fades out.
const val SLEEP_FADE_MS = 30_000L

// How loud the music is with this long left: full until the last 30
// seconds, then down to silence. Squared, so the fade sounds even.
fun sleepFade(remainingMs: Long): Float {
    val left = (remainingMs.toFloat() / SLEEP_FADE_MS).coerceIn(0f, 1f)
    return left * left
}

// How long until the next tick: on each whole second while counting, so
// the clock on screen never skips, and ten times a second through the fade.
fun untilNextSleepTick(remainingMs: Long): Long {
    val step = if (remainingMs > SLEEP_FADE_MS) 1_000L else 100L
    return (remainingMs - 1) % step + 1
}

// A timer in words, for the timer's card and its button.
fun sleepSummary(state: SleepState): String = when (state) {
    SleepState.Off -> "Off"
    is SleepState.Counting -> "${((state.remainingMs + 59_999) / 60_000).toInt()} min left"
    SleepState.EndOfSong -> "At the end of this song"
    is SleepState.Songs -> "After ${if (state.left == 1) "1 song" else "${state.left} songs"}"
    is SleepState.AfterSong -> "After ${state.title}"
}
